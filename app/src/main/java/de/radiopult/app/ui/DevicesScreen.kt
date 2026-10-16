package de.radiopult.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.radiopult.app.RadioViewModel
import de.radiopult.app.fsapi.RadioDevice

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DevicesScreen(vm: RadioViewModel, onSetup: () -> Unit) {
    var pinFor by remember { mutableStateOf<RadioDevice?>(null) }
    var manual by rememberSaveable { mutableStateOf(false) }

    /** Mit gespeicherter PIN direkt verbinden; nur beim ersten Mal oder wenn es nicht klappt nach der PIN fragen. */
    fun open(device: RadioDevice) {
        vm.clearError()
        if (vm.hasSavedPin(device.host)) {
            vm.connect(device, vm.savedPin(device.host)) { pinFor = device }
        } else {
            pinFor = device
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("RadioPult") },
                actions = {
                    IconButton(onClick = vm::scan, enabled = !vm.scanning) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Erneut suchen")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onSetup,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Neues Radio") },
            )
        },
    ) { pad ->
        if (vm.devices.isEmpty() && !vm.scanning) {
            EmptyDevices(Modifier.padding(pad), onScan = vm::scan, onManual = { manual = true })
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(pad),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 8.dp).height(32.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Radios im WLAN",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                        )
                        if (vm.scanning) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                }
                itemsIndexed(vm.devices, key = { _, d -> d.host }) { i, device ->
                    val last = vm.devices.lastIndex
                    Surface(
                        shape = groupShape(i, last),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.fillMaxWidth().clip(groupShape(i, last)).combinedClickable(
                            enabled = !vm.connecting,
                            onClick = { open(device) },
                            onLongClick = {
                                vm.clearError()
                                pinFor = device
                            },
                            onLongClickLabel = "PIN ändern",
                        ),
                    ) {
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            leadingContent = {
                                Box(
                                    Modifier.size(44.dp).clip(RoundedCornerShape(14.dp))
                                        .background(MaterialTheme.colorScheme.primaryContainer),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(Icons.Filled.Radio, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                            },
                            headlineContent = { Text(device.name, style = MaterialTheme.typography.titleMedium) },
                            supportingContent = { Text(device.host) },
                            trailingContent = {
                                if (vm.connectingHost == device.host && pinFor == null) {
                                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                                }
                            },
                        )
                    }
                }
                item {
                    TextButton(onClick = { manual = true }, modifier = Modifier.padding(top = 8.dp)) {
                        Icon(Icons.Filled.Lan, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Per IP-Adresse verbinden")
                    }
                }
            }
        }
    }

    if (manual) {
        ManualDialog(
            connecting = vm.connecting,
            onDismiss = { manual = false },
            onConnect = { ip ->
                manual = false
                open(RadioDevice(ip, ip))
            },
        )
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
private fun EmptyDevices(modifier: Modifier, onScan: () -> Unit, onManual: () -> Unit) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(96.dp).clip(RoundedCornerShape(32.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Radio, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(24.dp))
        Text("Kein Radio gefunden", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Handy und Radio müssen im selben WLAN sein.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        FilledTonalButton(onClick = onScan) {
            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Erneut suchen")
        }
        TextButton(onClick = onManual) { Text("Per IP-Adresse verbinden") }
    }
}

@Composable
private fun ManualDialog(connecting: Boolean, onDismiss: () -> Unit, onConnect: (String) -> Unit) {
    var ip by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Lan, contentDescription = null) },
        title = { Text("Per IP-Adresse verbinden") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = ip,
                    onValueChange = { ip = it },
                    label = { Text("IP-Adresse") },
                    placeholder = { Text("192.168.178.40") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                Text(
                    "Steht am Radio meist unter Einstellungen › Netzwerk › Einstellungen anzeigen. " +
                        "Die Standard-PIN ist oft 1234; zum Ändern ein Radio in der Liste lange drücken.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConnect(ip.trim()) }, enabled = ip.isNotBlank() && !connecting) { Text("Verbinden") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
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
