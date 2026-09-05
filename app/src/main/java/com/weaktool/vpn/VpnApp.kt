package com.weaktool.vpn

import android.app.Application
import com.weaktool.vpn.notification.NotificationHelper

/**
 * Application entry.
 * WeakNetVPN —— 以现代非阻塞 IO 重构的 VpnService 弱网模拟核心引擎。
 * UI/悬浮窗控制面板不在本工程重写范围（复用既有资源）。
 */
class VpnApp : Application() {
    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannels(this)
    }
}
