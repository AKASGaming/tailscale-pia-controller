package com.tailscalepiacontrol

import android.content.Context

object VpnRemoteSync {
    sealed class RemoteChange {
        data object Disabled : RemoteChange()
        data class Enabled(val region: String?) : RemoteChange()
        data class RegionChanged(val region: String?) : RemoteChange()
    }

    fun updateLastSynced(prefs: Prefs, status: VpnStatusResponse) {
        prefs.lastSyncedVpnEnabled = status.enabled
        prefs.lastSyncedRegion = status.region
    }

    fun detectChange(prefs: Prefs, status: VpnStatusResponse): RemoteChange? {
        val previousEnabled = prefs.lastSyncedVpnEnabled
        if (previousEnabled == null) {
            updateLastSynced(prefs, status)
            return null
        }

        val previousRegion = prefs.lastSyncedRegion
        val change = when {
            previousEnabled && !status.enabled -> RemoteChange.Disabled
            !previousEnabled && status.enabled -> RemoteChange.Enabled(status.region)
            previousEnabled && status.enabled && previousRegion != status.region ->
                RemoteChange.RegionChanged(status.region)
            else -> null
        }
        updateLastSynced(prefs, status)
        return change
    }

    fun reconcileExitNode(context: Context, prefs: Prefs, status: VpnStatusResponse) {
        val shouldUseExitNode = status.enabled &&
            status.stack_status == "running" &&
            !status.exit_node_hostname.isNullOrBlank()

        if (!shouldUseExitNode) {
            if (prefs.lastAppliedExitNode != null && status.stack_status != "starting") {
                TailscaleHelper.setExitNode(context, null, true)
                prefs.lastAppliedExitNode = null
            }
            return
        }

        if (!TailscaleHelper.isConnected(context)) return

        val hostname = status.exit_node_hostname ?: return
        if (prefs.lastAppliedExitNode == hostname) return

        TailscaleHelper.setExitNode(context, hostname, status.allow_lan_access)
        prefs.lastAppliedExitNode = hostname
    }
}
