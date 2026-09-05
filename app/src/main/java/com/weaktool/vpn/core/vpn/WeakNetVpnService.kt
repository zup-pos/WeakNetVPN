package com.weaktool.vpn.core.vpn

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import com.weaktool.vpn.notification.NotificationHelper

/**
 * 前台 VpnService —— 弱网模拟引擎的宿主。
 *
 * - 以 blocking 方式读取 tun fd（无包时内核阻塞、零忙等）。
 * - Android 8+ 必须进入前台并持有可见通知，否则进程会被系统回收；
 *   Android 14+ 要求启动时带前台服务类型，VpnService 使用
 *   `FOREGROUND_SERVICE_TYPE_SPECIAL_USE`（对应 Manifest
 *   foregroundServiceType="specialUse"），本服务在 API 29+ 显式传入类型。
 *
 * 本服务本身只做"建隧道 + 拉起读线程 + 生命周期管理"，
 * 具体包调度/QoS 由 [VpnEngine]（单线程 Selector Reactor）承担。
 */
class WeakNetVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.weaktool.vpn.action.START"
        const val ACTION_STOP = "com.weaktool.vpn.action.STOP"

        /** 隧道虚拟网段地址（应用侧把流量路由进来）。 */
        const val TUN_IP = "10.66.0.1"
        const val TUN_PREFIX = 32
        // TUN_MTU 移入 VpnEngine.TUN_MTU
    }

    private var engine: VpnEngine? = null

    override fun onBind(intent: Intent?): IBinder? {
        // VpnService 需要系统绑定。应用侧通过 startService + prepared 建立连接。
        return super.onBind(intent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopVpn(); return START_NOT_STICKY }
            else -> startVpn()
        }
        return START_STICKY
    }

    private fun startVpn() {
        if (engine != null) return   // 已在运行
        if (VpnService.prepare(this) != null) {
            stopSelf()
            return
        }

        NotificationHelper.ensureChannels(this)
        goForeground()

        val builder = Builder()
            .addAddress(TUN_IP, TUN_PREFIX)
            .addRoute("0.0.0.0", 0)          // 接管全部出站流量
            .setMtu(VpnEngine.TUN_MTU)
            .setSession("WeakNet")

        val tun = try {
            builder.establish()
        } catch (e: Exception) {
            stopSelf()
            return
        }
        if (tun == null) { stopSelf(); return }

        val eng = VpnEngine(this, tun)
        eng.start()
        engine = eng
    }

    private fun stopVpn() {
        engine?.stop()
        engine = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun goForeground() {
        val notif: Notification = NotificationHelper.vpnNotification(this)
        when {
            // Android 14+: VpnService uses specialUse FGS type (Manifest-declared)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                startForeground(NotificationHelper.NOTIF_ID, notif,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            // Android 10-13: use MANIFEST (-1) to defer to Manifest declaration
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                startForeground(NotificationHelper.NOTIF_ID, notif,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST)
            // Android 9 and below: no type parameter
            else -> startForeground(NotificationHelper.NOTIF_ID, notif)
        }
    }

    override fun onDestroy() {
        engine?.stop()
        engine = null
        super.onDestroy()
    }
}
