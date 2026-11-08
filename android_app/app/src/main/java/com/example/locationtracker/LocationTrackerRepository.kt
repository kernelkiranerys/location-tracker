package com.example.locationtracker

import android.content.Context
import android.location.Location
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class LocationTrackerRepository(context: Context) {
    private val appContext = context.applicationContext
    val settingsStore = TrackerSettingsStore(appContext)
    val apiClient = ApiClientManager(appContext, settingsStore)
    private val locationCollector = LocationCollector(appContext)
    private val cellTowerCollector = CellTowerCollector(appContext)
    private val nearbyDeviceCollector = NearbyDeviceCollector(appContext)
    private val pendingPayloadStore = PendingPayloadStore(appContext)

    companion object {
        private var sequenceCounter: Long = 0L
    }

    private val _lastScanSummary = MutableStateFlow("No scan yet")
    val lastScanSummary: StateFlow<String> = _lastScanSummary

    fun hasCredentials(): Boolean = settingsStore.hasCredentials()

    fun isPrivacyActive(): Boolean {
        val privacyUntilMillis = settingsStore.read().privacyUntilMillis
        return privacyUntilMillis > System.currentTimeMillis()
    }

    fun privacySummary(): String {
        val privacyUntilMillis = settingsStore.read().privacyUntilMillis
        return if (privacyUntilMillis > System.currentTimeMillis()) {
            val remainingMinutes = ((privacyUntilMillis - System.currentTimeMillis()) / 60000L).coerceAtLeast(0L)
            "Privacy mode active for about ${remainingMinutes} minute(s). Server requests can still override it."
        } else {
            "Privacy mode inactive"
        }
    }

    fun saveSettings(
        serverHost: String,
        serverPort: Int,
        certPinHash: String,
        deviceId: String,
        secretKeyBase64: String,
    ) {
        settingsStore.save(serverHost, serverPort, certPinHash, deviceId, secretKeyBase64)
        apiClient.resetClient()
    }

    suspend fun requestPrivacyPause(hours: Int): String = withContext(Dispatchers.IO) {
        if (!hasCredentials()) {
            return@withContext "Save device credentials first"
        }

        if (!apiClient.isNetworkAvailable()) {
            return@withContext "No validated network connection"
        }

        if (!apiClient.sendPrivacyRequest(hours)) {
            return@withContext "Server rejected the privacy request"
        }

        settingsStore.setPrivacyUntilMillis(System.currentTimeMillis() + TimeUnit.HOURS.toMillis(hours.toLong()))
        "Privacy pause set for ${hours} hour(s)"
    }

    fun cancelPrivacyPause() {
        settingsStore.clearPrivacyUntil()
    }

    suspend fun performAndSendLocation(triggeredBy: String, includeBleScan: Boolean = true): String = withContext(Dispatchers.IO) {
        AppLogger.log("Repository", "performAndSendLocation triggered=$triggeredBy ble=$includeBleScan")
        if (!hasCredentials()) {
            AppLogger.warn("Repository", "No credentials saved")
            return@withContext "Save device credentials first"
        }

        if (triggeredBy == "scheduled" && isPrivacyActive()) {
            AppLogger.info("Repository", "Privacy mode active, scheduled upload skipped")
            return@withContext "Privacy mode active; scheduled upload skipped"
        }

        if (!locationCollector.hasLocationAccess()) {
            AppLogger.warn("Repository", "Location permission missing")
            return@withContext "Location permission missing"
        }

        if (!locationCollector.isLocationEnabled()) {
            AppLogger.warn("Repository", "Location services disabled")
            return@withContext "Location services are disabled"
        }

        val location = locationCollector.getCurrentLocation()
        val wifiData = locationCollector.getWifiData()
        val cellTowerData = cellTowerCollector.getCellTowerData()
        val nearbyDevices = if (includeBleScan) nearbyDeviceCollector.getNearbyDevices() else emptyList()
        val operatorInfo = cellTowerCollector.getOperatorInfo()

        AppLogger.log("Repository", "Scan results: GPS=${location != null}, WiFi=${wifiData.networks.size}, Cell=${cellTowerData.size}, BLE=${nearbyDevices.size}, operator=${operatorInfo?.operatorName}")

        if (location != null) {
            android.util.Log.d("LocationUpload", "GPS: lat=${location.latitude}, lon=${location.longitude}, acc=${location.accuracy}, alt=${location.altitude}, spd=${location.speed}, bear=${location.bearing}, ts=${location.time}")
        } else {
            android.util.Log.d("LocationUpload", "GPS: no fix, sending WiFi data only (server will geolocate)")
        }
        android.util.Log.d("LocationUpload", "WiFi: ${wifiData.networks.size} networks, connected=${wifiData.connectedSsid}")
        android.util.Log.d("LocationUpload", "Cell towers: ${cellTowerData.size}, operator=${operatorInfo?.operatorName}")
        android.util.Log.d("LocationUpload", "Nearby devices: ${nearbyDevices.size}")

        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        _lastScanSummary.value = buildString {
            appendLine("Last scan: $timestamp")
            appendLine("GPS: ${if (location != null) "%.6f, %.6f (acc %.0fm)".format(location.latitude, location.longitude, location.accuracy) else "no fix"}")
            appendLine("WiFi: ${wifiData.networks.size} networks, connected: ${wifiData.connectedSsid.ifBlank { "none" }}")
            appendLine("Cell towers: ${cellTowerData.size}, operator: ${operatorInfo?.operatorName ?: "unknown"}")
            appendLine("Nearby BLE devices: ${nearbyDevices.size}")
            if (nearbyDevices.isNotEmpty()) {
                appendLine("Strongest: ${nearbyDevices.minByOrNull { it.rssi }?.let { "${it.address} (${it.rssi}dBm)" } ?: ""}")
            }
        }.trimEnd()

        if (location == null && wifiData.networks.isEmpty() && cellTowerData.isEmpty()) {
            AppLogger.warn("Repository", "No location data at all — skipping upload")
            return@withContext "No location data available (GPS unavailable, no WiFi/cell data found)"
        }

        val payload = buildPayload(location, triggeredBy, wifiData, cellTowerData, nearbyDevices, operatorInfo)

        if (!apiClient.isNetworkAvailable()) {
            pendingPayloadStore.savePayload(payload)
            AppLogger.warn("Repository", "No network — payload batched (${pendingPayloadStore.count()} pending)")
            return@withContext "No network — data saved, will send when connection returns (${pendingPayloadStore.count()} pending)"
        }

        if (apiClient.sendLocation(payload)) {
            AppLogger.info("Repository", "Location uploaded successfully")
            flushPendingPayloads()
            "Location uploaded"
        } else {
            pendingPayloadStore.savePayload(payload)
            AppLogger.warn("Repository", "Server rejected — payload batched (${pendingPayloadStore.count()} pending)")
            "Server rejected — data saved for retry (${pendingPayloadStore.count()} pending)"
        }
    }

    fun flushPendingPayloads() {
        if (!apiClient.isNetworkAvailable()) return
        val pending = pendingPayloadStore.readAll()
        if (pending.isEmpty()) return

        android.util.Log.d("LocationUpload", "Flushing ${pending.size} pending payloads")
        val iterator = pending.iterator()
        while (iterator.hasNext()) {
            val payload = iterator.next()
            if (apiClient.sendLocation(payload)) {
                pendingPayloadStore.remove(payload)
                android.util.Log.d("LocationUpload", "Flushed pending payload (${pendingPayloadStore.count()} remaining)")
            } else {
                android.util.Log.w("LocationUpload", "Failed to flush a pending payload, stopping")
                break
            }
        }
    }

    suspend fun flushLogs(): String = withContext(Dispatchers.IO) {
        AppLogger.init(appContext)
        val logs = AppLogger.getPendingLogs()
        if (logs.length() == 0) {
            return@withContext "No logs to upload"
        }
        if (!hasCredentials()) {
            return@withContext "No credentials, skipping log upload"
        }
        if (!apiClient.isNetworkAvailable()) {
            return@withContext "No network, skipping log upload"
        }
        val payload = JSONObject().put("logs", logs).toString()
        AppLogger.debug("Repository", "Uploading ${logs.length()} log entries")
        val success = apiClient.sendLogs(payload)
        if (success) {
            AppLogger.clearUploaded()
            AppLogger.info("Repository", "Uploaded and cleared ${logs.length()} logs")
            "Uploaded ${logs.length()} logs"
        } else {
            AppLogger.warn("Repository", "Log upload failed, will retry")
            "Log upload failed, will retry"
        }
    }

    private fun buildPayload(
        location: Location?,
        triggeredBy: String,
        wifiData: WifiData,
        cellTowerData: List<CellTowerData>,
        nearbyDevices: List<NearbyDevice>,
        operatorInfo: OperatorInfo?,
    ): String {
        sequenceCounter += 1
        return JSONObject().apply {
            put("sequence", sequenceCounter)
            if (location != null) {
                put("latitude", location.latitude)
                put("longitude", location.longitude)
                put("accuracy", location.accuracy.toDouble())
                put("altitude", location.altitude)
                put("speed", location.speed)
                put("bearing", location.bearing)
                put("timestamp", location.time)
            } else {
                put("latitude", JSONObject.NULL)
                put("longitude", JSONObject.NULL)
                put("timestamp", System.currentTimeMillis())
            }
            put("triggered_by", triggeredBy)

            // WiFi networks with war-driving data
            put("wifi_networks", JSONArray().apply {
                wifiData.networks.forEach { network ->
                    put(JSONObject().apply {
                        put("ssid", network.ssid)
                        put("bssid", network.bssid)
                        put("signal_dbm", network.signalDbm)
                        put("frequency_mhz", network.frequencyMhz)
                        put("is_connected", network.isConnected)
                        put("channel", network.channel)
                        put("capabilities", network.capabilities)
                        put("encryption", network.encryption)
                        put("frequency_band", network.frequencyBand)
                    })
                }
            })

            // Cell tower data
            put("cell_towers", JSONArray().apply {
                cellTowerData.forEach { cell ->
                    put(JSONObject().apply {
                        put("radio_technology", cell.radioTechnology)
                        cell.mcc?.let { put("mcc", it) }
                        cell.mnc?.let { put("mnc", it) }
                        cell.cellId?.let { put("cell_id", it) }
                        cell.lac?.let { put("lac", it) }
                        cell.tac?.let { put("tac", it) }
                        cell.pci?.let { put("pci", it) }
                        cell.signalStrength?.let { put("signal_strength", it) }
                        cell.level?.let { put("level", it) }
                        cell.rsrp?.let { put("rsrp", it) }
                        cell.rsrq?.let { put("rsrq", it) }
                        cell.snr?.let { put("snr", it) }
                        cell.ta?.let { put("timing_advance", it) }
                        cell.bands?.let { bands ->
                            put("bands", JSONArray().apply {
                                bands.forEach { put(it) }
                            })
                        }
                        cell.bandwidth?.let { put("bandwidth", it) }
                        cell.towerLatitude?.let { put("tower_latitude", it) }
                        cell.towerLongitude?.let { put("tower_longitude", it) }
                    })
                }
            })

            // Operator info
            operatorInfo?.let { op ->
                put("operator", JSONObject().apply {
                    op.mcc?.let { put("mcc", it) }
                    op.mnc?.let { put("mnc", it) }
                    op.operatorName?.let { put("network_operator_name", it) }
                    op.simOperator?.let { put("sim_operator", it) }
                    op.simOperatorName?.let { put("sim_operator_name", it) }
                    op.simCountryIso?.let { put("sim_country_iso", it) }
                    op.networkCountryIso?.let { put("network_country_iso", it) }
                })
            }

            // Nearby BLE devices
            put("nearby_devices", JSONArray().apply {
                nearbyDevices.forEach { device ->
                    put(JSONObject().apply {
                        put("address", device.address)
                        device.name?.let { put("name", it) }
                        put("rssi", device.rssi)
                        if (device.txPower != Int.MIN_VALUE) put("tx_power", device.txPower)
                        put("device_type", device.deviceType)
                        device.manufacturerData?.let { data ->
                            put("manufacturer_data", JSONArray().apply {
                                data.forEach { put(it) }
                            })
                        }
                        device.serviceUuids?.let { uuids ->
                            put("service_uuids", JSONArray().apply {
                                uuids.forEach { put(it) }
                            })
                        }
                        put("timestamp_nanos", device.timestampNanos)
                    })
                }
            })
        }.toString()
    }

    companion object {
        private const val WORK_NAME = "location_updates"
    }
}
