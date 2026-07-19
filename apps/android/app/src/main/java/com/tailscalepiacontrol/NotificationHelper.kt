package com.tailscalepiacontrol

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

object NotificationHelper {
    const val CHANNEL_MONITOR = "vpn_monitor"
    const val CHANNEL_CHANGES = "vpn_changes"
    const val NOTIFICATION_ID_MONITOR = 1
    const val NOTIFICATION_ID_CHANGE = 2

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)

        val monitorChannel = NotificationChannel(
            CHANNEL_MONITOR,
            context.getString(R.string.notification_channel_monitor),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notification_channel_monitor_desc)
            setShowBadge(false)
        }

        val changesChannel = NotificationChannel(
            CHANNEL_CHANGES,
            context.getString(R.string.notification_channel_changes),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notification_channel_changes_desc)
        }

        manager.createNotificationChannel(monitorChannel)
        manager.createNotificationChannel(changesChannel)
    }

    fun buildMonitorNotification(context: Context): Notification {
        val openAppIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(context, CHANNEL_MONITOR)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.notification_monitor_title))
            .setContentText(context.getString(R.string.notification_monitor_text))
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    fun showChangeNotification(context: Context, change: VpnRemoteSync.RemoteChange) {
        val (title, text) = when (change) {
            is VpnRemoteSync.RemoteChange.Disabled -> {
                context.getString(R.string.notification_vpn_disabled_title) to
                    context.getString(R.string.notification_vpn_disabled_text)
            }
            is VpnRemoteSync.RemoteChange.Enabled -> {
                val regionLabel = formatRegion(change.region)
                context.getString(R.string.notification_vpn_enabled_title) to
                    context.getString(R.string.notification_vpn_enabled_text, regionLabel)
            }
            is VpnRemoteSync.RemoteChange.RegionChanged -> {
                val regionLabel = formatRegion(change.region)
                context.getString(R.string.notification_region_changed_title) to
                    context.getString(R.string.notification_region_changed_text, regionLabel)
            }
        }

        val openAppIntent = PendingIntent.getActivity(
            context,
            change.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_CHANGES)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()

        context.getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID_CHANGE, notification)
    }

    private fun formatRegion(region: String?): String {
        if (region.isNullOrBlank()) return "unknown"
        return region.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
}
