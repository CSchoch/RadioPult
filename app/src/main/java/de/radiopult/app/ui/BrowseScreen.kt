package de.radiopult.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.radiopult.app.NavItem
import de.radiopult.app.RadioViewModel

/** Sender-/Titelliste der aktuellen Quelle (Internetradio, DAB, CD, Musik …). */
@Composable
fun BrowseScreen(vm: RadioViewModel, onBack: () -> Unit) {
    val s = vm.ui
    val b = vm.browse
    val mode = s.mode
    val isDab = mode?.id.equals("DAB", ignoreCase = true)
    var filter by rememberSaveable { mutableStateOf("") }
    var filtering by rememberSaveable { mutableStateOf(false) }
    var searchIn by remember { mutableStateOf<NavItem?>(null) }

    // bei jedem Quellenwechsel von oben neu laden
    LaunchedEffect(s.modeKey) { vm.openBrowser() }
    LaunchedEffect(b.path) {
        filter = ""
        filtering = false
    }

    val goBack = { if (!vm.browseUp()) onBack() }
    val shown = if (filter.isBlank()) b.items else b.items.filter { it.name.contains(filter.trim(), ignoreCase = true) }

    SubScreen(
        vm,
        mode?.listTitle ?: "Liste",
        onBack = goBack,
        actions = {
            if (b.items.size > 10) {
                IconButton(onClick = {
                    filtering = !filtering
                    if (!filtering) filter = ""
                }) { Icon(Icons.Filled.FilterList, contentDescription = "Liste filtern") }
            }
            if (isDab) {
                IconButton(onClick = vm::dabScan, enabled = !b.scanning) {
                    Icon(Icons.Filled.SettingsInputAntenna, contentDescription = "Sendersuchlauf")
                }
            }
            IconButton(onClick = vm::openBrowser, enabled = !b.loading) {
                Icon(Icons.Filled.Refresh, contentDescription = "Neu laden")
            }
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (b.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (b.path.isNotEmpty()) {
                Text(
                    b.path.joinToString(" › "),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (filtering) {
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    placeholder = { Text("Filtern …") },
                    singleLine = true,
                    trailingIcon = {
                        if (filter.isNotEmpty()) {
                            IconButton(onClick = { filter = "" }) { Icon(Icons.Filled.Clear, contentDescription = "Leeren") }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            when {
                b.scanning -> Column(
                    Modifier.fillMaxWidth().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.padding(8.dp))
                    HintText("Sendersuchlauf läuft … das kann ein bis zwei Minuten dauern.")
                }

                b.error != null -> Column(Modifier.padding(16.dp)) {
                    HintText(b.error)
                    TextButton(onClick = vm::openBrowser) { Text("Erneut versuchen") }
                }

                !b.loading && b.items.isEmpty() -> Column(Modifier.padding(16.dp)) {
                    HintText("Die Liste ist leer.")
                    if (isDab) {
                        Spacer(Modifier.padding(4.dp))
                        Button(onClick = vm::dabScan) { Text("Sendersuchlauf starten") }
                    }
                }

                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(shown, key = { it.key }) { item ->
                        NavRow(
                            item = item,
                            playing = !item.folder && !item.search && item.name.equals(s.station, ignoreCase = true),
                            enabled = !b.loading,
                            onClick = { if (item.search) searchIn = item else vm.openItem(item) },
                        )
                    }
                }
            }
        }
    }

    searchIn?.let { item ->
        SearchDialog(
            title = item.name,
            onDismiss = { searchIn = null },
            onSearch = { term ->
                searchIn = null
                vm.search(item, term)
            },
        )
    }
}

@Composable
private fun NavRow(item: NavItem, playing: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (playing) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                when {
                    item.folder -> Icons.Filled.Folder
                    item.search -> Icons.Filled.Search
                    playing -> Icons.Filled.Equalizer
                    else -> Icons.Filled.MusicNote
                },
                contentDescription = null,
                tint = if (playing || item.folder) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                item.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (item.folder || item.search) {
                Box { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) }
            }
        }
    }
}

@Composable
private fun SearchDialog(title: String, onDismiss: () -> Unit, onSearch: (String) -> Unit) {
    var term by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = term,
                onValueChange = { term = it },
                label = { Text("Suchbegriff") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (term.isNotBlank()) onSearch(term.trim()) }),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSearch(term.trim()) }, enabled = term.isNotBlank()) { Text("Suchen") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
