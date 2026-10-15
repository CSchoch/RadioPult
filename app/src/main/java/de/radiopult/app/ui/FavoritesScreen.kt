package de.radiopult.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.radiopult.app.Preset
import de.radiopult.app.RadioViewModel

/** Favoriten der aktuellen Quelle bearbeiten: aktuellen Sender speichern, löschen, Reihenfolge ändern. */
@Composable
fun FavoritesScreen(vm: RadioViewModel, onBack: () -> Unit) {
    val s = vm.ui
    val modeLabel = s.mode?.label.orEmpty()
    var confirmStore by remember { mutableStateOf<Preset?>(null) }
    var confirmDelete by remember { mutableStateOf<Preset?>(null) }

    SubScreen(vm, if (modeLabel.isEmpty()) "Favoriten" else "Favoriten – $modeLabel", onBack) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                HintText(
                    if (s.station.isEmpty()) "Es läuft gerade kein Sender, der gespeichert werden könnte."
                    else "Läuft gerade: ${s.station}\nTippe bei einem Platz auf ⋮, um diesen Sender dort zu speichern.",
                )
            }
            if (s.presets.isEmpty()) {
                item { HintText("Für diese Quelle meldet das Radio keine Speicherplätze.") }
            }
            items(s.presets, key = { it.key }) { slot ->
                val index = s.presets.indexOf(slot)
                PresetSlot(
                    slot = slot,
                    canStore = s.station.isNotEmpty(),
                    canMoveUp = index > 0,
                    canMoveDown = index < s.presets.lastIndex,
                    onStore = { if (slot.empty) vm.storePreset(slot) else confirmStore = slot },
                    onDelete = { confirmDelete = slot },
                    onMove = { delta -> vm.movePreset(slot, delta) },
                )
            }
        }
    }

    confirmStore?.let { slot ->
        AlertDialog(
            onDismissRequest = { confirmStore = null },
            title = { Text("Platz ${slot.key + 1} überschreiben?") },
            text = { Text("„${slot.name}“ wird durch „${s.station}“ ersetzt.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.storePreset(slot)
                    confirmStore = null
                }) { Text("Überschreiben") }
            },
            dismissButton = { TextButton(onClick = { confirmStore = null }) { Text("Abbrechen") } },
        )
    }
    confirmDelete?.let { slot ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Favorit löschen?") },
            text = { Text("„${slot.name}“ wird von Platz ${slot.key + 1} entfernt.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deletePreset(slot)
                    confirmDelete = null
                }) { Text("Löschen", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun PresetSlot(
    slot: Preset,
    canStore: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onStore: () -> Unit,
    onDelete: () -> Unit,
    onMove: (Int) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),
    ) {
        Row(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "${slot.key + 1}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                if (slot.empty) "frei" else slot.name,
                style = MaterialTheme.typography.bodyLarge,
                fontStyle = if (slot.empty) FontStyle.Italic else FontStyle.Normal,
                color = if (slot.empty) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { onMove(-1) }, enabled = canMoveUp && !slot.empty) {
                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Nach oben")
            }
            IconButton(onClick = { onMove(1) }, enabled = canMoveDown && !slot.empty) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Nach unten")
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Mehr")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Aktuellen Sender hier speichern") },
                        leadingIcon = { Icon(Icons.Filled.Save, contentDescription = null) },
                        enabled = canStore,
                        onClick = {
                            menu = false
                            onStore()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Löschen") },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                        enabled = !slot.empty,
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
