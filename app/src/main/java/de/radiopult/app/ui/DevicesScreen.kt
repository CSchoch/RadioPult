package de.radiopult.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import de.radiopult.app.RadioViewModel
import de.radiopult.app.fsapi.RadioDevice

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(vm: RadioViewModel, onSetup: () -> Unit) {
    var pinFor by remember { mutableStateOf<RadioDevice?>(null) }
    var manualIp by rememberSaveable { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("RadioPult") },
                actions = {
                    IconButton(onClick = onSetup) {
                        Icon(Icons.Filled.Add, contentDescription = "Neues Radio einrichten")
                    }
                    IconButton(onClick = vm::scan, enabled = !vm.scanning) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Erneut suchen")
                    }
                },
            )
        },
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column {
                    Text("Radios im WLAN", style = MaterialTheme.typography.titleMedium)
                    if (vm.scanning) {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }

            if (vm.devices.isEmpty() && !vm.scanning) {
                item {
                    Text(
                        "Kein Radio gefunden. Handy und Radio müssen im selben WLAN sein. " +
                            "Du kannst oben neu suchen oder die IP-Adresse unten eingeben. " +
                            "Ein neues Radio bringst du mit „Neues Radio einrichten“ ins WLAN.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            items(vm.devices, key = { it.host }) { device ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable {
                        vm.clearError()
                        pinFor = device
                    },
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Radio,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp),
                        )
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(device.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                device.host,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            item {
                FilledTonalButton(onClick = onSetup, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Neues Radio einrichten")
                }
            }

            item {
                Column {
                    Spacer(Modifier.height(8.dp))
                    Text("Manuell verbinden", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = manualIp,
                            onValueChange = { manualIp = it },
                            label = { Text("IP-Adresse, z. B. 192.168.178.40") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                val ip = manualIp.trim()
                                vm.clearError()
                                pinFor = RadioDevice(ip, ip)
                            },
                            enabled = manualIp.isNotBlank(),
                        ) { Text("Los") }
                    }
                }
            }

            item {
                Text(
                    "Die IP-Adresse steht am Radio meist unter Einstellungen › Netzwerk › Einstellungen anzeigen. " +
                        "Die Standard-PIN ist bei vielen Geräten 1234.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    pinFor?.let { device ->
        PinDialog(
            device = device,
            initialPin = vm.savedPin(device.host),
            connecting = vm.connecting,
            error = vm.connectError,
            onDismiss = {
                pinFor = null
                vm.clearError()
            },
            onConnect = { pin -> vm.connect(device, pin) },
        )
    }
}

@Composable
private fun PinDialog(
    device: RadioDevice,
    initialPin: String,
    connecting: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onConnect: (String) -> Unit,
) {
    var pin by remember(device.host) { mutableStateOf(initialPin) }
    AlertDialog(
        onDismissRequest = { if (!connecting) onDismiss() },
        title = { Text(device.name) },
        text = {
            Column {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter(Char::isDigit).take(8) },
                    label = { Text("PIN des Radios") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConnect(pin) }, enabled = !connecting && pin.isNotEmpty()) {
                if (connecting) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("Verbinden")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !connecting) { Text("Abbrechen") }
        },
    )
}
