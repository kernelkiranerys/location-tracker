package com.example.locationtracker

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

data class TrackerSettings(
    val serverHost: String = "tracker.example.com",
    val serverPort: Int = 8443,
    val certPinHash: String = "",
    val deviceId: String = "",
    val secretKeyBase64: String = "",
    val privacyUntilMillis: Long = 0L,
)

class TrackerSettingsStore(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val preferences = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun read(): TrackerSettings {
        val defaults = TrackerSettings()
        return TrackerSettings(
            serverHost = preferences.getString(KEY_HOST, defaults.serverHost).orEmpty(),
            serverPort = preferences.getInt(KEY_PORT, defaults.serverPort),
            certPinHash = preferences.getString(KEY_CERT_PIN, defaults.certPinHash).orEmpty(),
            deviceId = preferences.getString(KEY_DEVICE_ID, defaults.deviceId).orEmpty(),
            secretKeyBase64 = preferences.getString(KEY_SECRET_KEY, defaults.secretKeyBase64).orEmpty(),
            privacyUntilMillis = preferences.getLong(KEY_PRIVACY_UNTIL, defaults.privacyUntilMillis),
        )
    }

    fun save(
        serverHost: String,
        serverPort: Int,
        certPinHash: String,
        deviceId: String,
        secretKeyBase64: String,
    ) {
        preferences.edit()
            .putString(KEY_HOST, serverHost.trim())
            .putInt(KEY_PORT, serverPort)
            .putString(KEY_CERT_PIN, certPinHash.trim())
            .putString(KEY_DEVICE_ID, deviceId.trim())
            .putString(KEY_SECRET_KEY, secretKeyBase64.trim())
            .apply()
    }

    fun setPrivacyUntilMillis(untilMillis: Long) {
        preferences.edit()
            .putLong(KEY_PRIVACY_UNTIL, untilMillis)
            .apply()
    }

    fun clearPrivacyUntil() {
        preferences.edit()
            .remove(KEY_PRIVACY_UNTIL)
            .apply()
    }

    fun hasCredentials(): Boolean {
        val settings = read()
        return settings.deviceId.isNotBlank() && settings.secretKeyBase64.isNotBlank()
    }

    companion object {
        private const val PREFS_NAME = "location_tracker_secure"
        private const val KEY_HOST = "server_host"
        private const val KEY_PORT = "server_port"
        private const val KEY_CERT_PIN = "server_cert_pin"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_SECRET_KEY = "secret_key_base64"
        private const val KEY_PRIVACY_UNTIL = "privacy_until_millis"
    }
}