package com.example.locationtracker

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import okhttp3.Request
import org.json.JSONObject
import java.io.File

class UpdateChecker(
    private val activity: Activity,
    private val apiClient: ApiClientManager,
) {
    companion object {
        private const val TAG = "UpdateChecker"
        private const val PREFS_NAME = "update_prefs"
        private const val KEY_LAST_PROMPTED_VERSION = "last_prompted_version"
        private var hasCheckedThisSession = false
    }

    fun checkForUpdate() {
        if (hasCheckedThisSession) {
            Log.d(TAG, "Already checked this session, skipping")
            return
        }
        hasCheckedThisSession = true

        Thread {
            try {
                val baseUrl = apiClient.getBaseUrl()
                val request = Request.Builder()
                    .url("$baseUrl/api/app-version/")
                    .get()
                    .build()

                apiClient.getClient().newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.d(TAG, "Version check failed: HTTP ${response.code}")
                        return@use
                    }

                    val body = response.body?.string() ?: return@use
                    val data = JSONObject(body)
                    val serverVersionCode = data.optInt("version_code", 0)
                    val serverVersionName = data.optString("version_name", "0.0")

                    val currentVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        activity.packageManager.getPackageInfo(activity.packageName, 0).longVersionCode.toInt()
                    } else {
                        @Suppress("DEPRECATION")
                        activity.packageManager.getPackageInfo(activity.packageName, 0).versionCode
                    }

                    Log.d(TAG, "Current: $currentVersionCode, Server: $serverVersionCode")

                    if (serverVersionCode > currentVersionCode) {
                        // Don't re-prompt for the same version we already tried
                        val prefs = activity.getSharedPreferences(PREFS_NAME, Activity.MODE_PRIVATE)
                        val lastPrompted = prefs.getInt(KEY_LAST_PROMPTED_VERSION, 0)
                        if (lastPrompted == serverVersionCode) {
                            Log.d(TAG, "Already prompted for v$serverVersionCode, not re-prompting")
                            return@use
                        }

                        Log.d(TAG, "Update available: $serverVersionName ($serverVersionCode)")
                        prefs.edit().putInt(KEY_LAST_PROMPTED_VERSION, serverVersionCode).apply()

                        activity.runOnUiThread {
                            Toast.makeText(activity, "Update available: v$serverVersionName — downloading...", Toast.LENGTH_LONG).show()
                        }
                        downloadAndInstall(serverVersionName)
                    } else {
                        Log.d(TAG, "App is up to date")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Update check failed", e)
            }
        }.start()
    }

    private fun downloadAndInstall(versionName: String) {
        try {
            val baseUrl = apiClient.getBaseUrl()
            val request = Request.Builder()
                .url("$baseUrl/apk/latest.apk")
                .get()
                .build()

            apiClient.getClient().newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "APK download failed: HTTP ${response.code}")
                    return
                }

                val apkFile = File(activity.cacheDir, "update.apk")
                response.body?.byteStream()?.use { input ->
                    apkFile.outputStream().use { output -> input.copyTo(output) }
                }

                Log.d(TAG, "APK downloaded: ${apkFile.length()} bytes")

                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apkFile)
                } else {
                    Uri.fromFile(apkFile)
                }

                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                }

                activity.runOnUiThread {
                    Toast.makeText(activity, "Installing v$versionName...", Toast.LENGTH_SHORT).show()
                    activity.startActivity(intent)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download/install failed", e)
            activity.runOnUiThread {
                Toast.makeText(activity, "Update download failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
