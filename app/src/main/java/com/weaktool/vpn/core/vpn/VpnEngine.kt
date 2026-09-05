package com.weaktool.vpn.core.vpn

import android.content.Context
import android.os.ParcelFileDescriptor
import com.weaktool.vpn.config.VpnConfig
import com.weaktool.vpn.qos.NetProfile
import java.io.FileOutputStream
import java.nio.channels.Selector

/**
 * 单线程 Selector Reactor —— 包调度中枢。
 * 出站 socket 注册到唯一 Selector，单线程事件驱动；入包阻塞读，两条路径都不空转。
 */
class VpnEngine(
    private val context: Context,
    private val tun: ParcelFileDescriptor
) {
    companion object {
        const val TUN_MTU = 1400
    }
    private val selector: Selector = Selector.open()
    private val header = IpPacket.Header()

    private var currentProfile: NetProfile = NetProfile.DISABLED

    private lateinit var reader: IpPacketReader

    private val tcp = TcpForwarder(this, selector)
    private val udp = UdpForwarder(this, selector)
    private val icmp = IcmpResponder(this)

    @Volatile
    private var running = false

    @Volatile
    private var tunOut: FileOutputStream? = null

    private val reactorThread = Thread(::reactorLoop, "vpn-reactor")

    /** 供 forwarder 把回包写回 tun（下行：远端 -> 应用）。 */
    fun writeToTun(packet: ByteArray) {
        try {
            tunOut?.write(packet)
        } catch (_: Exception) {
        }
    }

    /** 当前活跃画像快照，供 forwarder 读取以决定是否启用弱网。 */
    fun profile(): NetProfile = currentProfile

    /** Reactor 线程仅在 stop 时关闭全部已注册通道。 */
    fun <T> io(fn: () -> T): T = fn()

    /** 汇聚各 forwarder 的延迟调度源，供 reactor 计算 select 超时。 */
    private val delaySources = arrayOf<TimerSource>(tcp, udp)

    // ---------- 生命周期 ----------

    /** 由 [WeakNetVpnService.startVpn] 调用：拉起读线程 + reactor 线程。 */
    fun start() {
        if (running) return
        currentProfile = VpnConfig.load(context)
        running = true
        try {
            tunOut = FileOutputStream(tun.fileDescriptor)
        } catch (_: Exception) { }
        reader = IpPacketReader(tun) { data -> handlePacket(data) }
        reader.start()
        reactorThread.start()
    }

    /** 由 [WeakNetVpnService.stopVpn] / [onDestroy] 调用：优雅关停。 */
    fun stop() {
        if (!running) return
        running = false
        reader.shutdown()
        selector.wakeup()
        try { reactorThread.join(500) } catch (_: InterruptedException) { }
        io {
            tcp.closeAll()
            udp.closeAll()
        }
        try { selector.close() } catch (_: Exception) { }
    }

    /** 动态应用新弱网画像（供悬浮窗/控制面板实时调整调用）。 */
    fun applyProfile(p: NetProfile) {
        io {
            currentProfile = p
            tcp.applyProfile(p)
            udp.applyProfile(p)
        }
    }

    // ---------- 入包分发（阻塞读线程回调） ----------

    /** 读线程单包回调：解析 IP 头并按协议分发，绝不在此阻塞。 */
    private fun handlePacket(data: ByteArray) {
        val off = IpPacket.parse(data, header)
        when (header.version) {
            IpPacket.IPV4 -> when (header.protocol) {
                IpPacket.ICMP -> icmp.respond(data, header, off)
                IpPacket.TCP -> tcp.forward(data, header, off)
                IpPacket.UDP -> udp.forward(data, header, off)
                else -> { /* 其它协议丢弃 */ }
            }
            IpPacket.IPV6 -> { /* IPv6 暂不接管，直接丢弃本骨架（可扩展透传） */ }
            else -> { }
        }
    }

    // ---------- reactor 主循环（单线程事件驱动，非忙轮询） ----------

    private fun reactorLoop() {
        try {
            while (running) {
                val timeout = computeSelectTimeout()
                selector.select(timeout)
                if (!running) break
                val keys = selector.selectedKeys()
                val it = keys.iterator()
                while (it.hasNext()) {
                    val key = it.next()
                    it.remove()
                    if (!key.isValid) continue
                    dispatchReady(key)
                }
                io { tcp.drainDue(); udp.drainDue() }
            }
        } catch (_: Exception) {
            // selector 被 close 或异常：线程退出
        } finally {
            io {
                tcp.closeAll()
                udp.closeAll()
            }
        }
    }

    /** 依据各 forwarder 的延迟队列算出 select 阻塞时长；无任务则阻塞等待新事件。 */
    private fun computeSelectTimeout(): Long {
        var min = Long.MAX_VALUE
        for (s in delaySources) {
            val d = s.nextDelayMs()
            if (d >= 0 && d < min) min = d
        }
        return if (min == Long.MAX_VALUE) -1L else min
    }

    /** 可读/可写事件交给对应 forwarder 处理。 */
    private fun dispatchReady(key: java.nio.channels.SelectionKey) {
        val ops = key.readyOps()
        if ((ops and (java.nio.channels.SelectionKey.OP_READ or
                java.nio.channels.SelectionKey.OP_WRITE)) != 0) {
            io {
                tcp.onSelected(key, ops)
                udp.onSelected(key, ops)
            }
        }
    }

    /** forwarder 暴露的最小时间调度接口。 */
    interface TimerSource {
        /** 距最近一个延迟任务还有多少 ms；-1 表示无任务（可无限阻塞）。 */
        fun nextDelayMs(): Long
    }
}
