package com.example.locationtracker

import android.content.Context
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

/**
 * Centralised app logger with:
 * - In-memory ring buffer (max 2000 entries)
 * - Persistent file storage (48h retention, cleaned on write)
 * - Export to shareable file
 * - Batch upload to server via ApiClientManager
 *
 * Usage: AppLogger.log("TAG", "message") or AppLogger.info/debug/warn/error
 */
object AppLogger {
    private const val TAG = "AppLogger"
    private const val MAX_ENTRIES = 2000
    private const val RETENTION_HOURS = 48L
    private const val LOG_FILE = "app_logs.jsonl"

    private val entries = ConcurrentLinkedQueue<LogEntry>()
    private var logFile: File? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
    private var initialised = false

    fun init(context: Context) {
        if (initialised) return
        logFile = File(context.filesDir, LOG_FILE)
        loadFromDisk()
        // Clean old entries on init
        pruneOld()
        initialised = true
        log("AppLogger", "Logger initialised, ${entries.size} entries loaded")
    }

    fun log(tag: String, message: String, level: String = "INFO") {
        val entry = LogEntry(
            timestamp = dateFormat.format(Date()),
            level = level,
            tag = tag,
            message = message,
        )
        entries.add(entry)
        // Also log to logcat for live debugging
        when (level) {
            "ERROR" -> Log.e(tag, message)
            "WARN" -> Log.w(tag, message)
            "DEBUG" -> Log.d(tag, message)
            else -> Log.i(tag, message)
        }
        // Trim in-memory buffer
        while (entries.size > MAX_ENTRIES) {
            entries.poll()
        }
        // Append to file
        appendToFile(entry)
    }

    fun info(tag: String, message: String) = log(tag, message, "INFO")
    fun debug(tag: String, message: String) = log(tag, message, "DEBUG")
    fun warn(tag: String, message: String) = log(tag, message, "WARN")
    fun error(tag: String, message: String) = log(tag, message, "ERROR")

    /** Log permission status for all relevant permissions. */
    fun logPermissions(context: Context) {
        val perms = mapOf(
            "ACCESS_FINE_LOCATION" to android.Manifest.permission.ACCESS_FINE_LOCATION,
            "ACCESS_COARSE_LOCATION" to android.Manifest.permission.ACCESS_COARSE_LOCATION,
            "ACCESS_BACKGROUND_LOCATION" to android.Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            "ACCESS_WIFI_STATE" to android.Manifest.permission.ACCESS_WIFI_STATE,
            "ACCESS_NETWORK_STATE" to android.Manifest.permission.ACCESS_NETWORK_STATE,
            "INTERNET" to android.Manifest.permission.INTERNET,
            "RECEIVE_BOOT_COMPLETED" to android.Manifest.permission.RECEIVE_BOOT_COMPLETED,
            "FOREGROUND_SERVICE" to android.Manifest.permission.FOREGROUND_SERVICE,
            "FOREGROUND_SERVICE_LOCATION" to android.Manifest.permission.FOREGROUND_SERVICE_LOCATION,
            "POST_NOTIFICATIONS" to android.Manifest.permission.POST_NOTIFICATIONS,
            "BLUETOOTH_SCAN" to android.Manifest.permission.BLUETOOTH_SCAN,
            "BLUETOOTH_CONNECT" to android.Manifest.permission.BLUETOOTH_CONNECT,
            "CAMERA" to android.Manifest.permission.CAMERA,
        )
        val sb = StringBuilder("Permission status:\n")
        for ((name, perm) in perms) {
            val granted = context.checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED
            sb.append("  $name: ${if (granted) "GRANTED" else "DENIED"}\n")
        }
        sb.append("  Android SDK: ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})\n")
        sb.append("  Device: ${Build.MANUFACTURER} ${Build.MODEL}\n")
        sb.append("  App version: ${getAppVersion(context)}")
        log("Permissions", sb.toString(), "INFO")
    }

    /** Get all pending logs as a JSON array for upload. */
    fun getPendingLogs(): JSONArray {
        val arr = JSONArray()
        for (entry in entries) {
            arr.put(JSONObject().apply {
                put("timestamp", entry.timestamp)
                put("level", entry.level)
                put("tag", entry.tag)
                put("message", entry.message)
            })
        }
        return arr
    }

    /** Clear logs after successful upload. */
    fun clearUploaded() {
        entries.clear()
        logFile?.delete()
        log("AppLogger", "Logs cleared after upload")
    }

    /** Export logs to a shareable file and return the file path. */
    fun exportLogs(context: Context): File? {
        val exportFile = File(context.cacheDir, "location_tracker_logs_${System.currentTimeMillis()}.txt")
        try {
            exportFile.bufferedWriter().use { writer ->
                writer.write("=== Location Tracker App Logs ===\n")
                writer.write("Exported: ${dateFormat.format(Date())}\n")
                writer.write("Device: ${Build.MANUFACTURER} ${Build.MODEL}\n")
                writer.write("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n")
                writer.write("App version: ${getAppVersion(context)}\n")
                writer.write("Entries: ${entries.size}\n")
                writer.write("=" * 60 + "\n\n")
                for (entry in entries) {
                    writer.write("${entry.timestamp}  ${entry.level.padEnd(5)}  ${entry.tag.padEnd(20)}  ${entry.message}\n")
                }
            }
            log("AppLogger", "Exported ${entries.size} log entries to ${exportFile.name}")
            return exportFile
        } catch (e: Exception) {
            Log.e(TAG, "Export failed", e)
            return null
        }
    }

    private fun appendToFile(entry: LogEntry) {
        try {
            logFile?.let { f ->
                f.appendText("${entry.timestamp}|${entry.level}|${entry.tag}|${entry.message}\n")
            }
        } catch (e: Exception) {
            // Silent fail — don't let logging crash the app
        }
    }

    private fun loadFromDisk() {
        try {
            val f = logFile ?: return
            if (!f.exists()) return
            f.forEachLine { line ->
                val parts = line.split("|", limit = 4)
                if (parts.size == 4) {
                    entries.add(LogEntry(parts[0], parts[1], parts[2], parts[3]))
                }
            }
            // Trim to max
            while (entries.size > MAX_ENTRIES) entries.poll()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load logs from disk", e)
        }
    }

    private fun pruneOld() {
        val cutoff = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(RETENTION_HOURS)
        val cutoffDate = dateFormat.format(Date(cutoff))
        val iter = entries.iterator()
        var removed = 0
        while (iter.hasNext()) {
            val entry = iter.next()
            if (entry.timestamp < cutoffDate) {
                iter.remove()
                removed++
            } else {
                break  // entries are roughly chronological
            }
        }
        if (removed > 0) {
            Log.d(TAG, "Pruned $removed entries older than 48h")
        }
    }

    private fun getAppVersion(context: Context): String {
        return try {
            val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
            "${pkg.versionName} (${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pkg.longVersionCode else pkg.versionCode})"
        } catch (e: Exception) {
            "unknown"
        }
    }

    private data class LogEntry(
        val timestamp: String,
        val level: String,
        val tag: String,
        val message: String,
    )
}
