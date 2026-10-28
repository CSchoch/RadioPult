package de.radiopult.app.ui

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.SpeakerGroup
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.radiopult.app.MrDevice
import de.radiopult.app.MrGroup
import de.radiopult.app.R
import de.radiopult.app.RadioViewModel
import kotlin.math.roundToInt

/** Multiroom-Gruppen (mehrere Radios spielen dasselbe) und Stereopaare (zwei Radios als links/rechts). */
@Composable
fun MultiroomScreen(vm: RadioViewModel, onBack: () -> Unit) {
    LaunchedEffect(Unit) { vm.loadMultiroom() }
    val mr = vm.multiroom
    val s = mr.ui
    var creating by rememberSaveable { mutableStateOf(false) }
    var pairing by rememberSaveable { mutableStateOf(false) }
    var addingTo by remember { mutableStateOf<MrGroup?>(null) }

    SubScreen(
        vm,
        stringResource(R.string.multiroom),
        onBack,
        actions = {
            IconButton(onClick = vm::loadMultiroom) {
                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.refresh))
            }
        },
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (s.busy || (s.loading && s.supported == null)) LinearProgressIndicator(Modifier.fillMaxWidth())
            when (s.supported) {
                null -> if (!s.loading) HintText(stringResource(R.string.multiroom_unknown))
                false -> HintText(stringResource(R.string.multiroom_unsupported))
                true -> {
                    HintText(stringResource(R.string.multiroom_hint))
                    s.groups.forEach { g ->
                        GroupCard(
                            g,
                            s.volumeMax,
                            canAdd = s.ungrouped.isNotEmpty() && g.clients.size < s.maxClients,
                            onAdd = { addingTo = g },
                            onRemove = { mr.removeFromGroup(g, it) },
                            onDelete = { mr.deleteGroup(g) },
                            onMaster = { mr.setMasterVolume(g, it) },
                            onMember = { d, v -> mr.setMemberVolume(g, d, v) },
                        )
                    }
                    if (s.ungrouped.isNotEmpty()) {
                        Text(stringResource(R.string.multiroom_single), style = MaterialTheme.typography.titleSmall)
                        s.ungrouped.forEach { d ->
                            DeviceRow(d) {
                                if (d.stereoRole == 1) {
                                    IconButton(onClick = { mr.swapStereo(d) }) {
                                        Icon(Icons.Filled.SwapHoriz, contentDescription = stringResource(R.string.stereo_swap))
                                    }
                                    TextButton(onClick = { mr.unpairStereo(d) }) { Text(stringResource(R.string.stereo_unpair)) }
                                }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { creating = true }, enabled = s.ungrouped.size >= 2 && !s.busy) {
                            Icon(Icons.Filled.SpeakerGroup, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.multiroom_new_group))
                        }
                        val stereoCandidates = s.ungrouped.filter { it.udn in s.stereoCapable && it.stereoRole == 0 }
                        if (stereoCandidates.size >= 2) {
                            OutlinedButton(onClick = { pairing = true }, enabled = !s.busy) {
                                Text(stringResource(R.string.stereo_pair))
                            }
                        }
                    }
                }
            }
            if (s.loading && s.supported != null) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (creating) {
        CreateGroupDialog(
            devices = s.ungrouped,
            onDismiss = { creating = false },
            onCreate = { name, server, members ->
                creating = false
                mr.createGroup(name, server, members)
            },
        )
    }
    addingTo?.let { g ->
        AlertDialog(
            onDismissRequest = { addingTo = null },
            title = { Text(stringResource(R.string.multiroom_add_to, g.name)) },
            text = {
                Column {
                    s.ungrouped.forEach { d ->
                        Text(
                            d.name,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    addingTo = null
                                    mr.addToGroup(g, d)
                                }
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { addingTo = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (pairing) {
        StereoDialog(
            devices = s.ungrouped.filter { it.udn in s.stereoCapable && it.stereoRole == 0 },
            onDismiss = { pairing = false },
            onPair = { name, primary, secondary, left ->
                pairing = false
                mr.pairStereo(name, primary, secondary, left)
            },
        )
    }
}

@Composable
private fun GroupCard(
    g: MrGroup,
    volumeMax: Int,
    canAdd: Boolean,
    onAdd: () -> Unit,
    onRemove: (MrDevice) -> Unit,
    onDelete: () -> Unit,
    onMaster: (Int) -> Unit,
    onMember: (MrDevice, Int) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.SpeakerGroup, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(g.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = onDelete) { Text(stringResource(R.string.multiroom_delete_group)) }
            }
            g.masterVolume?.let { v ->
                Text(stringResource(R.string.multiroom_all_volume), style = MaterialTheme.typography.labelLarge)
                VolumeSlider(v, volumeMax, onMaster)
            }
            g.members.forEach { d ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(d.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (d.server) HintText(stringResource(R.string.multiroom_server))
                    }
                    IconButton(onClick = { onRemove(d) }) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.multiroom_remove, d.name))
                    }
                }
                g.volumes[d.udn]?.let { v -> VolumeSlider(v, volumeMax) { onMember(d, it) } }
            }
            if (canAdd) {
                TextButton(onClick = onAdd) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.multiroom_add))
                }
            }
        }
    }
}

