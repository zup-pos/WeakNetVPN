package com.weaktool.vpn.core.vpn

import com.weaktool.vpn.qos.NetProfile
import com.weaktool.vpn.qos.QosPipeline
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.util.concurrent.ConcurrentHashMap

/**
 * UDP 转发器：每个"四元组会话"对应一个连接到真实远端的 DatagramChannel，
 * 全部注册到同一个 Selector，由 reactor 单线程按事件驱动收发。
 *
 * 上行(应用->远端)直接转发；下行(远端->应用)经 [QosPipeline] 做
 * 丢包/延迟/限速后再写回 tun —— 弱网效果作用于应用收到数据的方向。
 */
class UdpForwarder(
    private val engine: VpnEngine,
    private val selector: Selector
) : VpnEngine.TimerSource {

    private class Session(
        private val engine: VpnEngine,
        val srcIp: Int, val srcPort: Int,
        val dstIp: Int, val dstPort: Int
    ) {
        val channel: DatagramChannel = DatagramChannel.open()
        var key: SelectionKey? = null
        // 下行流水线：emit = 写回 tun（远端 -> 应用方向做弱网）
        val pipeline = QosPipeline { pkt -> engine.writeToTun(pkt) }
    }

    private val sessions = ConcurrentHashMap<String, Session>()
    private var currentProfile: NetProfile = NetProfile.DISABLED

    private fun key(srcIp: Int, sp: Int, dstIp: Int, dp: Int) =
        "$srcIp:$sp->$dstIp:$dp"

    fun forward(data: ByteArray, header: IpPacket.Header, off: Int) {
        if (header.moreFragments || header.fragmentOffset != 0) return // 分片暂不重组
        if (off + 8 > data.size) return
        val sp = u16(data, off); val dp = u16(data, off + 2)
        val ulen = u16(data, off + 4)
        val payloadStart = off + 8
        val plen = minOf(ulen - 8, data.size - payloadStart)
        if (plen <= 0) return
        val k = key(header.src, sp, header.dst, dp)
        val s = sessions.getOrPut(k) { newSession(header, sp, dp) }
        s.channel.write(ByteBuffer.wrap(data, payloadStart, plen))
    }

    private fun newSession(h: IpPacket.Header, sp: Int, dp: Int): Session {
        val s = Session(engine, h.src, sp, h.dst, dp)
        try {
            s.channel.configureBlocking(false)
            s.channel.connect(InetSocketAddress(IpPacket.ipToStr(h.dst), dp))
            s.pipeline.applyProfile(currentProfile)
            s.key = s.channel.register(selector, SelectionKey.OP_READ, s)
        } catch (_: Exception) { close(s) }
        return s
    }

    fun applyProfile(p: NetProfile) {
        currentProfile = p
        for (s in sessions.values) s.pipeline.applyProfile(p)
    }

    /** reactor 每轮 select 后派发到期下行包。 */
    fun drainDue() { for (s in sessions.values) s.pipeline.tick() }

    /** reactor 读事件：收远端下行 UDP，重组并送入弱网流水线。 */
    fun onSelected(key: SelectionKey, ops: Int) {
        val s = key.attachment() as? Session ?: return
        if ((ops and SelectionKey.OP_READ) != 0) {
            while (true) {
                val buf = ByteBuffer.allocate(65536)
                val n = try { s.channel.receive(buf) } catch (_: Exception) { return }
                if (n == null) break
                buf.flip()
                if (buf.remaining() <= 0) continue
                val payload = ByteArray(buf.remaining())
                buf.get(payload)
                val inner = udpInner(s.dstPort, s.srcPort, payload)
                val pkt = IpBuilder.ip(IpPacket.UDP, s.dstIp, s.srcIp, inner)
                s.pipeline.process(pkt)
            }
        }
    }

    fun closeAll() { for (s in sessions.values) close(s); sessions.clear() }

    private fun close(s: Session) {
        try { s.key?.cancel() } catch (_: Exception) {}
        try { s.channel.close() } catch (_: Exception) {}
    }

    override fun nextDelayMs(): Long {
        var min = Long.MAX_VALUE
        for (s in sessions.values) { val d = s.pipeline.nextDelayMs(); if (d >= 0 && d < min) min = d }
        return if (min == Long.MAX_VALUE) -1L else min
    }

    private fun udpInner(sp: Int, dp: Int, payload: ByteArray): ByteArray {
        val h = ByteArray(8 + payload.size)
        h[0] = (sp ushr 8).toByte(); h[1] = sp.toByte()
        h[2] = (dp ushr 8).toByte(); h[3] = dp.toByte()
        val total = 8 + payload.size
        h[4] = (total ushr 8).toByte(); h[5] = total.toByte()
        // UDP 校验和：载荷放到包尾再一次性算
        System.arraycopy(payload, 0, h, 8, payload.size)
        h[6] = 0; h[7] = 0
        return h
    }

    private fun u16(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xff) shl 8) or (b[o + 1].toInt() and 0xff)
}