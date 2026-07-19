package com.tailscalepiacontrol

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.Settings

object TailscaleHelper {
    private const val TAILSCALE_PACKAGE = "com.tailscale.ipn"
    private const val TAILSCALE_MAIN_ACTIVITY = "com.tailscale.ipn.MainActivity"

    fun isInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(TAILSCALE_PACKAGE, PackageManager.GET_ACTIVITIES)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun isConnected(context: Context): Boolean {
        if (!isInstalled(context)) return false

        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networks = connectivity.allNetworks
        for (network in networks) {
            val capabilities = connectivity.getNetworkCapabilities(network) ?: continue
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                return true
            }
        }
        return false
    }

    fun setExitNode(context: Context, hostname: String?, allowLanAccess: Boolean = true) {
        val intent = Intent("com.tailscale.ipn.USE_EXIT_NODE").apply {
            component = ComponentName(TAILSCALE_PACKAGE, "com.tailscale.ipn.IPNReceiver")
            putExtra("exitNode", hostname.orEmpty())
            putExtra("allowLanAccess", allowLanAccess)
        }
        context.sendBroadcast(intent)
    }

    fun openTailscaleApp(context: Context) {
        if (!isInstalled(context)) {
            openPlayStore(context)
            return
        }

        val launchers = context.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(TAILSCALE_PACKAGE),
            PackageManager.MATCH_DEFAULT_ONLY
        )
        if (launchers.isNotEmpty()) {
            val activity = launchers.first().activityInfo
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                component = ComponentName(activity.packageName, activity.name)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
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

        val settingsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", TAILSCALE_PACKAGE, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(settingsIntent)
    }

    private fun openPlayStore(context: Context) {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$TAILSCALE_PACKAGE")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (market.resolveActivity(context.packageManager) != null) {
            context.startActivity(market)
            return
        }
        context.startActivity(
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://play.google.com/store/apps/details?id=$TAILSCALE_PACKAGE")
            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        )
    }
}
