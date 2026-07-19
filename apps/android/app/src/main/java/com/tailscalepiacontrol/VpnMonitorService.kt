package com.tailscalepiacontrol

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class VpnMonitorService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null
    private lateinit var prefs: Prefs

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        NotificationHelper.createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!prefs.isRegistered) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(
            NotificationHelper.NOTIFICATION_ID_FOREGROUND,
            NotificationHelper.buildSilentForegroundNotification(this),
        )
        startPolling()
        return START_STICKY
    }

    override fun onDestroy() {
        pollJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startPolling() {
        if (pollJob?.isActive == true) return

        pollJob = serviceScope.launch {
            while (isActive) {
                if (!prefs.isRegistered) {
                    stopSelf()
                    break
                }

                pollOnce()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun pollOnce() {
        val baseUrl = prefs.controllerUrl ?: return
        val token = prefs.apiToken ?: return

        try {
            val status = ControllerClient(baseUrl, token).getVpnStatus()
            val change = VpnRemoteSync.detectChange(prefs, status)
            VpnRemoteSync.reconcileExitNode(this@VpnMonitorService, prefs, status)

            if (change != null && !AppVisibility.isInForeground) {
                NotificationHelper.showChangeNotification(this@VpnMonitorService, change)
            }
        } catch (error: Exception) {
            if (error.message?.contains("HTTP 401") == true) {
                AppLogger.error("VpnMonitorService", "Device removed from controller — stopping monitor", error)
                stopSelf()
            } else {
                AppLogger.error("VpnMonitorService", "Status poll failed", error)
            }
        }
    }

    companion object {
        private const val POLL_INTERVAL_MS = 3_000L

        fun start(context: Context) {
            if (!Prefs(context).isRegistered) return
            val intent = Intent(context, VpnMonitorService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, VpnMonitorService::class.java))
        }
    }
}
