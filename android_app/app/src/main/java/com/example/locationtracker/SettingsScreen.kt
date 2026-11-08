package com.example.locationtracker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanIntentResult
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(repository: LocationTrackerRepository, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val initial = repository.settingsStore.read()

    var serverHost by remember { mutableStateOf(initial.serverHost) }
    var serverPort by remember { mutableStateOf(initial.serverPort.toString()) }
    var certPin by remember { mutableStateOf(initial.certPinHash) }
    var deviceId by remember { mutableStateOf(initial.deviceId) }
    var secretKey by remember { mutableStateOf(initial.secretKeyBase64) }
    var statusMessage by remember { mutableStateOf("Ready") }
    var permissionMessage by remember { mutableStateOf(permissionSummary(context)) }
    var networkMessage by remember { mutableStateOf(networkSummary(repository)) }
    var showManualConnect by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        permissionMessage = permissionSummary(context)
        val granted = results.values.all { it }
        val names = results.keys.map { it.substringAfterLast('.') }
        statusMessage = if (granted) "Permissions granted: ${names.joinToString()}" else "Permissions denied or partially granted"
        Toast.makeText(context, statusMessage, Toast.LENGTH_SHORT).show()
    }

    val backgroundLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionMessage = permissionSummary(context)
        statusMessage = if (granted) "Background location granted" else "Background location denied"
        Toast.makeText(context, statusMessage, Toast.LENGTH_SHORT).show()
    }

    val qrLauncher = rememberLauncherForActivityResult(ScanContract()) { result: ScanIntentResult ->
        if (result.contents != null) {
            try {
                val payload = JSONObject(result.contents)
                val host = payload.optString("host", "")
                val port = payload.optString("port", "8443").toIntOrNull() ?: 8443
                val pin = payload.optString("cert_pin", "")
                val did = payload.optString("device_id", "")
                val key = payload.optString("secret_key", "")

                repository.saveSettings(host, port, pin, did, key)
                serverHost = host
                serverPort = port.toString()
                certPin = pin
                deviceId = did
                secretKey = key

                statusMessage = "Connection complete"
                permissionMessage = permissionSummary(context)
                networkMessage = networkSummary(repository)
                Toast.makeText(context, "Connection complete", Toast.LENGTH_SHORT).show()
                Log.d("SettingsScreen", "QR scan complete, settings saved automatically")
            } catch (e: Exception) {
                statusMessage = "Invalid QR code: ${e.message}"
                Toast.makeText(context, "Invalid QR code", Toast.LENGTH_SHORT).show()
                Log.e("SettingsScreen", "QR parse failed", e)
            }
        }
    }

    fun launchQrScanner() {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("Point at the QR code on the registration page")
            setBeepEnabled(false)
            setOrientationLocked(false)
        }
        qrLauncher.launch(options)
    }

    LaunchedEffect(Unit) {
        permissionMessage = permissionSummary(context)
        networkMessage = networkSummary(repository)
        AppLogger.log("SettingsScreen", "Settings screen opened")
        AppLogger.logPermissions(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Primary: QR scan button
            Button(
                onClick = { launchQrScanner() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.QrCodeScanner, contentDescription = "Scan QR")
                Text("  Scan QR to connect")
            }

            // Toggle for manual connect
            TextButton(onClick = { showManualConnect = !showManualConnect }) {
                Icon(Icons.Default.ExpandMore, contentDescription = null)
                Text("  Manual connect")
            }

            // Hidden server fields
            AnimatedVisibility(visible = showManualConnect) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = serverHost,
                        onValueChange = { serverHost = it },
                        label = { Text("Server host") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = serverPort,
                        onValueChange = { serverPort = it.filter { c -> c.isDigit() } },
                        label = { Text("Server port") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = certPin,
                        onValueChange = { certPin = it.trim() },
                        label = { Text("Certificate pin hash (optional)") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = deviceId,
                        onValueChange = { deviceId = it.trim() },
                        label = { Text("Device ID") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = secretKey,
                        onValueChange = { secretKey = it.trim() },
                        label = { Text("Secret key (base64)") },
                        singleLine = true,
                    )
                    Button(onClick = {
                        scope.launch {
                            val portValue = serverPort.toIntOrNull() ?: 8443
                            repository.saveSettings(
                                serverHost = serverHost,
                                serverPort = portValue,
                                certPinHash = certPin,
                                deviceId = deviceId,
                                secretKeyBase64 = secretKey,
                            )
                            statusMessage = "Settings saved"
                            permissionMessage = permissionSummary(context)
                            networkMessage = networkSummary(repository)
                            Toast.makeText(context, "Settings saved", Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Text("Save settings")
                    }
                }
            }

            HorizontalDivider()

            // Permissions
            Text("Permissions", style = MaterialTheme.typography.titleMedium)

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = {
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                        ),
                    )
                }) {
                    Text("Location")
                }

                OutlinedButton(onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    }
                }) {
                    Text("Background location")
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    OutlinedButton(onClick = {
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.BLUETOOTH_SCAN,
                                Manifest.permission.BLUETOOTH_CONNECT,
                            ),
                        )
                    }) {
                        Text("Bluetooth scan")
                    }
                }

                OutlinedButton(onClick = {
                    permissionLauncher.launch(
                        arrayOf(Manifest.permission.READ_PHONE_STATE),
                    )
                }) {
                    Text("Phone state")
                }
            }

            HorizontalDivider()

            // Diagnostics
            Text("Diagnostics", style = MaterialTheme.typography.titleMedium)
            Text(networkMessage, style = MaterialTheme.typography.bodyMedium)
            Text(permissionMessage, style = MaterialTheme.typography.bodyMedium)
            Text(statusMessage, style = MaterialTheme.typography.bodyMedium)

            Button(onClick = {
                scope.launch {
                    AppLogger.log("SettingsScreen", "Send location now pressed")
                    statusMessage = repository.performAndSendLocation("on_demand")
                    networkMessage = networkSummary(repository)
                }
            }) {
                Text("Send location now")
            }

            HorizontalDivider()

            // Export logs
            Text("Logs", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = {
                AppLogger.init(context)
                AppLogger.log("SettingsScreen", "Export logs pressed")
                AppLogger.logPermissions(context)
                val file = AppLogger.exportLogs(context)
                if (file != null) {
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        context, "${context.packageName}.fileprovider", file
                    )
                    val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                        putExtra(android.content.Intent.EXTRA_SUBJECT, "Location Tracker Logs")
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(android.content.Intent.createChooser(shareIntent, "Export Logs"))
                } else {
                    Toast.makeText(context, "Failed to export logs", Toast.LENGTH_SHORT).show()
                }
            }, modifier = Modifier.fillMaxWidth()) {
                Text("Export Logs")
            }
        }
    }
}

internal fun permissionSummary(context: Context): String {
    val fineGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val coarseGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val bgGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
    val btScanGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED
    }
    val phoneStateGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
    return buildString {
        if (fineGranted || coarseGranted) {
            append("Location permission granted")
        } else {
            append("Location permission missing")
        }
        append(" | Background location: ")
        append(if (bgGranted) "granted (all the time)" else "missing — needed for screen-locked updates")
        append(" | Bluetooth scan: ")
        append(if (btScanGranted) "granted" else "missing — needed for nearby device scanning")
        append(" | Phone state: ")
        append(if (phoneStateGranted) "granted" else "missing — needed for cell tower data")
    }
}

internal fun networkSummary(repository: LocationTrackerRepository): String {
    return if (repository.apiClient.isNetworkAvailable()) {
        "Validated network available"
    } else {
        "No validated network"
    }
}
