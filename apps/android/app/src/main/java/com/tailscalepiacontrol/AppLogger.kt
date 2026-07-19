package com.tailscalepiacontrol

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLogger {
    private const val MAX_LINES = 2500
    private val lines = ArrayDeque<String>(MAX_LINES)
    private val lock = Any()
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun info(tag: String, message: String) = append("I", tag, message)

    fun warn(tag: String, message: String) = append("W", tag, message)

    fun error(tag: String, message: String, throwable: Throwable? = null) {
        val details = if (throwable == null) {
            message
        } else {
            "$message (${throwable.javaClass.simpleName}: ${throwable.message})"
        }
        append("E", tag, details)
    }

    fun snapshot(): String {
        synchronized(lock) {
            return lines.joinToString("\n")
        }
    }

    fun buildReport(context: Context): String {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val versionName = packageInfo.versionName ?: "unknown"
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }

        return buildString {
            appendLine("PIA Control diagnostic log")
            appendLine("Generated: ${timestampFormat.format(Date())}")
            appendLine("App version: $versionName ($versionCode)")
            appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("---")
            append(snapshot())
            appendLine()
        }
    }

    fun exportToCacheFile(context: Context): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(context.cacheDir, "pia-control-logs-$stamp.txt")
        file.writeText(buildReport(context))
        return file
    }

    private fun append(level: String, tag: String, message: String) {
        val line = "${timestampFormat.format(Date())} $level/$tag: $message"
        synchronized(lock) {
            if (lines.size >= MAX_LINES) {
                lines.removeFirst()
            }
            lines.addLast(line)
        }
    }
}
