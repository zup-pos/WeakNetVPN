package com.weaktool.vpn.core.vpn

import com.weaktool.vpn.qos.NetProfile
import com.weaktool.vpn.qos.QosPipeline
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingDeque
import kotlin.random.Random

/**
 * TCP 转发器（透明代理式）。
 *
 * 架构目标：与 UDP 一致，所有远端 socket 注册到同一个 Selector，由 reactor
 * 单线程事件驱动，绝不忙轮询。TCP 有状态，比 UDP 复杂，本类实现核心连接态机，
 * 其余进阶语义(乱序重组/重传去重/窗口缩放/精确 RST)以 TODO 标记留待迭代。
 *
 * 转发模型(透明代理)：本机对 app 扮演"被访问的服务器"，同时对真实服务器扮演
 * "客户端"。两条方向的载荷都以不透明字节流方式在 SocketChannel 与 tun 间搬运，
 * 由两侧独立的内核 TCP 栈负责各自的序号/确认，我们只需维护两侧的字节累计量，
 * 不必逐字节搬移序号。
 */
class TcpForwarder(
    private val engine: VpnEngine,
    private val selector: Selector
) : VpnEngine.TimerSource {

    // ---------- TCP 标志 / 状态 ----------
    companion object {
        const val F_SYN = 0x02
        const val F_RST = 0x04
        const val F_ACK = 0x10
        const val F_FIN = 0x01

        const val ST_SYN_SENT_APP = 0   // 已向 app 回 SYN-ACK，等 app ACK
        const val ST_ESTABLISHED = 1    // 应用侧已建立
        const val ST_CLOSING = 2        // 任一侧 FIN，等待收尾

        const val MAX_CHUNK = 32768

        fun randIsn(): Long = (Random.nextLong() and 0xffff_ffffL)
        fun mask32(v: Long): Long = v and 0xffff_ffffL
    }

    // ---------- 会话 ----------
    private class Session(
        private val engine: VpnEngine,
        private val selector: Selector,
        val srcIp: Int, val srcPort: Int,
        val dstIp: Int, val dstPort: Int
    ) {
        val remote: SocketChannel = SocketChannel.open()
        var key: SelectionKey? = null

        var state = ST_SYN_SENT_APP

        // 应用侧双向字节累计（用于生成发往 app 的包头的 seq/ack）。
        var appIsn: Long = 0            // app 发起连接的初始序号
        var appRecvCount: Long = 0      // 已从 app 收到的载荷字节数
        var ourIsn: Long = randIsn()    // 本机(扮演 server)初始序号
        var ourSentCount: Long = 0      // 已发给 app 的载荷字节数

        // 应用->远端 待写缓冲(连接未就绪时暂存)。
        val pending = LinkedBlockingDeque<ByteArray>()

        // 远端->应用 下行弱网流水线：emit 写回 tun。
        val downlink = QosPipeline { pkt -> engine.writeToTun(pkt) }

        fun appAck() = mask32(appIsn + appRecvCount + 1)       // 发往 app 的 ACK
        fun appSeq() = mask32(ourIsn + ourSentCount)           // 发往 app 的 seq(下个字节)
        fun register(ops: Int) {
            key = remote.register(selector, ops, this)
        }
    }

    private val sessions = ConcurrentHashMap<String, Session>()
    private var currentProfile: NetProfile = NetProfile.DISABLED

    private fun key(srcIp: Int, sp: Int, dstIp: Int, dp: Int) =
        "$srcIp:$sp->$dstIp:$dp"

    // ---------- 上行：应用包进来 (读线程回调) ----------
    fun forward(data: ByteArray, header: IpPacket.Header, off: Int) {
        if (header.moreFragments || header.fragmentOffset != 0) return
        if (off + 20 > data.size) return

        val sp = u16(data, off); val dp = u16(data, off + 2)
        val seq = readSeq(data, off + 4)
        val hlen = ((data[off + 12].toInt() ushr 4) and 0x0f) * 4
        val flags = data[off + 13].toInt() and 0xff
        val payloadStart = off + hlen
        val payloadLen = data.size - payloadStart

        val k = key(header.src, sp, header.dst, dp)
        val s = sessions.getOrPut(k) { newSession(header.src, sp, header.dst, dp) }

        if ((flags and F_SYN) != 0 && (flags and F_ACK) == 0) {
            // 1) app SYN：扮演 server 回 SYN-ACK，同时去连接真实服务器。
            s.appIsn = seq
            sendToApp(s, s.ourIsn, mask32(s.appIsn + 1), F_SYN or F_ACK, null)
            connectRemote(s)
            return
        }
        if (s.state == ST_SYN_SENT_APP && (flags and F_ACK) != 0) {
            // 2) app ACK 确认我们的 SYN-ACK：应用侧握手完成。
            s.state = ST_ESTABLISHED
        }
        if (s.state != ST_ESTABLISHED && s.state != ST_CLOSING) {
            return // 仍在对真实服务器建连，静默丢弃应用侧后续（重传由 app 处理）
        }

        // 3) 载荷转发：仅当本次是新数据（简化：忽略重复 ACK 判重，见 TODO）。
        if (payloadLen > 0) {
            val chunk = ByteArray(payloadLen)
            System.arraycopy(data, payloadStart, chunk, 0, payloadLen)
            s.appRecvCount += payloadLen
            s.pending.offer(chunk)
            tryFlushRemote(s)
        }
        if ((flags and F_FIN) != 0) {
            // app 关闭写端：转发收尾，远端 shutdownOutput。
            s.state = ST_CLOSING
            try { s.remote.shutdownOutput() } catch (_: Exception) {}
        }
        if ((flags and F_RST) != 0) closeSession(s)
    }

    private fun newSession(srcIp: Int, sp: Int, dstIp: Int, dp: Int): Session {
        val s = Session(engine, selector, srcIp, sp, dstIp, dp)
        s.downlink.applyProfile(currentProfile)
        try {
            s.remote.configureBlocking(false)
            s.remote.connect(InetSocketAddress(IpPacket.ipToStr(dstIp), dp))
            s.register(SelectionKey.OP_CONNECT)
        } catch (_: Exception) { closeSession(s) }
        return s
    }

    private fun connectRemote(s: Session) {
        // 触发立即尝试 connect（已在 newSession 中调用过 connect）。
        tryFlushRemote(s)
    }

    /** 应用载荷已就绪/远端可写时，把积压缓冲写入远端 socket(非阻塞)。 */
    private fun tryFlushRemote(s: Session) {
        try {
            var done = false
            while (!done) {
                val head = s.pending.peek() ?: { done = true; null }()
                if (head == null) break
                val n = s.remote.write(ByteBuffer.wrap(head))
                if (n <= 0) {
                    // 远端缓冲满：注册 OP_WRITE，等可写再续。
                    s.register(SelectionKey.OP_READ or SelectionKey.OP_WRITE)
                    done = true
                } else if (n == head.size) {
                    s.pending.poll()
                } else {
                    val rest = ByteArray(head.size - n)
                    System.arraycopy(head, n, rest, 0, rest.size)
                    s.pending.poll()
                    s.pending.offerFirst(rest)
                }
            }
        } catch (_: Exception) { closeSession(s) }
    }

    // ---------- 下行：远端就绪事件 (reactor 线程) ----------
    fun onSelected(key: SelectionKey, ops: Int) {
        val s = key.attachment() as? Session ?: return
        try {
            if (key.isConnectable) {
                if (!s.remote.finishConnect()) return
                key.interestOps(SelectionKey.OP_READ)
                tryFlushRemote(s)
            }
            if ((ops and SelectionKey.OP_READ) != 0) readRemote(s)
            if ((ops and SelectionKey.OP_WRITE) != 0) {
                key.interestOps(SelectionKey.OP_READ)
                tryFlushRemote(s)
            }
        } catch (_: Exception) { closeSession(s) }
    }

    /** 读远端字节 -> 封成 TCP 段 -> 走下行弱网流水线回写 app。 */
    private fun readRemote(s: Session) {
        val buf = ByteBuffer.allocate(MAX_CHUNK)
        val n = try { s.remote.read(buf) } catch (_: Exception) { -1 }
        if (n == -1) { closeSession(s); return }
        if (n == 0) return
        buf.flip()
        val payload = ByteArray(buf.remaining())
        buf.get(payload)
        s.ourSentCount += payload.size
        val seg = buildSeg(s, s.appSeq(), s.appAck(), F_ACK, payload)
        // 完整 IP+TCP 报文交由 QoS 流水线(延迟/丢包/限速)后写回 tun。
        s.downlink.process(seg)
    }

    // ---------- 生命周期 / 画像 / 调度 ----------
    fun applyProfile(p: NetProfile) {
        currentProfile = p
        for (s in sessions.values) s.downlink.applyProfile(p)
    }

    fun drainDue() { for (s in sessions.values) s.downlink.tick() }

    fun closeAll() {
        for (s in sessions.values) closeSession(s)
        sessions.clear()
    }

    private fun closeSession(s: Session) {
        sessions.remove(key(s.srcIp, s.srcPort, s.dstIp, s.dstPort))
        try { s.key?.cancel() } catch (_: Exception) {}
        try { s.remote.close() } catch (_: Exception) {}
    }

    override fun nextDelayMs(): Long {
        var min = Long.MAX_VALUE
        for (s in sessions.values) {
            val d = s.downlink.nextDelayMs()
            if (d >= 0 && d < min) min = d
        }
        return if (min == Long.MAX_VALUE) -1L else min
    }

    // ---------- TCP 封包 / 校验和 ----------
    /** 生成发往 app 的 TCP 段(含伪头部校验和)。payload 可为空(纯控制段)。 */
    private fun buildSeg(
        s: Session, seq: Long, ack: Long, flags: Int, payload: ByteArray?
    ): ByteArray {
        val data = payload ?: ByteArray(0)
        val tcpLen = 20 + data.size
        val seg = ByteArray(tcpLen)
        seg[0] = (s.srcPort ushr 8).toByte(); seg[1] = s.srcPort.toByte()   // 源=真实服务端口
        seg[2] = (s.dstPort ushr 8).toByte(); seg[3] = s.dstPort.toByte()   // 目的=app端口
        writeSeq(seg, 4, seq); writeSeq(seg, 8, ack)
        seg[12] = 0x50.toByte()               // 数据偏移=5 (20字节头)
        seg[13] = flags.toByte()
        seg[14] = 0x10.toByte(); seg[15] = 0x00 // window=4096 (骨架简化)
        if (data.isNotEmpty()) System.arraycopy(data, 0, seg, 20, data.size)
        // TCP 校验和：伪头(srcIP,dstIP,0,6,len) + 段
        val pseudo = ByteArray(12)
        IpBuilder.writeInt(pseudo, 0, s.dstIp)     // 下行：源=远端服务器(dstIp)
        IpBuilder.writeInt(pseudo, 4, s.srcIp)     // 目的=app(srcIp)
        pseudo[9] = 6                             // proto=TCP
        pseudo[10] = ((tcpLen ushr 8) and 0xff).toByte()
        pseudo[11] = (tcpLen and 0xff).toByte()
        val sum = checksumWithPseudo(pseudo, seg)
        seg[16] = ((sum ushr 8) and 0xff).toByte(); seg[17] = (sum and 0xff).toByte()
        return IpBuilder.ip(IpPacket.TCP, s.dstIp, s.srcIp, seg)
    }

    /** 向 app 直接发送一段(握手/控制段不经过 QoS，保证连接可靠建立)。 */
    private fun sendToApp(s: Session, seq: Long, ack: Long, flags: Int, payload: ByteArray?) {
        if (payload != null) s.ourSentCount += payload.size
        val seg = buildSeg(s, seq, ack, flags, payload)
        engine.writeToTun(seg)
    }

    private fun checksumWithPseudo(pseudo: ByteArray, seg: ByteArray): Int {
        var sum = 0
        var i = 0
        while (i + 1 < pseudo.size) {
            sum += ((pseudo[i].toInt() and 0xff) shl 8) or (pseudo[i + 1].toInt() and 0xff)
            i += 2
        }
        i = 0
        while (i + 1 < seg.size) {
            sum += ((seg[i].toInt() and 0xff) shl 8) or (seg[i + 1].toInt() and 0xff)
            i += 2
        }
        if (i < seg.size) sum += (seg[i].toInt() and 0xff) shl 8
        while (sum ushr 16 != 0) sum = (sum and 0xffff) + (sum ushr 16)
        return sum.inv() and 0xffff
    }


    private fun writeSeq(b: ByteArray, off: Int, v: Long) {
        b[off] = ((v ushr 24) and 0xff).toByte()
        b[off + 1] = ((v ushr 16) and 0xff).toByte()
        b[off + 2] = ((v ushr 8) and 0xff).toByte()
        b[off + 3] = (v and 0xff).toByte()
    }

    private fun readSeq(b: ByteArray, off: Int): Long =
        (((b[off].toInt() and 0xff).toLong() shl 24) or
            ((b[off + 1].toInt() and 0xff).toLong() shl 16) or
            ((b[off + 2].toInt() and 0xff).toLong() shl 8) or
            (b[off + 3].toInt() and 0xff).toLong()) and 0xffff_ffffL

    private fun u16(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xff) shl 8) or (b[o + 1].toInt() and 0xff)
}
