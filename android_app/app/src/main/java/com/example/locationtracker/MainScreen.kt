package com.example.locationtracker

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private val BroadcastingGreen = Color(0xFF4CAF50)
private val PrivacyRed = Color(0xFFF44336)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(repository: LocationTrackerRepository, onOpenSettings: () -> Unit, onOpenAdmin: () -> Unit) {
    val scope = rememberCoroutineScope()
    var privacyActive by remember { mutableStateOf(repository.isPrivacyActive()) }
    var privacyMessage by remember { mutableStateOf(repository.privacySummary()) }
    var statusMessage by remember { mutableStateOf("") }
    var tapCount by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        privacyActive = repository.isPrivacyActive()
        privacyMessage = repository.privacySummary()
        AppLogger.log("MainScreen", "Screen opened, privacy=$privacyActive")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("Location Tracker", modifier = Modifier.pointerInput(Unit) {
                        detectTapGestures(
                            onTap = {
                                tapCount++
                                if (tapCount >= 7) {
                                    tapCount = 0
                                    AppLogger.log("MainScreen", "Admin screen triggered (7 taps)")
                                    onOpenAdmin()
                                }
                            }
                        )
                    })
                },
                actions = {
                    IconButton(onClick = {
                        AppLogger.log("MainScreen", "Settings button pressed")
                        onOpenSettings()
                    }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        ) {
            Box(
                modifier = Modifier
                    .size(140.dp)
                    .clip(CircleShape)
                    .background(if (privacyActive) PrivacyRed else BroadcastingGreen),
            )

            Text(
                if (privacyActive) "Privacy mode active" else "Broadcasting",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = if (privacyActive) PrivacyRed else BroadcastingGreen,
            )

            Text(
                privacyMessage,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (statusMessage.isNotEmpty()) {
                Text(
                    statusMessage,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (privacyActive) {
                Button(
                    onClick = {
                        AppLogger.log("MainScreen", "Cancel privacy mode pressed")
                        repository.cancelPrivacyPause()
                        privacyActive = repository.isPrivacyActive()
                        privacyMessage = repository.privacySummary()
                        statusMessage = "Privacy mode cancelled — broadcasting resumed"
                    },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = PrivacyRed,
                    ),
                ) {
                    Text("Cancel privacy mode")
                }
            } else {
                Text(
                    "Request privacy pause",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                )

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf(1, 2, 4, 8).forEach { hours ->
                        Button(onClick = {
                            AppLogger.log("MainScreen", "Privacy pause requested: ${hours}h")
                            scope.launch {
                                statusMessage = repository.requestPrivacyPause(hours)
                                privacyActive = repository.isPrivacyActive()
                                privacyMessage = repository.privacySummary()
                            }
                        }) {
                            Text("${hours}h")
                        }
                    }
                }
            }
        }
    }
}
