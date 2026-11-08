package com.example.locationtracker

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class NearbyDeviceCollector(private val context: Context) {
    companion object {
        private const val TAG = "NearbyDeviceCollector"
        private const val BLE_SCAN_TIMEOUT_MS = 10_000L
    }

    @SuppressLint("MissingPermission")
    suspend fun getNearbyDevices(): List<NearbyDevice> {
        if (!hasBluetoothScanPermission()) {
            Log.d(TAG, "No Bluetooth scan permission, skipping")
            return emptyList()
        }

        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            ?: return emptyList()
        val adapter = bluetoothManager.adapter ?: run {
            Log.d(TAG, "No Bluetooth adapter")
            return emptyList()
        }

        if (!adapter.isEnabled) {
            Log.d(TAG, "Bluetooth not enabled, skipping BLE scan")
            return emptyList()
        }

        return runBleScan(adapter)
    }

    @SuppressLint("MissingPermission")
    private suspend fun runBleScan(adapter: BluetoothAdapter): List<NearbyDevice> {
        val scanner = adapter.bluetoothLeScanner ?: return emptyList()
        val found = mutableListOf<NearbyDevice>()

        val scanResults = withTimeoutOrNull(BLE_SCAN_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val callback = object : ScanCallback() {
                    override fun onScanResult(callbackType: Int, result: ScanResult) {
                        val device = result.device ?: return
                        val address = device.address
                        if (found.none { it.address == address }) {
                            val record = result.scanRecord
                            val nearby = NearbyDevice(
                                address = address,
                                name = runCatching { device.name }.getOrNull(),
                                rssi = result.rssi,
                                txPower = record?.txPowerLevel ?: Int.MIN_VALUE,
                                deviceType = "BLE",
                                manufacturerData = record?.manufacturerSpecificData?.let { data ->
                                    (0 until data.size()).mapNotNull { i ->
                                        val key = data.keyAt(i)
                                        val value = data.valueAt(i)
                                        if (value != null) {
                                            "0x${key.toString(16)}:${value.joinToString("") { "%02x".format(it) }}"
                                        } else null
                                    }
                                },
                                serviceUuids = record?.serviceUuids?.map { it.uuid.toString() },
                                timestampNanos = result.timestampNanos,
                            )
                            found.add(nearby)
                            Log.d(TAG, "Found device: $address rssi=${result.rssi}")
                        }
                    }

                    override fun onScanFailed(errorCode: Int) {
                        Log.w(TAG, "BLE scan failed (errorCode=$errorCode)")
                        cont.resume(emptyList<ScanResult>())
                    }
                }

                val settings = ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setReportDelay(0L)
                    .build()

                scanner.startScan(emptyList<ScanFilter>(), settings, callback)
                cont.invokeOnCancellation { scanner.stopScan(callback) }
            }
        }

        Log.d(TAG, "BLE scan complete, found ${found.size} devices")
        return found
    }

    private fun hasBluetoothScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            context.checkSelfPermission(android.Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED &&
                context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED
        }
    }
}

data class NearbyDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
    val txPower: Int,
    val deviceType: String,
    val manufacturerData: List<String>?,
    val serviceUuids: List<String>?,
    val timestampNanos: Long,
)
