package de.radiopult.app.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiFind
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.radiopult.app.R
import de.radiopult.app.fsapi.RadioDevice
import de.radiopult.app.fsapi.WifiNetwork
import de.radiopult.app.setup.SetupStep
import de.radiopult.app.setup.SetupUi
import de.radiopult.app.setup.SetupViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(vm: SetupViewModel, onFinished: (RadioDevice?) -> Unit) {
    val s = vm.ui
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.setup_title)) },
                navigationIcon = {
                    IconButton(onClick = vm::close, enabled = s.step != SetupStep.APPLYING) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cancel))
                    }
                },
            )
        },
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StepIndicator(s.step)
            s.error?.let { SetupError(it) }

            when (s.step) {
                SetupStep.INTRO -> IntroStep(onNext = vm::next)
                SetupStep.JOIN -> JoinStep(s, vm.canPickWifi, vm::setPin, vm::joinWithPicker, vm::joinManually)
                SetupStep.NAME -> NameStep(s, vm::setName, vm::saveName)
                SetupStep.NETWORKS -> NetworksStep(s, vm::choose, vm::chooseHidden, vm::rescan)
                SetupStep.PASSWORD -> PasswordStep(s, vm::connectWith, vm::back)
                SetupStep.APPLYING -> Busy(s.status)
                SetupStep.DONE -> DoneStep(s) {
                    onFinished(s.found)
                    vm.close()
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun StepIndicator(step: SetupStep) {
    val index = when (step) {
        SetupStep.INTRO -> 0
        SetupStep.JOIN -> 1
        SetupStep.NAME -> 2
        SetupStep.NETWORKS, SetupStep.PASSWORD -> 3
        SetupStep.APPLYING -> 4
        SetupStep.DONE -> 5
    }
    LinearProgressIndicator(progress = { index / 5f }, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun IntroStep(onNext: () -> Unit) {
    Text(stringResource(R.string.setup_intro_title), style = MaterialTheme.typography.titleLarge)
    Paragraph(
        stringResource(R.string.setup_intro_1),
    )
    Paragraph(
        stringResource(R.string.setup_intro_2),
    )
    Paragraph(stringResource(R.string.setup_intro_3))
    Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.next)) }
}

@Composable
private fun JoinStep(
    s: SetupUi,
    canPick: Boolean,
    onPin: (String) -> Unit,
    onPick: () -> Unit,
    onManual: () -> Unit,
) {
    val context = LocalContext.current
    Text(stringResource(R.string.setup_join_title), style = MaterialTheme.typography.titleLarge)
    if (s.busy) {
        Busy(stringResource(R.string.setup_connecting))
        return
    }
    if (canPick) {
        Paragraph(stringResource(R.string.setup_pick_hint))
        Button(onClick = onPick, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.WifiFind, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.setup_pick))
        }
        Paragraph(stringResource(R.string.setup_manual_fallback))
    } else {
        Paragraph(stringResource(R.string.setup_manual_hint))
    }
    OutlinedButton(
        onClick = { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) },
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.open_wifi_settings)) }
    OutlinedButton(onClick = onManual, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.setup_joined))
    }
    Paragraph(stringResource(R.string.setup_no_internet_hint))
    OutlinedTextField(
        value = s.pin,
        onValueChange = onPin,
        label = { Text(stringResource(R.string.radio_pin)) },
        supportingText = { Text(stringResource(R.string.setup_pin_hint)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun NameStep(s: SetupUi, onName: (String) -> Unit, onSave: () -> Unit) {
    Text(stringResource(R.string.setup_name_title), style = MaterialTheme.typography.titleLarge)
    Paragraph(stringResource(R.string.setup_name_hint))
    OutlinedTextField(
        value = s.name,
        onValueChange = onName,
        label = { Text(stringResource(R.string.name)) },
        singleLine = true,
        enabled = !s.busy,
        modifier = Modifier.fillMaxWidth(),
    )
    Button(onClick = onSave, enabled = !s.busy && s.name.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.next))
    }
}

@Composable
private fun NetworksStep(
    s: SetupUi,
    onChoose: (WifiNetwork) -> Unit,
    onHidden: (String, Boolean) -> Unit,
    onRescan: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.choose_wifi), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        IconButton(onClick = onRescan, enabled = !s.busy) {
            Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.search_again))
        }
    }
    if (s.busy) {
        Busy(stringResource(R.string.setup_scanning))
        return
    }
    Paragraph(stringResource(R.string.setup_which_wifi))
    s.networks.forEach { network ->
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onChoose(network) },
        ) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Wifi, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(
                    network.ssid,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (network.secured) {
                    Icon(
                        Icons.Filled.Lock,
                        contentDescription = stringResource(R.string.password_protected),
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    HiddenNetwork(onHidden)
}

@Composable
private fun HiddenNetwork(onHidden: (String, Boolean) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    var ssid by rememberSaveable { mutableStateOf("") }
    var secured by rememberSaveable { mutableStateOf(true) }
    if (!open) {
        TextButton(onClick = { open = true }) { Text(stringResource(R.string.hidden_wifi)) }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = ssid,
            onValueChange = { ssid = it.take(32) },
            label = { Text(stringResource(R.string.wifi_ssid)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = secured, onCheckedChange = { secured = it })
            Text(stringResource(R.string.with_password))
        }
        Button(onClick = { onHidden(ssid, secured) }, enabled = ssid.isNotBlank()) { Text(stringResource(R.string.next)) }
    }
}

@Composable
private fun PasswordStep(s: SetupUi, onConnect: (String) -> Unit, onBack: () -> Unit) {
    var password by rememberSaveable { mutableStateOf("") }
    var visible by rememberSaveable { mutableStateOf(false) }
    Text(stringResource(R.string.password_for, s.selected?.ssid.orEmpty()), style = MaterialTheme.typography.titleLarge)
    OutlinedTextField(
        value = password,
        onValueChange = { password = it.take(64) },
        label = { Text(stringResource(R.string.wifi_password)) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (visible) stringResource(R.string.hide_password) else stringResource(R.string.show_password),
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
    Paragraph(stringResource(R.string.password_encrypted))
    Button(onClick = { onConnect(password) }, enabled = password.length >= 8, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.connect))
    }
    TextButton(onClick = onBack) { Text(stringResource(R.string.other_wifi)) }
}

@Composable
private fun DoneStep(s: SetupUi, onDone: () -> Unit) {
    val found = s.found
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(64.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            if (found != null) stringResource(R.string.setup_done) else stringResource(R.string.setup_applying),
            style = MaterialTheme.typography.titleLarge,
        )
    }
    if (found != null) {
        Paragraph(stringResource(R.string.setup_found, found.name, s.selected?.ssid.orEmpty()))
    } else {
        Paragraph(
            stringResource(R.string.setup_not_found),
        )
        Paragraph(
            stringResource(R.string.setup_wrong_password),
        )
    }
    Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.go_to_devices)) }
}

@Composable
private fun Busy(text: String) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Paragraph(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SetupError(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(Modifier.padding(12.dp)) { Text(text, style = MaterialTheme.typography.bodyMedium) }
    }
}
