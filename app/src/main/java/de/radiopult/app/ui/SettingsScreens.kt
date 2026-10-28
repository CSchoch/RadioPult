package de.radiopult.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import de.radiopult.app.Choice
import de.radiopult.app.R
import de.radiopult.app.RadioViewModel
import de.radiopult.app.SettingsUi
import de.radiopult.app.TimeZoneChoice
import de.radiopult.app.fsapi.Values
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val SLEEP_OPTIONS = listOf(0, 15, 30, 45, 60, 90, 120)

@Composable
fun SettingsScreen(
    vm: RadioViewModel,
    onBack: () -> Unit,
    onLanguage: () -> Unit,
    onSleep: () -> Unit,
    onEqualizer: () -> Unit,
    onClock: () -> Unit,
    onMultiroom: () -> Unit,
    onBluetooth: () -> Unit,
) {
    LaunchedEffect(Unit) { vm.loadSettings() }
    val s = vm.settings
    var renaming by rememberSaveable { mutableStateOf(false) }
    var confirmReset by rememberSaveable { mutableStateOf(false) }

    SubScreen(vm, stringResource(R.string.settings), onBack) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState()),
        ) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(48.dp)
                                .padding(4.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Filled.Radio, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            vm.ui.name.ifEmpty { vm.current?.name.orEmpty() },
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        OutlinedButton(onClick = { renaming = true }) { Text(stringResource(R.string.rename)) }
                    }
                    DeviceInfo(s, vm.current?.host)
                }
            }

            if (!s.loaded && s.loading) {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            if (s.languages.isNotEmpty()) {
                SettingRow(
                    stringResource(R.string.language),
                    subtitle = s.languages.firstOrNull { it.key == s.languageKey }?.label,
                    onClick = onLanguage,
                    trailing = { Chevron() },
                )
            }
            if (s.sleepSeconds != null) {
                SettingRow(stringResource(R.string.sleep_timer), subtitle = sleepText(s.sleepSeconds), onClick = onSleep, trailing = { Chevron() })
            }
            SettingRow(stringResource(R.string.equalizer), onClick = onEqualizer, trailing = { Chevron() })
            if (s.clock24 != null || s.clockSources.isNotEmpty() || s.timeZones.isNotEmpty() || s.dst != null || s.manualClock) {
                SettingRow(stringResource(R.string.date_time), subtitle = clockSummary(s), onClick = onClock, trailing = { Chevron() })
            }
            if (vm.ui.modes.any { it.id.contains("BLUETOOTH", ignoreCase = true) }) {
                SettingRow(stringResource(R.string.bluetooth), subtitle = stringResource(R.string.bluetooth_summary), onClick = onBluetooth, trailing = { Chevron() })
            }
            SettingRow(stringResource(R.string.multiroom), subtitle = stringResource(R.string.multiroom_summary), onClick = onMultiroom, trailing = { Chevron() })
            s.streamQuality?.let { StreamQualityRow(it, vm::setStreamQuality) }
            s.extDelay?.let { ExtDelayRow(it, s.extDelayMax ?: 500, vm::setExtDelay) }
            NotificationRow(vm.notificationOn, vm::setNotification)
            if (s.loaded) {
                SettingRow(stringResource(R.string.locate), subtitle = stringResource(R.string.locate_hint), onClick = vm::locate) {
                    Icon(Icons.Outlined.NotificationsActive, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            }
            if (s.loaded) {
                Column {
                    Text(
                        stringResource(R.string.factory_reset),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { confirmReset = true }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                    )
                    HorizontalDivider()
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (renaming) {
        RenameDialog(
            initial = vm.ui.name.ifEmpty { vm.current?.name.orEmpty() },
            onDismiss = { renaming = false },
            onRename = {
                vm.rename(it)
                renaming = false
            },
        )
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.factory_reset_title)) },
            text = {
                Text(
                    stringResource(R.string.factory_reset_text),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    vm.factoryReset()
                }) { Text(stringResource(R.string.reset), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun DeviceInfo(s: SettingsUi, host: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        s.model?.let { InfoLine(stringResource(R.string.model_name), it) }
        s.version?.let { InfoLine(stringResource(R.string.firmware_version), it) }
        (s.ip ?: host)?.let { InfoLine(stringResource(R.string.ip_address), it) }
        s.mac?.let { InfoLine(stringResource(R.string.mac_address), it) }
        s.network?.let { InfoLine(stringResource(R.string.network), it) }
        s.dhcp?.let { InfoLine(stringResource(R.string.ip_config), stringResource(if (it) R.string.ip_dhcp else R.string.ip_static)) }
        s.subnet?.let { InfoLine(stringResource(R.string.subnet_mask), it) }
        s.gateway?.let { InfoLine(stringResource(R.string.gateway), it) }
        listOfNotNull(s.dns1, s.dns2).filter { it != "0.0.0.0" }.ifEmpty { null }?.let {
            InfoLine(stringResource(R.string.dns_server), it.joinToString(", "))
        }
        s.signal?.let { signal ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                InfoLabel(stringResource(R.string.wifi_signal))
                LinearProgressIndicator(
                    progress = { signal / 100f },
                    modifier = Modifier.weight(1f).height(6.dp),
                    drawStopIndicator = {},
                )
                Text(
                    " $signal %",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row {
        InfoLabel(label)
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun InfoLabel(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.width(140.dp),
    )
}

@Composable
private fun Chevron() {
    Icon(
        Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun RenameDialog(initial: String, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_radio)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(32) },
                label = { Text(stringResource(R.string.name)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            )
        },
        confirmButton = {
            TextButton(onClick = { onRename(name) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Qualität der Internet-Streams (weniger Daten oder besserer Klang). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StreamQualityRow(level: Int, onSelect: (Int) -> Unit) {
    val labels = listOf(R.string.quality_low, R.string.quality_normal, R.string.quality_high)
    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.stream_quality), style = MaterialTheme.typography.bodyLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            labels.forEachIndexed { i, label ->
                SegmentedButton(
                    selected = level == i,
                    onClick = { onSelect(i) },
                    shape = SegmentedButtonDefaults.itemShape(i, labels.size),
                ) { Text(stringResource(label)) }
            }
        }
    }
    HorizontalDivider()
}

/** Verzögerung für externe Lautsprecher, damit Bild und Ton zusammenpassen. */
@Composable
private fun ExtDelayRow(ms: Int, max: Int, onChange: (Int) -> Unit) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shown = (dragging ?: ms.toFloat()).roundToInt()
    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.ext_delay), style = MaterialTheme.typography.bodyLarge)
                HintText(stringResource(R.string.ext_delay_hint))
            }
            Text(stringResource(R.string.milliseconds, shown), style = MaterialTheme.typography.labelLarge)
        }
        Slider(
            value = shown.toFloat().coerceIn(0f, max.toFloat()),
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { onChange(it.roundToInt()) }
                dragging = null
            },
            valueRange = 0f..max.toFloat(),
        )
    }
    HorizontalDivider()
}

/** Medien-Benachrichtigung mit Steuerung auf dem Sperrbildschirm; ab Android 13 erst nach Erlaubnis. */
@Composable
private fun NotificationRow(on: Boolean, onChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> onChange(granted) }
    val toggle = { want: Boolean ->
        val needsPermission = want && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) ask.launch(Manifest.permission.POST_NOTIFICATIONS) else onChange(want)
    }
    SettingRow(stringResource(R.string.media_notification), subtitle = stringResource(R.string.media_notification_hint), onClick = { toggle(!on) }) {
        Switch(checked = on, onCheckedChange = { toggle(it) })
    }
}

