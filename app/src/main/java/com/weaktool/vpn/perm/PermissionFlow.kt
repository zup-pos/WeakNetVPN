package com.weaktool.vpn.perm

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.provider.Settings
import android.text.TextUtils

/**
 * 权限引导集中处理。
 *
 * - Android 6+(23): POST_NOTIFICATIONS 前台通知权限(VpnService 必需可见通知)。
 * - Android 14+(33+) 起的悬浮窗管控变严：悬浮窗控制面板需要 SYSTEM_ALERT_WINDOW，
 *   且在 targetSdk 33+ 需显式引导到系统设置页，无法直接静默授予。
 * - VpnService.prepare() 首次需用户同意建立 VPN 连接。
 */
object PermissionFlow {

    fun hasVpnPermission(context: Context): Boolean {
        return VpnService.prepare(context) == null
    }

    /** 触发 VpnService.prepare 的授权界面。 */
    fun requestVpn(activity: Activity, requestCode: Int) {
        if (!hasVpnPermission(activity)) {
            activity.startActivityForResult(
                VpnService.prepare(activity),
                requestCode
            )
        }
    }

    /** Android 6.0+(API 23) 是否已授予 POST_NOTIFICATIONS(通知)运行时权限。 */
    fun hasNotificationPermission(context: Context): Boolean {
        return Build.VERSION.SDK_INT < 33 ||
            (context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED)
    }

    /** 是否有悬浮窗权限。 */
    fun canDrawOverlays(context: Context): Boolean {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(context)
    }

    /** 引导用户到系统悬浮窗权限设置页。 */
    fun requestOverlay(activity: Activity) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            android.net.Uri.parse("package:" + activity.packageName)
        )
        activity.startActivityForResult(intent, REQ_OVERLAY)
    }

    /** Android 13+ 请求通知运行时权限。 */
    fun requestNotification(activity: Activity) {
        if (Build.VERSION.SDK_INT >= 33 && !hasNotificationPermission(activity)) {
            activity.requestPermissions(
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                REQ_NOTIF
            )
        }
    }

    fun isAppIgnoredBatteryOpt(ctx: Context): Boolean = false // 留待扩展

    const val REQ_VPN = 1001
    const val REQ_OVERLAY = 1002
    const val REQ_NOTIF = 1003
}