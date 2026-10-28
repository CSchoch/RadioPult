package de.radiopult.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.radiopult.app.Preset
import de.radiopult.app.R
import de.radiopult.app.RadioViewModel

/** Favoriten der aktuellen Quelle bearbeiten: aktuellen Sender speichern, löschen, Reihenfolge ändern. */
@Composable
fun FavoritesScreen(vm: RadioViewModel, onBack: () -> Unit) {
    val s = vm.ui
    val modeLabel = s.mode?.label.orEmpty()
    var confirmStore by remember { mutableStateOf<Preset?>(null) }
    var confirmDelete by remember { mutableStateOf<Preset?>(null) }
    var editing by remember { mutableStateOf<Preset?>(null) }
    // Name und Adresse ändern nur, wo das Radio es erlaubt (bzw. nichts dazu meldet)
    val editable = s.presetsEditable != false

    SubScreen(vm, if (modeLabel.isEmpty()) stringResource(R.string.favorites) else stringResource(R.string.favorites_for_mode, modeLabel), onBack) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item {
                NowPlayingHint(s.station)
                Spacer(Modifier.height(16.dp))
            }
            if (s.presets.isEmpty()) {
                item { HintText(stringResource(R.string.favorites_no_slots)) }
            }
            items(s.presets, key = { it.key }) { slot ->
                val index = s.presets.indexOf(slot)
                PresetSlot(
                    slot = slot,
                    shape = groupShape(index, s.presets.lastIndex),
                    canStore = s.station.isNotEmpty(),
                    canMoveUp = index > 0,
                    canMoveDown = index < s.presets.lastIndex,
                    onStore = { if (slot.empty) vm.storePreset(slot) else confirmStore = slot },
                    onDelete = { confirmDelete = slot },
                    onMove = { delta -> vm.movePreset(slot, delta) },
                    onEdit = if (!slot.empty && editable) ({ editing = slot }) else null,
                )
            }
        }
    }

    confirmStore?.let { slot ->
        AlertDialog(
            onDismissRequest = { confirmStore = null },
            title = { Text(stringResource(R.string.overwrite_slot_title, slot.key + 1)) },
            text = { Text(stringResource(R.string.overwrite_slot_text, slot.name, s.station)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.storePreset(slot)
                    confirmStore = null
                }) { Text(stringResource(R.string.overwrite)) }
            },
            dismissButton = { TextButton(onClick = { confirmStore = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    editing?.let { slot ->
        EditPresetDialog(
            slot,
            // die Adresse kennen nur Internet-Sender
            showUrl = slot.url.isNotEmpty(),
            onDismiss = { editing = null },
            onSave = { name, url ->
                vm.editPreset(slot, name, url)
                editing = null
            },
        )
    }
    confirmDelete?.let { slot ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.delete_favorite_title)) },
            text = { Text(stringResource(R.string.delete_favorite_text, slot.name, slot.key + 1)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.deletePreset(slot)
                    confirmDelete = null
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun NowPlayingHint(station: String) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.GraphicEq, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    if (station.isEmpty()) stringResource(R.string.favorites_nothing_playing) else station,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    if (station.isEmpty()) stringResource(R.string.favorites_start_station)
                    else stringResource(R.string.favorites_now_playing_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

@Composable
private fun PresetSlot(
    slot: Preset,
    shape: Shape,
    canStore: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onStore: () -> Unit,
    onDelete: () -> Unit,
    onMove: (Int) -> Unit,
    onEdit: (() -> Unit)?,
) {
    var menu by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = shape,
        color = cs.surfaceContainer,
        modifier = Modifier.fillMaxWidth().clip(shape),
    ) {
        Row(
            Modifier.heightIn(min = 64.dp).padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${slot.key + 1}",
                style = MaterialTheme.typography.titleMedium,
                color = cs.primary,
                modifier = Modifier.width(28.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (slot.empty) stringResource(R.string.slot_empty) else slot.name,
                style = MaterialTheme.typography.bodyLarge,
                color = if (slot.empty) cs.onSurfaceVariant else cs.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (slot.empty) {
                if (canStore) {
                    FilledTonalButton(onClick = onStore, modifier = Modifier.padding(end = 8.dp)) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.save))
                    }
                }
            } else {
                IconButton(onClick = { onMove(-1) }, enabled = canMoveUp) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.move_up))
                }
                IconButton(onClick = { onMove(1) }, enabled = canMoveDown) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.move_down))
                }
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.store_current_here)) },
                            leadingIcon = { Icon(Icons.Filled.Save, contentDescription = null) },
                            enabled = canStore,
                            onClick = {
                                menu = false
                                onStore()
                            },
                        )
                        onEdit?.let { edit ->
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.preset_edit)) },
                                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                                onClick = {
                                    menu = false
                                    edit()
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.delete)) },
                            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                            onClick = {
                                menu = false
                                onDelete()
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Name (und bei Internetradio die Stream-Adresse) eines Favoriten ändern. */
@Composable
private fun EditPresetDialog(slot: Preset, showUrl: Boolean, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(slot.name) }
    var url by rememberSaveable { mutableStateOf(slot.url) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.preset_edit_title, slot.key + 1)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(64) },
                    label = { Text(stringResource(R.string.name)) },
                    singleLine = true,
                )
                if (showUrl) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it.trim() },
                        label = { Text(stringResource(R.string.preset_url)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name, url) },
                enabled = name.isNotBlank() && (!showUrl || url.startsWith("http")),
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
