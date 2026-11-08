package com.example.locationtracker

import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

private enum class AdminView { LOGIN, MENU, CLIENTS, NEARBY, ADD_CLIENT }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminScreen(repository: LocationTrackerRepository, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var view by remember { mutableStateOf(AdminView.LOGIN) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var loginError by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var clientLocations by remember { mutableStateOf<JSONArray?>(null) }
    var nearbyDevices by remember { mutableStateOf<JSONArray?>(null) }
    var nearbyLoading by remember { mutableStateOf(false) }
    var newDeviceName by remember { mutableStateOf("") }
    var createdDevice by remember { mutableStateOf<JSONObject?>(null) }
    var creatingDevice by remember { mutableStateOf(false) }

    val title = when (view) {
        AdminView.LOGIN -> "Admin"
        AdminView.MENU -> "Admin"
        AdminView.CLIENTS -> "Client Locations"
        AdminView.NEARBY -> "Nearby Devices"
        AdminView.ADD_CLIENT -> "Add Client"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = {
                        AppLogger.log("AdminScreen", "Back button pressed (view=$view)")
                        when (view) {
                            AdminView.LOGIN -> onBack()
                            AdminView.MENU -> view = AdminView.LOGIN
                            AdminView.CLIENTS -> view = AdminView.MENU
                    AdminView.NEARBY -> view = AdminView.MENU
                    AdminView.ADD_CLIENT -> view = AdminView.MENU
                }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        when (view) {
            AdminView.LOGIN -> Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Server Admin Login", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = username, onValueChange = { username = it },
                    label = { Text("Username") }, singleLine = true,
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = password, onValueChange = { password = it },
                    label = { Text("Password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                if (loginError.isNotEmpty()) {
                    Text(loginError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Button(
                    onClick = {
                        loading = true
                        scope.launch {
                            val success = withContext(Dispatchers.IO) {
                                AppLogger.log("AdminScreen", "Admin login attempt: $username")
                                repository.apiClient.adminLogin(username, password)
                            }
                            loading = false
                            if (success) {
                                AppLogger.info("AdminScreen", "Admin login successful")
                                view = AdminView.MENU; loginError = ""
                            } else {
                                AppLogger.warn("AdminScreen", "Admin login failed")
                                loginError = "Login failed — check credentials"
                            }
                        }
                    },
                    enabled = !loading && username.isNotBlank() && password.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (loading) "Logging in..." else "Login") }
            }

            AdminView.MENU -> Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Admin Functions", style = MaterialTheme.typography.titleMedium)
                Button(onClick = {
                    scope.launch {
                        loading = true
                        val result = withContext(Dispatchers.IO) {
                            repository.apiClient.adminGetClientLocations()
                        }
                        loading = false
                        if (result != null) {
                            clientLocations = result
                            view = AdminView.CLIENTS
                        } else {
                            Toast.makeText(context, "Failed to fetch locations", Toast.LENGTH_SHORT).show()
                        }
                    }
                }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (loading) "Loading..." else "Locate Clients")
                }
                Button(onClick = {
                    nearbyLoading = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            val locations = repository.apiClient.adminGetClientLocations()
                            if (locations == null || locations.length() == 0) return@withContext null
                            var lat: Double? = null
                            var lng: Double? = null
                            val settings = repository.settingsStore.read()
                            for (i in 0 until locations.length()) {
                                val loc = locations.getJSONObject(i)
                                if (loc.optString("device_id") == settings.deviceId) {
                                    lat = loc.optDouble("latitude")
                                    lng = loc.optDouble("longitude")
                                    break
                                }
                            }
                            if (lat == null || lng == null) {
                                val first = locations.getJSONObject(0)
                                lat = first.optDouble("latitude")
                                lng = first.optDouble("longitude")
                            }
                            repository.apiClient.adminGetNearbyDevices(lat, lng, 50.0)
                        }
                        nearbyLoading = false
                        if (result != null) {
                            nearbyDevices = result
                            view = AdminView.NEARBY
                        } else {
                            Toast.makeText(context, "No location data available", Toast.LENGTH_SHORT).show()
                        }
                    }
                }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (nearbyLoading) "Loading..." else "Get Local Devices")
                }
                Button(onClick = {
                    newDeviceName = ""
                    createdDevice = null
                    view = AdminView.ADD_CLIENT
                }, modifier = Modifier.fillMaxWidth()) {
                    Text("Add Client")
                }
                HorizontalDivider()
                OutlinedButton(onClick = { view = AdminView.LOGIN; password = "" }, modifier = Modifier.fillMaxWidth()) {
                    Text("Logout")
                }
            }

            AdminView.CLIENTS -> {
                val locations = clientLocations
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(locations?.length() ?: 0) { i ->
                        val loc = locations!!.getJSONObject(i)
                        val name = loc.optString("name", "Unknown")
                        val lat = loc.optDouble("latitude")
                        val lng = loc.optDouble("longitude")
                        val ts = loc.optString("timestamp", "—")
                        val source = loc.optString("location_source", "—")
                        Card(
                            modifier = Modifier.fillMaxWidth().clickable {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lng?q=$lat,$lng($name)"))
                                context.startActivity(intent)
                            }
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(name, style = MaterialTheme.typography.titleMedium)
                                Text("Lat: %.5f, Lng: %.5f".format(lat, lng), style = MaterialTheme.typography.bodySmall)
                                Text("Source: $source", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("Last seen: $ts", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            AdminView.ADD_CLIENT -> Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            ) {
                val result = createdDevice
                if (result == null) {
                    Text("Add New Client", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = newDeviceName,
                        onValueChange = { newDeviceName = it },
                        label = { Text("Device name") },
                        singleLine = true,
                    )
                    Button(
                        onClick = {
                            creatingDevice = true
                            scope.launch {
                                val res = withContext(Dispatchers.IO) {
                                    repository.apiClient.adminCreateDevice(newDeviceName.trim())
                                }
                                creatingDevice = false
                                if (res != null) {
                                    createdDevice = res
                                } else {
                                    Toast.makeText(context, "Failed to create device", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        enabled = !creatingDevice && newDeviceName.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (creatingDevice) "Creating..." else "Create Device") }
                } else {
                    Text("Device Created", style = MaterialTheme.typography.titleMedium)
                    Text("Name: ${result.optString("name")}", style = MaterialTheme.typography.bodyLarge)
                    Text("Device ID:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(result.optString("device_id"), style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    Text("Secret Key:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(result.optString("secret_key"), style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    val qrPayload = result.optString("qr_payload")
                    val qrBitmap = remember(qrPayload) {
                        try {
                            val writer = com.google.zxing.qrcode.QRCodeWriter()
                            val bitMatrix = writer.encode(qrPayload, com.google.zxing.BarcodeFormat.QR_CODE, 600, 600)
                            val bmp = android.graphics.Bitmap.createBitmap(600, 600, android.graphics.Bitmap.Config.RGB_565)
                            for (x in 0 until 600) {
                                for (y in 0 until 600) {
                                    bmp.setPixel(x, y, if (bitMatrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                                }
                            }
                            bmp
                        } catch (e: Exception) {
                            Log.w("AddClient", "QR generation failed", e)
                            null
                        }
                    }
                    qrBitmap?.let { bmp ->
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "QR code for device provisioning",
                            modifier = Modifier.size(240.dp),
                        )
                    }
                    Text("Scan this QR code on the new device's Settings screen", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = {
                        view = AdminView.MENU
                        createdDevice = null
                        newDeviceName = ""
                    }, modifier = Modifier.fillMaxWidth()) { Text("Done") }
                }
            }
        }
    }
}

            AdminView.NEARBY -> {
                val devices = nearbyDevices
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(devices?.length() ?: 0) { i ->
                        val dev = devices!!.getJSONObject(i)
                        NearbyDeviceCard(dev, repository, scope, context)
                    }
                }
            }
        }
    }
}

@Composable
private fun NearbyDeviceCard(
    dev: JSONObject,
    repository: LocationTrackerRepository,
    scope: kotlinx.coroutines.CoroutineScope,
    context: android.content.Context,
) {
    var expanded by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf<JSONArray?>(null) }
    var historyLoading by remember { mutableStateOf(false) }

    val type = dev.optString("type", "unknown")
    val typeColor = when (type) {
        "wifi" -> Color(0xFF4ADE80)
        "cell" -> Color(0xFFEF4444)
        "bluetooth" -> Color(0xFF3B82F6)
        else -> Color(0xFF888888)
    }

    val lastSeen = dev.optString("last_seen", "")
    val ageColor = ageColor(lastSeen)

    val name = when (type) {
        "wifi" -> dev.optString("name", dev.optString("mac", "")).ifBlank { dev.optString("mac", "") }
        "cell" -> "${dev.optString("radio", "")} ${dev.optString("cell_id", "")}".trim()
        "bluetooth" -> dev.optString("name", dev.optString("mac", "")).ifBlank { dev.optString("mac", "") }
        else -> "Unknown"
    }
    val sub = when (type) {
        "wifi" -> "WiFi · ${dev.optString("encryption", "")} · ${dev.optString("mac", "")}"
        "cell" -> "Cell · ${dev.optString("mcc", "")}/${dev.optString("mnc", "")} · ${dev.optString("signal_dbm", "")} dBm"
        "bluetooth" -> "BLE · ${dev.optString("mac", "")} · ${dev.optString("rssi", "")} dBm"
        else -> ""
    }
    val manufacturer = dev.optString("manufacturer", "")
    val sightings = dev.optInt("sighting_count", 0)

    Card(
        modifier = Modifier.fillMaxWidth().clickable {
            expanded = !expanded
            if (expanded && history == null && !historyLoading) {
                historyLoading = true
                val mac = dev.optString("mac", "") ?: ""
                val lat = dev.optDouble("latitude", 0.0)
                val lng = dev.optDouble("longitude", 0.0)
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        repository.apiClient.adminGetDeviceHistory(mac, lat, lng)
                    }
                    historyLoading = false
                    history = result
                }
            }
        },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(2.dp, ageColor),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(name, style = MaterialTheme.typography.titleSmall)
                Text(type.uppercase(), style = MaterialTheme.typography.titleLarge, color = typeColor)
            }
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (manufacturer.isNotBlank()) {
                Text("Manufacturer: $manufacturer", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("Seen ${sightings}x · Last: ${fmtTime(lastSeen)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    HorizontalDivider()
                    Spacer(modifier = Modifier.padding(4.dp))

                    // All known data
                    Text("All Data", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val allFields = mutableListOf<Pair<String, String>>().apply {
                        add("Type" to type)
                        if (dev.optString("mac").isNotBlank()) add("MAC/BSSID" to dev.optString("mac"))
                        if (manufacturer.isNotBlank()) add("Manufacturer" to manufacturer)
                        if (dev.optString("name").isNotBlank()) add("Name" to dev.optString("name"))
                        if (dev.optString("encryption").isNotBlank()) add("Encryption" to dev.optString("encryption"))
                        if (dev.optString("radio").isNotBlank()) add("Radio" to dev.optString("radio"))
                        if (dev.optString("mcc").isNotBlank()) add("MCC" to dev.optString("mcc"))
                        if (dev.optString("mnc").isNotBlank()) add("MNC" to dev.optString("mnc"))
                        if (dev.optString("cell_id").isNotBlank()) add("Cell ID" to dev.optString("cell_id"))
                        if (dev.optString("signal_dbm").isNotBlank()) add("Signal (dBm)" to dev.optString("signal_dbm"))
                        if (dev.optString("rssi").isNotBlank()) add("RSSI (dBm)" to dev.optString("rssi"))
                        if (dev.optString("device_type").isNotBlank()) add("Device Type" to dev.optString("device_type"))
                        add("Sighting Count" to sightings.toString())
                        add("Last Seen" to fmtTime(lastSeen))
                    }
                    allFields.forEach { (k, v) ->
                        Text("  $k: $v", style = MaterialTheme.typography.bodySmall)
                    }

                    // Other locations
                    Spacer(modifier = Modifier.padding(8.dp))
                    if (historyLoading) {
                        Text("Loading history...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        val locs = history
                        if (locs != null && locs.length() > 0) {
                            Text("Other Locations (${locs.length()})", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.padding(4.dp))
                            for (j in 0 until locs.length()) {
                                val loc = locs.getJSONObject(j)
                                val hLat = loc.optDouble("latitude")
                                val hLng = loc.optDouble("longitude")
                                val hSeen = fmtTime(loc.optString("last_seen"))
                                val hCount = loc.optInt("sighting_count", 0)
                                Text(
                                    "  • ${"%.5f".format(hLat)}, ${"%.5f".format(hLng)} (seen ${hCount}x, $hSeen)",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.clickable {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$hLat,$hLng?q=$hLat,$hLng"))
                                        context.startActivity(intent)
                                    }
                                )
                            }
                        } else if (locs != null) {
                            Text("No other locations found", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

private fun ageColor(lastSeen: String): Color {
    if (lastSeen.isBlank()) return Color(0xFFEF4444) // red
    return try {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
        val date = sdf.parse(lastSeen)
        if (date == null) return Color(0xFFEF4444)
        val ageMs = Date().time - date.time
        val weekMs = TimeUnit.DAYS.toMillis(7)
        val monthMs = TimeUnit.DAYS.toMillis(30)
        when {
            ageMs < weekMs -> Color(0xFF4ADE80)  // green
            ageMs < monthMs -> Color(0xFFF59E0B) // orange
            else -> Color(0xFFEF4444)            // red
        }
    } catch (e: Exception) {
        Color(0xFFEF4444)
    }
}

private fun fmtTime(ts: String): String {
    if (ts.isBlank()) return "—"
    return try {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
        val date = sdf.parse(ts)
        if (date == null) return ts
        val diff = Date().time - date.time
        when {
            diff < TimeUnit.MINUTES.toMillis(1) -> "just now"
            diff < TimeUnit.HOURS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toMinutes(diff)}m ago"
            diff < TimeUnit.DAYS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toHours(diff)}h ago"
            diff < TimeUnit.DAYS.toMillis(7) -> "${TimeUnit.MILLISECONDS.toDays(diff)}d ago"
            else -> SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(date)
        }
    } catch (e: Exception) {
        ts
    }
}
