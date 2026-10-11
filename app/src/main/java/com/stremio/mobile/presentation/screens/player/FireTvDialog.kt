package com.stremio.mobile.presentation.screens.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stremio.mobile.firetv.FireTvManager
import kotlinx.coroutines.launch

/**
 * Setup dialog for the Fire TV receiver: scan the LAN, connect manually by IP,
 * or disconnect. Install the "Stremio TV Receiver" APK on the Fire TV first
 * (sideload via Downloader); it listens on port 8080.
 */
@Composable
fun FireTvDialog(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val connectedDevice by FireTvManager.connectedDevice.collectAsState()
    val devices by FireTvManager.devices.collectAsState()
    val isScanning by FireTvManager.isScanning.collectAsState()
    var manualIp by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Fire TV Receiver", color = Color.White) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (connectedDevice != null) {
                    Text(
                        "Connected to ${connectedDevice!!.name}",
                        color = Color(0xFF4CAF50),
                        fontSize = 14.sp,
                    )
                    OutlinedButton(
                        onClick = {
                            FireTvManager.disconnect()
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Disconnect")
                    }
                } else {
                    Text(
                        "Install the Stremio TV Receiver on your Fire TV Stick (sideload the APK), then connect below. Both devices must be on the same Wi-Fi.",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 13.sp,
                    )
                    Button(
                        onClick = {
                            error = null
                            scope.launch { FireTvManager.scan() }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isScanning,
                    ) {
                        if (isScanning) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .width(18.dp)
                                    .height(18.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (isScanning) "Scanning…" else "Scan for Fire TV")
                    }
                    devices.forEach { device ->
                        OutlinedButton(
                            onClick = {
                                busy = true
                                error = null
                                scope.launch {
                                    FireTvManager.connect(device)
                                    busy = false
                                    onDismiss()
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !busy,
                        ) {
                            Text("Connect to ${device.ip}")
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("Or enter IP manually:", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                    OutlinedTextField(
                        value = manualIp,
                        onValueChange = { manualIp = it; error = null },
                        label = { Text("Fire TV IP address") },
                        placeholder = { Text("192.168.1.42") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            if (manualIp.isBlank()) {
                                error = "Enter an IP address"
                                return@Button
                            }
                            busy = true
                            error = null
                            scope.launch {
                                val ok = FireTvManager.connectManual(manualIp)
                                busy = false
                                if (ok) onDismiss()
                                else error = "No receiver found at $manualIp:8080"
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy,
                    ) {
                        Text("Connect")
                    }
                    error?.let {
                        Text(it, color = Color(0xFFFF6B6B), fontSize = 13.sp)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
        modifier = Modifier.padding(16.dp),
    )
}
