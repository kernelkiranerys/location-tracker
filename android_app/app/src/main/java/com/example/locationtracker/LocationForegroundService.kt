package com.example.locationtracker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class LocationForegroundService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        AppLogger.init(this)
        AppLogger.log("FgService", "Service created")
        Log.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val triggeredBy = intent?.getStringExtra(EXTRA_TRIGGERED_BY) ?: "scheduled"
        AppLogger.log("FgService", "onStartCommand triggeredBy=$triggeredBy oneShot=${intent?.getBooleanExtra(EXTRA_ONE_SHOT, false)}")
        Log.d(TAG, "onStartCommand triggeredBy=$triggeredBy")

        val notification = buildNotification("Location tracking active")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        if (intent?.getBooleanExtra(EXTRA_ONE_SHOT, false) == true) {
            serviceScope.launch {
                val repository = LocationTrackerRepository(applicationContext)
                val result = repository.performAndSendLocation(triggeredBy)
                Log.d(TAG, "One-shot result: $result")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        } else {
            serviceScope.launch {
                runTrackingLoop()
            }
        }

        return START_STICKY
    }

    private suspend fun runTrackingLoop() {
        val repository = LocationTrackerRepository(applicationContext)
        Log.d(TAG, "Tracking loop started (interval ${INTERVAL_MS}ms, BLE every ${BLE_SCAN_EVERY_N_CYCLES} cycles)")

        var cycle = 0
        while (true) {
            val includeBle = cycle % BLE_SCAN_EVERY_N_CYCLES == 0
            Log.d(TAG, "Cycle $cycle (BLE scan: $includeBle)")
            try {
                // Flush any pending payloads from offline periods
                if (repository.apiClient.isNetworkAvailable()) {
                    repository.flushPendingPayloads()
                }

                if (!repository.apiClient.isNetworkAvailable()) {
                    Log.d(TAG, "No network, skipping cycle")
                } else if (!repository.hasCredentials()) {
                    Log.d(TAG, "No credentials, skipping cycle")
                } else {
                    val pendingRequest = repository.apiClient.isLocationRequestPending()
                    val privacyActive = repository.isPrivacyActive()
                    Log.d(TAG, "pending=$pendingRequest privacy=$privacyActive")

                    val shouldSend = pendingRequest || !privacyActive
                    if (shouldSend) {
                        val triggeredBy = when {
                            pendingRequest && privacyActive -> "privacy_override"
                            pendingRequest -> "on_demand"
                            else -> "scheduled"
                        }
                        val result = repository.performAndSendLocation(triggeredBy, includeBle)
                        Log.d(TAG, "Sent location ($triggeredBy): $result")
                    } else {
                        Log.d(TAG, "Privacy active + no pending request, skipping")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in tracking cycle", e)
            }

            try {
                val logResult = repository.flushLogs()
                Log.d(TAG, "Log flush: $logResult")
            } catch (e: Exception) {
                Log.w(TAG, "Log flush failed", e)
            }

            cycle++
            delay(INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Location tracking",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Persistent notification for background location tracking"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Location Tracker")
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val TAG = "LocationService"
        private const val CHANNEL_ID = "location_tracking"
        private const val INTERVAL_MS = 10L * 60 * 1000
        private const val BLE_SCAN_EVERY_N_CYCLES = 6
        const val NOTIFICATION_ID = 1001
        const val EXTRA_TRIGGERED_BY = "triggered_by"
        const val EXTRA_ONE_SHOT = "one_shot"

        fun start(context: Context) {
            val intent = Intent(context, LocationForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