@Composable
private fun sleepText(seconds: Int?): String =
    if (seconds == null || seconds <= 0) stringResource(R.string.off) else stringResource(R.string.sleep_off_in, Values.sleepMinutes(seconds))

@Composable
private fun clockSummary(s: SettingsUi): String? = listOfNotNull(
    s.clock24?.let { if (it) stringResource(R.string.hours_24) else stringResource(R.string.hours_12) },
    s.clockSources.firstOrNull { it.key == s.clockSourceKey }?.label,
    s.utcOffset?.let { Values.utcOffset(it) },
).joinToString(" · ").ifEmpty { null }

@Composable
fun LanguageScreen(vm: RadioViewModel, onBack: () -> Unit) {
    val s = vm.settings
    SubScreen(vm, stringResource(R.string.language), onBack) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState()),
        ) {
            HintText(stringResource(R.string.language_hint), Modifier.padding(16.dp))
            s.languages.forEach { lang -> ChoiceRow(lang, lang.key == s.languageKey) { vm.setLanguage(lang) } }
        }
    }
}

@Composable
private fun ChoiceRow(choice: Choice, selected: Boolean, onClick: () -> Unit) {
    SettingRow(choice.label, onClick = onClick) {
        if (selected) Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.selected_choice), tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
fun SleepScreen(vm: RadioViewModel, onBack: () -> Unit) {
    val seconds = vm.settings.sleepSeconds ?: 0
    // Restzeit regelmäßig auffrischen, solange die Seite offen ist
    LaunchedEffect(Unit) {
        while (true) {
            vm.refreshSleep()
            delay(15_000)
        }
    }
    val active = seconds > 0
    // ausgewählte Stufe: die nächste, die zur Restzeit passt
    val selected = if (!active) 0 else SLEEP_OPTIONS.filter { it > 0 }.firstOrNull { it * 60 >= seconds } ?: 120
    SubScreen(vm, stringResource(R.string.sleep_timer), onBack) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState()),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) {
                Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (active) stringResource(R.string.sleep_minutes_short, Values.sleepMinutes(seconds)) else stringResource(R.string.off),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (active) stringResource(R.string.sleep_active_hint) else stringResource(R.string.sleep_inactive_hint),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            SLEEP_OPTIONS.forEach { minutes ->
                SettingRow(
                    if (minutes == 0) stringResource(R.string.off) else pluralStringResource(R.plurals.minutes, minutes, minutes),
                    onClick = { vm.setSleep(minutes) },
                ) {
                    RadioButton(selected = minutes == selected, onClick = { vm.setSleep(minutes) })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClockScreen(vm: RadioViewModel, onBack: () -> Unit) {
    val s = vm.settings
    var pickZone by rememberSaveable { mutableStateOf(false) }
    SubScreen(vm, stringResource(R.string.date_time), onBack) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState()),
        ) {
            s.clock24?.let { is24 ->
                Text(stringResource(R.string.hour_format), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(16.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    SegmentedButton(
                        selected = !is24,
                        onClick = { vm.setClock24(false) },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                    ) { Text(stringResource(R.string.hours_12)) }
                    SegmentedButton(
                        selected = is24,
                        onClick = { vm.setClock24(true) },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                    ) { Text(stringResource(R.string.hours_24)) }
                }
                Spacer(Modifier.height(8.dp))
            }
            if (s.clockSources.isNotEmpty()) {
                Text(stringResource(R.string.time_source), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(16.dp))
                s.clockSources.forEach { source ->
                    SettingRow(source.label, onClick = { vm.setClockSource(source) }) {
                        RadioButton(selected = source.key == s.clockSourceKey, onClick = { vm.setClockSource(source) })
                    }
                }
                val id = s.clockSourceKey?.let { s.clockSourceIds[it] }
                if (id != null) {
                    HintText(
                        if (Values.clockSourceAutomatic(id)) stringResource(R.string.clock_automatic)
                        else stringResource(R.string.clock_manual),
                        Modifier.padding(16.dp),
                    )
                }
            }
            if (s.timeZones.isNotEmpty() || s.dst != null || s.manualClock) {
                Text(stringResource(R.string.time_zone_section), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(16.dp))
            }
            if (s.timeZones.isNotEmpty()) {
                val zone = s.timeZones.firstOrNull { it.region.isNotEmpty() && it.region == s.timeZone }
                    ?: s.timeZones.firstOrNull { it.offset == s.utcOffset }
                SettingRow(
                    stringResource(R.string.time_zone),
                    subtitle = zone?.let { zoneLabel(it) } ?: s.utcOffset?.let { Values.utcOffset(it) },
                    onClick = { pickZone = true },
                    trailing = { Chevron() },
                )
            }
            s.dst?.let { dst ->
                SettingRow(stringResource(R.string.dst), subtitle = stringResource(R.string.dst_hint), onClick = { vm.setDst(!dst) }) {
                    Switch(checked = dst, onCheckedChange = vm::setDst)
                }
            }
            if (s.manualClock) {
                SettingRow(stringResource(R.string.clock_from_phone), subtitle = stringResource(R.string.clock_from_phone_hint)) {
                    OutlinedButton(onClick = vm::setClockFromPhone) { Text(stringResource(R.string.set)) }
                }
            }
        }
    }
    if (pickZone) {
        AlertDialog(
            onDismissRequest = { pickZone = false },
            title = { Text(stringResource(R.string.time_zone)) },
            text = {
                LazyColumn {
                    items(s.timeZones, key = { it.key }) { zone ->
                        val selected = if (s.timeZone.isNullOrEmpty()) zone.offset == s.utcOffset else zone.region == s.timeZone
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    pickZone = false
                                    vm.setTimeZone(zone)
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selected, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Text(zoneLabel(zone), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickZone = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private fun zoneLabel(zone: TimeZoneChoice): String =
    if (zone.region.isEmpty()) Values.utcOffset(zone.offset) else "${Values.utcOffset(zone.offset)}  ${zone.region}"
