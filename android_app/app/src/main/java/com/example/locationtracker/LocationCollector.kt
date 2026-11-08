package com.example.locationtracker

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class LocationCollector(private val context: Context) {
    companion object {
        private const val GPS_TIMEOUT_MS = 30_000L
        private const val WIFI_SCAN_TIMEOUT_MS = 15_000L
        private const val TAG = "LocationCollector"
    }

    fun hasLocationAccess(): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    fun isLocationEnabled(): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            locationManager.isLocationEnabled
        } else {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocation(): Location? {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val networkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

        Log.d(TAG, "gpsEnabled=$gpsEnabled networkEnabled=$networkEnabled")

        if (!gpsEnabled && !networkEnabled) {
            Log.d(TAG, "No providers enabled, trying last known location")
            return getLastKnownLocation(locationManager)
        }

        val provider = if (networkEnabled) LocationManager.NETWORK_PROVIDER else LocationManager.GPS_PROVIDER
        Log.d(TAG, "Using provider: $provider (timeout ${GPS_TIMEOUT_MS}ms)")

        val freshFix = withTimeoutOrNull(GPS_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        Log.d(TAG, "Got fix from $provider: ${location.latitude},${location.longitude}")
                        locationManager.removeUpdates(this)
                        cont.resume(location)
                    }

                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {
                        locationManager.removeUpdates(this)
                        cont.resume(null)
                    }

                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                }

                locationManager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
                cont.invokeOnCancellation { locationManager.removeUpdates(listener) }
            }
        }

        if (freshFix != null) {
            Log.d(TAG, "Fresh fix obtained")
            AppLogger.info("LocationCollector", "GPS fix obtained: ${freshFix.latitude},${freshFix.longitude} acc=${freshFix.accuracy}m")
            return freshFix
        }

        Log.d(TAG, "Fresh fix timed out, falling back to last known location")
        val lastKnown = getLastKnownLocation(locationManager)
        Log.d(TAG, "Last known location: ${lastKnown?.let { "${it.latitude},${it.longitude} age=${(System.currentTimeMillis() - it.time) / 1000}s" } ?: "null"}")
        AppLogger.warn("LocationCollector", "GPS fix timed out, using last known: ${lastKnown?.let { "${it.latitude},${it.longitude} age=${(System.currentTimeMillis() - it.time) / 1000}s" } ?: "null"}")
        return lastKnown
    }

    @SuppressLint("MissingPermission")
    private fun getLastKnownLocation(locationManager: LocationManager): Location? {
        return runCatching {
            locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        }.getOrNull()
    }

    @SuppressLint("MissingPermission")
    suspend fun getWifiData(): WifiData {
        val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (!hasLocationAccess() || !isLocationEnabled()) {
            AppLogger.warn("LocationCollector", "WiFi scan skipped: location access=${hasLocationAccess()}, location enabled=${isLocationEnabled()}")
            return WifiData(connectedSsid = "", networks = emptyList())
        }

        AppLogger.debug("LocationCollector", "Starting WiFi scan...")
        // Trigger a fresh scan and wait for results (cached scanResults are
        // often empty or stale when the screen is off)
        val scanResults = withTimeoutOrNull(WIFI_SCAN_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val callback = object : WifiManager.ScanResultsCallback() {
                    override fun onScanResultsAvailable() {
                        wifiManager.unregisterScanResultsCallback(this)
                        cont.resume(wifiManager.scanResults)
                    }
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    wifiManager.registerScanResultsCallback(Runnable::run, callback)
                    wifiManager.startScan()  // explicitly trigger a fresh scan
                } else {
                    @Suppress("DEPRECATION")
                    wifiManager.startScan()
                    cont.resume(wifiManager.scanResults)
                }
                cont.invokeOnCancellation {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                        wifiManager.unregisterScanResultsCallback(callback)
                    }
                }
            }
        } ?: run {
            Log.w(TAG, "WiFi scan timed out, returning empty network list (not using stale cached scanResults)")
            AppLogger.warn("LocationCollector", "WiFi scan timed out after ${WIFI_SCAN_TIMEOUT_MS}ms, returning empty network list (cached scanResults deliberately ignored to avoid stale-location reports)")
            emptyList<ScanResult>()
        }

        val connectionInfo = runCatching { wifiManager.connectionInfo }.getOrNull()
        val networks = scanResults.map { result ->
            val capabilities = result.capabilities ?: ""
            val encryption = parseEncryption(capabilities)
            WifiNetwork(
                ssid = result.SSID,
                bssid = result.BSSID,
                signalDbm = result.level,
                frequencyMhz = result.frequency,
                channel = frequencyToChannel(result.frequency),
                isConnected = result.BSSID != null && result.BSSID == connectionInfo?.bssid,
                capabilities = capabilities,
                encryption = encryption,
                frequencyBand = if (result.frequency >= 5000) "5GHz" else "2.4GHz",
            )
        }
        AppLogger.info("LocationCollector", "WiFi scan complete: ${networks.size} APs found, connected=${connectionInfo?.ssid ?: "none"}")
        return WifiData(connectedSsid = connectionInfo?.ssid?.removeSurrounding("\"") ?: "", networks = networks)
    }

    private fun frequencyToChannel(freq: Int): Int = when {
        freq in 2412..2484 -> (freq - 2412) / 5 + 1
        freq in 5160..5885 -> (freq - 5180) / 5 + 36
        else -> 0
    }

    private fun parseEncryption(capabilities: String): String {
        val caps = capabilities.uppercase()
        return when {
            caps.contains("WPA3") -> "WPA3"
            caps.contains("WPA2") -> "WPA2"
            caps.contains("WPA") -> "WPA"
            caps.contains("WEP") -> "WEP"
            caps.contains("ESS") && caps.isNotBlank() -> "OPEN"
            else -> "UNKNOWN"
        }
    }
}

data class WifiData(val connectedSsid: String, val networks: List<WifiNetwork>)
data class WifiNetwork(
    val ssid: String,
    val bssid: String,
    val signalDbm: Int,
    val frequencyMhz: Int,
    val channel: Int,
    val isConnected: Boolean,
    val capabilities: String = "",
    val encryption: String = "UNKNOWN",
    val frequencyBand: String = "",
)
