package com.weaktool.vpn.core.vpn

import android.os.ParcelFileDescriptor
import java.io.FileInputStream

/**
 * tun 阻塞读线程。
 *
 * 关键点：这是**阻塞** read，不是忙轮询。VpnService 的 tun fd 在没有入包时
 * 内核会让 read 挂起，线程让出 CPU，空闲流量下占用接近 0——这正是本工程
 * 与旧实现(自旋/忙等)的根本区别。
 *
 * 一个逐包解析回调到 [VpnEngine]；engine 内部再用 Selector 做非阻塞出站事件，
 * 因此整体模型是"读阻塞 + 出站事件驱动"，两条路径都不空转。
 */
class IpPacketReader(
    private val tun: ParcelFileDescriptor,
    private val onPacket: (ByteArray) -> Unit
) : Thread("vpn-tun-reader") {

    @Volatile
    private var running = true

    override fun run() {
        val buffer = ByteArray(VpnEngine.TUN_MTU)
        try {
            FileInputStream(tun.fileDescriptor).use { input ->
                while (running) {
                    val n = try {
                        input.read(buffer)
                    } catch (e: Exception) {
                        if (running) { /* fd 被关闭或被打断 */ }
                        break
                    }
                    if (n <= 0) {
                        if (running) continue   // 0/被信号打断，继续等下一包
                        break
                    }
                    val packet = ByteArray(n)
                    System.arraycopy(buffer, 0, packet, 0, n)
                    try {
                        onPacket(packet)
                    } catch (e: Throwable) {
                        // 单包处理异常不应拖垮读线程
                    }
                }
            }
        } finally {
            // 线程退出
        }
    }

    fun shutdown() {
        running = false
        try { tun.close() } catch (_: Exception) {}
    }
}