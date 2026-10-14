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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
                title = { Text("Radio einrichten") },
                navigationIcon = {
                    IconButton(onClick = vm::close, enabled = s.step != SetupStep.APPLYING) {
                        Icon(Icons.Filled.Close, contentDescription = "Abbrechen")
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
    Text("Neues Radio ins WLAN bringen", style = MaterialTheme.typography.titleLarge)
    Paragraph(
        "Ein neues oder auf Werkseinstellungen zurückgesetztes Radio startet im Setup-Modus und " +
            "öffnet ein eigenes WLAN, meist „Setup <Marke> <Modell> …“.",
    )
    Paragraph(
        "Ist das Radio schon eingerichtet, startest du den Setup-Modus am Radio unter " +
            "Einstellungen › Netzwerk › Netzwerkassistent bzw. „Netzwerkeinstellungen löschen“ " +
            "(je nach Gerät auch „Mit UNDOK einrichten“ / „Setup über App“).",
    )
    Paragraph("Halte das Passwort deines WLANs bereit.")
    Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) { Text("Weiter") }
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
    Text("Mit dem Radio verbinden", style = MaterialTheme.typography.titleLarge)
    if (s.busy) {
        Busy("Verbinde mit dem Radio …")
        return
    }
    if (canPick) {
        Paragraph("Tippe auf „Setup-WLAN suchen“ und wähle im folgenden Fenster das WLAN deines Radios.")
        Button(onClick = onPick, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.WifiFind, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Setup-WLAN suchen")
        }
        Paragraph("Erscheint dein Radio dort nicht, verbinde dich selbst in den WLAN-Einstellungen:")
    } else {
        Paragraph("Öffne die WLAN-Einstellungen, verbinde dich mit dem WLAN „Setup …“ des Radios und komm dann hierher zurück.")
    }
    OutlinedButton(
        onClick = { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("WLAN-Einstellungen öffnen") }
    OutlinedButton(onClick = onManual, modifier = Modifier.fillMaxWidth()) {
        Text("Ich bin mit dem Setup-WLAN verbunden")
    }
    Paragraph("Fragt Android, ob du im WLAN ohne Internet bleiben willst, bestätige mit „Ja“.")
    OutlinedTextField(
        value = s.pin,
        onValueChange = onPin,
        label = { Text("PIN des Radios") },
        supportingText = { Text("Im Setup-Modus fast immer 1234") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun NameStep(s: SetupUi, onName: (String) -> Unit, onSave: () -> Unit) {
    Text("Name des Radios", style = MaterialTheme.typography.titleLarge)
    Paragraph("Unter diesem Namen erscheint das Radio später in der App, z. B. „Küche“ oder „Wohnzimmer“.")
    OutlinedTextField(
        value = s.name,
        onValueChange = onName,
        label = { Text("Name") },
        singleLine = true,
        enabled = !s.busy,
        modifier = Modifier.fillMaxWidth(),
    )
    Button(onClick = onSave, enabled = !s.busy && s.name.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
        Text("Weiter")
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
        Text("WLAN wählen", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        IconButton(onClick = onRescan, enabled = !s.busy) {
            Icon(Icons.Filled.Refresh, contentDescription = "Erneut suchen")
        }
    }
    if (s.busy) {
        Busy("Das Radio sucht nach WLANs …")
        return
    }
    Paragraph("Mit welchem WLAN soll sich das Radio verbinden?")
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
                        contentDescription = "Passwortgeschützt",
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
        TextButton(onClick = { open = true }) { Text("Verstecktes WLAN eingeben") }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = ssid,
            onValueChange = { ssid = it.take(32) },
            label = { Text("Name des WLANs (SSID)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = secured, onCheckedChange = { secured = it })
            Text("Mit Passwort (WPA2)")
        }
        Button(onClick = { onHidden(ssid, secured) }, enabled = ssid.isNotBlank()) { Text("Weiter") }
    }
}

@Composable
private fun PasswordStep(s: SetupUi, onConnect: (String) -> Unit, onBack: () -> Unit) {
    var password by rememberSaveable { mutableStateOf("") }
    var visible by rememberSaveable { mutableStateOf(false) }
    Text("Passwort für „${s.selected?.ssid.orEmpty()}“", style = MaterialTheme.typography.titleLarge)
    OutlinedTextField(
        value = password,
        onValueChange = { password = it.take(64) },
        label = { Text("WLAN-Passwort") },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (visible) "Passwort verbergen" else "Passwort zeigen",
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
    Paragraph("Das Passwort wird verschlüsselt an das Radio übertragen.")
    Button(onClick = { onConnect(password) }, enabled = password.length >= 8, modifier = Modifier.fillMaxWidth()) {
        Text("Verbinden")
    }
    TextButton(onClick = onBack) { Text("Anderes WLAN wählen") }
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
            if (found != null) "Fertig!" else "Einstellungen übertragen",
            style = MaterialTheme.typography.titleLarge,
        )
    }
    if (found != null) {
        Paragraph("„${found.name}“ ist jetzt im WLAN „${s.selected?.ssid.orEmpty()}“ und erscheint in der Geräteliste.")
    } else {
        Paragraph(
            "Das Radio wurde im WLAN noch nicht gefunden. Es kann eine Minute dauern, bis es verbunden ist – " +
                "suche dann in der Geräteliste erneut.",
        )
        Paragraph(
            "Zeigt das Radio einen Fehler oder öffnet es wieder das Setup-WLAN, war das Passwort vermutlich " +
                "falsch. Starte die Einrichtung dann noch einmal.",
        )
    }
    Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Zur Geräteliste") }
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
