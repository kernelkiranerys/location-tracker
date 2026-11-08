package com.example.locationtracker

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class PendingPayloadStore(context: Context) {
    private val storeFile = File(context.filesDir, "pending_payloads.json")
    private val tag = "PendingPayloadStore"

    fun savePayload(payload: String) {
        val payloads = readAll()
        payloads.add(payload)
        writeAll(payloads)
        Log.d(tag, "Saved pending payload (total: ${payloads.size})")
    }

    fun readAll(): MutableList<String> {
        if (!storeFile.exists()) return mutableListOf()
        return runCatching {
            val array = JSONArray(storeFile.readText())
            (0 until array.length()).map { array.getString(it) }.toMutableList()
        }.getOrElse {
            Log.w(tag, "Failed to read pending payloads", it)
            mutableListOf()
        }
    }

    fun remove(payload: String) {
        val payloads = readAll()
        payloads.remove(payload)
        writeAll(payloads)
        Log.d(tag, "Removed pending payload (remaining: ${payloads.size})")
    }

    fun clear() {
        storeFile.delete()
        Log.d(tag, "Cleared all pending payloads")
    }

    fun count(): Int = readAll().size

    private fun writeAll(payloads: List<String>) {
        val array = JSONArray()
        payloads.forEach { array.put(it) }
        storeFile.writeText(array.toString())
    }
}