@Composable
private fun VolumeSlider(value: Int, max: Int, onChange: (Int) -> Unit) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    Slider(
        value = (dragging ?: value.toFloat()).coerceIn(0f, max.toFloat()),
        onValueChange = { dragging = it },
        onValueChangeFinished = {
            dragging?.let { onChange(it.roundToInt()) }
            dragging = null
        },
        valueRange = 0f..max.toFloat(),
    )
}

@Composable
private fun DeviceRow(d: MrDevice, actions: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Speaker, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(d.name + if (d.current) " · " + stringResource(R.string.multiroom_this_radio) else "", style = MaterialTheme.typography.bodyLarge)
            if (d.stereoRole == 1) HintText(stringResource(R.string.stereo_paired_as, d.stereoName.ifEmpty { d.name }))
        }
        actions()
    }
}

@Composable
private fun CreateGroupDialog(devices: List<MrDevice>, onDismiss: () -> Unit, onCreate: (String, MrDevice, List<MrDevice>) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var serverUdn by rememberSaveable { mutableStateOf((devices.firstOrNull { it.current } ?: devices.first()).udn) }
    var members by remember { mutableStateOf(devices.filter { it.udn != serverUdn }.map { it.udn }.toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.multiroom_new_group)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(32) },
                    label = { Text(stringResource(R.string.name)) },
                    singleLine = true,
                )
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.multiroom_choose_server), style = MaterialTheme.typography.titleSmall)
                devices.forEach { d ->
                    Row(
                        Modifier.fillMaxWidth().clickable { serverUdn = d.udn; members = members - d.udn },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = d.udn == serverUdn, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(d.name, modifier = Modifier.padding(vertical = 10.dp))
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.multiroom_choose_members), style = MaterialTheme.typography.titleSmall)
                devices.filter { it.udn != serverUdn }.forEach { d ->
                    val checked = d.udn in members
                    Row(
                        Modifier.fillMaxWidth().clickable { members = if (checked) members - d.udn else members + d.udn },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null)
                        Spacer(Modifier.width(8.dp))
                        Text(d.name, modifier = Modifier.padding(vertical = 10.dp))
                    }
                }
            }
        },
        confirmButton = {
            val server = devices.firstOrNull { it.udn == serverUdn }
            TextButton(
                onClick = { server?.let { onCreate(name, it, devices.filter { d -> d.udn in members }) } },
                enabled = name.isNotBlank() && server != null && members.isNotEmpty(),
            ) { Text(stringResource(R.string.multiroom_create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StereoDialog(devices: List<MrDevice>, onDismiss: () -> Unit, onPair: (String, MrDevice, MrDevice, Boolean) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var leftUdn by rememberSaveable { mutableStateOf(devices.first().udn) }
    var rightUdn by rememberSaveable { mutableStateOf(devices[1].udn) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.stereo_pair)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HintText(stringResource(R.string.stereo_hint))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(32) },
                    label = { Text(stringResource(R.string.name)) },
                    singleLine = true,
                )
                Text(stringResource(R.string.stereo_left), style = MaterialTheme.typography.titleSmall)
                SpeakerChoice(devices, leftUdn) { leftUdn = it; if (rightUdn == it) rightUdn = devices.first { d -> d.udn != it }.udn }
                Text(stringResource(R.string.stereo_right), style = MaterialTheme.typography.titleSmall)
                SpeakerChoice(devices.filter { it.udn != leftUdn }, rightUdn) { rightUdn = it }
            }
        },
        confirmButton = {
            val left = devices.firstOrNull { it.udn == leftUdn }
            val right = devices.firstOrNull { it.udn == rightUdn }
            TextButton(
                // das linke Radio wird Hauptlautsprecher; es steuert danach das Paar
                onClick = { if (left != null && right != null) onPair(name, left, right, true) },
                enabled = name.isNotBlank() && left != null && right != null && left.udn != right.udn,
            ) { Text(stringResource(R.string.stereo_pair_action)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpeakerChoice(devices: List<MrDevice>, selected: String, onSelect: (String) -> Unit) {
    if (devices.size <= 3) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            devices.forEachIndexed { i, d ->
                SegmentedButton(
                    selected = d.udn == selected,
                    onClick = { onSelect(d.udn) },
                    shape = SegmentedButtonDefaults.itemShape(i, devices.size),
                ) { Text(d.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    } else {
        devices.forEach { d ->
            Row(Modifier.fillMaxWidth().clickable { onSelect(d.udn) }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = d.udn == selected, onClick = null)
                Spacer(Modifier.width(8.dp))
                Text(d.name, modifier = Modifier.padding(vertical = 10.dp))
            }
        }
    }
}
