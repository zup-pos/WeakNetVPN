package com.weaktool.vpn.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.weaktool.vpn.R
import com.weaktool.vpn.core.vpn.WeakNetVpnService

/**
 * Notification helper. Android 14+ requires a visible foreground-service(VPN)
 * notification, otherwise the tunnel process is silently killed. This builds
 * the persistent notification and the channel.
 */
object NotificationHelper {

    const val CHANNEL_VPN = "weaknet_vpn"
    const val NOTIF_ID = 1001

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val c = NotificationChannel(
                CHANNEL_VPN, context.getString(R.string.notif_channel_vpn),
                NotificationManager.IMPORTANCE_LOW
            )
            c.description = context.getString(R.string.notif_channel_vpn)
            nm.createNotificationChannel(c)
        }
    }

    fun vpnNotification(context: Context): Notification {
        val stop = PendingIntent.getService(
            context, 0,
            Intent(context, WeakNetVpnService::class.java)
                .setAction(WeakNetVpnService.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val pi = PendingIntent.getActivity(
            context, 0,
            context.packageManager.getLaunchIntentForPackage(context.packageName)!!,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, CHANNEL_VPN)
            .setContentTitle(context.getString(R.string.notif_title_vpn))
            .setContentText(context.getString(R.string.notif_text_vpn))
            .setSmallIcon(R.drawable.ic_stat_vpn)
            .setOngoing(true)
            .setContentIntent(pi)
            .addAction(0, context.getString(R.string.action_stop), stop)
            .build()
    }

    fun notifyVpn(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, vpnNotification(context))
    }
}