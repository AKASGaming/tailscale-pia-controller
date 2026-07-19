package com.tailscalepiacontrol

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri

object TailscaleHelper {
    fun setExitNode(context: Context, hostname: String?, allowLanAccess: Boolean = true) {
        val intent = Intent("com.tailscale.ipn.USE_EXIT_NODE").apply {
            component = ComponentName("com.tailscale.ipn", "com.tailscale.ipn.IPNReceiver")
            if (!hostname.isNullOrBlank()) {
                putExtra("exitNode", hostname)
            } else {
                putExtra("exitNode", "")
            }
            putExtra("allowLanAccess", allowLanAccess)
        }
        context.sendBroadcast(intent)
    }

    fun openTailscaleApp(context: Context) {
        val launch = context.packageManager.getLaunchIntentForPackage("com.tailscale.ipn")
        if (launch != null) {
            context.startActivity(launch)
            return
        }
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.tailscale.ipn")))
    }
}
