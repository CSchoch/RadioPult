package de.radiopult.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.radiopult.app.R
import de.radiopult.app.RadioViewModel

/** Bluetooth des Radios: sichtbar machen zum Koppeln, gekoppelte und verbundene Geräte. */
@Composable
fun BluetoothScreen(vm: RadioViewModel, onBack: () -> Unit) {
    LaunchedEffect(Unit) { vm.loadBluetooth() }
    val b = vm.bluetooth
    SubScreen(
        vm,
        stringResource(R.string.bluetooth),
        onBack,
        actions = {
            IconButton(onClick = vm::loadBluetooth) {
                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.refresh))
            }
        },
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState()),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                HintText(stringResource(if (b.discoverable == true) R.string.bluetooth_discoverable_hint else R.string.bluetooth_pair_hint))
                Button(onClick = vm::pairBluetooth, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Bluetooth, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.bluetooth_pair))
                }
            }
            if (b.loading && b.devices.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            if (b.devices.isNotEmpty()) {
                Text(stringResource(R.string.bluetooth_devices), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(16.dp))
            } else if (!b.loading) {
                HintText(stringResource(R.string.bluetooth_no_devices), Modifier.padding(16.dp))
            }
            b.devices.forEach { d ->
                SettingRow(
                    d.name,
                    subtitle = stringResource(
                        when (d.state) {
                            1 -> R.string.bluetooth_connected
                            2 -> R.string.bluetooth_paired
                            else -> R.string.bluetooth_disconnected
                        },
                    ),
                ) {
                    Icon(
                        if (d.state == 1) Icons.Filled.BluetoothConnected else Icons.Filled.Bluetooth,
                        contentDescription = null,
                        tint = if (d.state == 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
