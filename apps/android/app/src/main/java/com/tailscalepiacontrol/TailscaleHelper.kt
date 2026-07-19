package com.tailscalepiacontrol

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

object TailscaleHelper {
    private const val TAILSCALE_PACKAGE = "com.tailscale.ipn"
    private const val TAILSCALE_MAIN_ACTIVITY = "com.tailscale.ipn.MainActivity"

    fun setExitNode(context: Context, hostname: String?, allowLanAccess: Boolean = true) {
        val intent = Intent("com.tailscale.ipn.USE_EXIT_NODE").apply {
            component = ComponentName(TAILSCALE_PACKAGE, "com.tailscale.ipn.IPNReceiver")
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
        if (!isTailscaleInstalled(context)) {
            context.startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=$TAILSCALE_PACKAGE")
                )
            )
            return
        }

        val explicit = Intent().apply {
            component = ComponentName(TAILSCALE_PACKAGE, TAILSCALE_MAIN_ACTIVITY)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (explicit.resolveActivity(context.packageManager) != null) {
            context.startActivity(explicit)
            return
        }

        val launch = context.packageManager.getLaunchIntentForPackage(TAILSCALE_PACKAGE)
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launch)
            return
        }

        context.startActivity(
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://play.google.com/store/apps/details?id=$TAILSCALE_PACKAGE")
            )
        )
    }

    private fun isTailscaleInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(TAILSCALE_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }
}
