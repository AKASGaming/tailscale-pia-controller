package com.tailscalepiacontrol

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

object NotificationHelper {
    const val CHANNEL_CHANGES = "vpn_changes"
    private const val CHANNEL_FOREGROUND = "vpn_foreground"
    const val NOTIFICATION_ID = 1

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)

        val changesChannel = NotificationChannel(
            CHANNEL_CHANGES,
            context.getString(R.string.notification_channel_changes),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notification_channel_changes_desc)
        }
        manager.createNotificationChannel(changesChannel)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val silentChannel = NotificationChannel(
                CHANNEL_FOREGROUND,
                context.getString(R.string.notification_channel_foreground),
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = context.getString(R.string.notification_channel_foreground_desc)
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
            }
            manager.createNotificationChannel(silentChannel)
        }

        // Clean up the old second notification slot from earlier builds.
        manager.cancel(2)
    }

    fun buildSilentForegroundNotification(context: Context): Notification {
        return NotificationCompat.Builder(context, CHANNEL_FOREGROUND)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
            .build()
    }

    fun buildChangeNotification(context: Context, change: VpnRemoteSync.RemoteChange): Notification {
        val (title, text) = changeContent(context, change)

        val openAppIntent = PendingIntent.getActivity(
            context,
            change.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(context, CHANNEL_CHANGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    fun showChangeNotification(service: Service, change: VpnRemoteSync.RemoteChange) {
        service.startForeground(NOTIFICATION_ID, buildChangeNotification(service, change))
    }

    private fun changeContent(context: Context, change: VpnRemoteSync.RemoteChange): Pair<String, String> {
        return when (change) {
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
    }

    private fun formatRegion(region: String?): String {
        if (region.isNullOrBlank()) return "unknown"
        return region.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
}
