package com.tailscalepiacontrol

import android.content.Context

class Prefs(context: Context) {
    private val prefs = context.getSharedPreferences("tailscale_pia_control", Context.MODE_PRIVATE)

    var controllerUrl: String?
        get() = prefs.getString("controller_url", null)
        set(value) = prefs.edit().putString("controller_url", value).apply()

    var deviceName: String?
        get() = prefs.getString("device_name", null)
        set(value) = prefs.edit().putString("device_name", value).apply()

    var apiToken: String?
        get() = prefs.getString("api_token", null)
        set(value) = prefs.edit().putString("api_token", value).apply()

    var deviceId: String?
        get() = prefs.getString("device_id", null)
        set(value) = prefs.edit().putString("device_id", value).apply()

    var lastAppliedExitNode: String?
        get() = prefs.getString("last_applied_exit_node", null)
        set(value) = prefs.edit().putString("last_applied_exit_node", value).apply()

    var lastSyncedVpnEnabled: Boolean?
        get() = if (prefs.contains("last_synced_vpn_enabled")) {
            prefs.getBoolean("last_synced_vpn_enabled", false)
        } else {
            null
        }
        set(value) {
            val editor = prefs.edit()
            if (value == null) {
                editor.remove("last_synced_vpn_enabled")
            } else {
                editor.putBoolean("last_synced_vpn_enabled", value)
            }
            editor.apply()
        }

    var lastSyncedRegion: String?
        get() = if (prefs.contains("last_synced_region")) {
            prefs.getString("last_synced_region", null)
        } else {
            null
        }
        set(value) {
            val editor = prefs.edit()
            if (value == null) {
                editor.remove("last_synced_region")
            } else {
                editor.putString("last_synced_region", value)
            }
            editor.apply()
        }

    val isRegistered: Boolean
        get() = !apiToken.isNullOrBlank()

    fun clearRegistration() {
        prefs.edit()
            .remove("api_token")
            .remove("device_id")
            .remove("last_applied_exit_node")
            .remove("last_synced_vpn_enabled")
            .remove("last_synced_region")
            .apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }
}
