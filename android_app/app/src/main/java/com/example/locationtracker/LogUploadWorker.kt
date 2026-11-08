package com.example.locationtracker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class LogUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repository = LocationTrackerRepository(applicationContext)
        val result = repository.flushLogs()
        AppLogger.debug("LogUploadWorker", "flushLogs result: $result")
        return when {
            result.startsWith("No network") || result.startsWith("Log upload failed") -> Result.retry()
            else -> Result.success()
        }
    }
}
