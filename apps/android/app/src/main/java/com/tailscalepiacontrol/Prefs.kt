package com.tailscalepiacontrol

import android.content.Context

class Prefs(context: Context) {
    private val prefs = context.getSharedPreferences("tailscale_pia_control", Context.MODE_PRIVATE)

    var controllerUrl: String?
        get() = prefs.getString("controller_url", null)
        set(value) = prefs.edit().putString("controller_url", value).apply()

    var apiToken: String?
        get() = prefs.getString("api_token", null)
        set(value) = prefs.edit().putString("api_token", value).apply()

    var deviceId: String?
        get() = prefs.getString("device_id", null)
        set(value) = prefs.edit().putString("device_id", value).apply()

    var lastAppliedExitNode: String?
        get() = prefs.getString("last_applied_exit_node", null)
        set(value) = prefs.edit().putString("last_applied_exit_node", value).apply()

    val isRegistered: Boolean
        get() = !apiToken.isNullOrBlank()

    fun clearRegistration() {
        prefs.edit()
            .remove("api_token")
            .remove("device_id")
            .remove("last_applied_exit_node")
            .apply()
    }
}
