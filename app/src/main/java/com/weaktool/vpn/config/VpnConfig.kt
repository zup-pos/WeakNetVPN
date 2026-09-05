package com.weaktool.vpn.config

import android.content.Context
import com.weaktool.vpn.qos.NetProfile

/**
 * VPN 运行配置：当前 QoS 画像 + 是否已启动。
 * 用 SharedPreferences 持久化，供 MainActivity / 悬浮窗控制面板读写。
 */
object VpnConfig {

    private const val PREFS = "weaknet_vpn_cfg"
    private const val K_ACTIVE = "active"
    private const val K_BAND = "bandwidth_kbps"
    private const val K_DELAY = "delay_ms"
    private const val K_JITTER = "jitter_ms"
    private const val K_LOSS = "loss_percent"

    fun load(context: Context): NetProfile {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return NetProfile(
            bandwidthKbps = sp.getInt(K_BAND, 0),
            delayMs = sp.getInt(K_DELAY, 0),
            jitterMs = sp.getInt(K_JITTER, 0),
            lossPercent = sp.getInt(K_LOSS, 0)
        )
    }

    fun isActive(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(K_ACTIVE, false)

    fun save(context: Context, profile: NetProfile, active: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(K_ACTIVE, active)
            .putInt(K_BAND, profile.bandwidthKbps)
            .putInt(K_DELAY, profile.delayMs)
            .putInt(K_JITTER, profile.jitterMs)
            .putInt(K_LOSS, profile.lossPercent)
            .apply()
    }
}