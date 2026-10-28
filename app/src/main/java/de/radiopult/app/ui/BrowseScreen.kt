package de.radiopult.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Radio
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.radiopult.app.ContextMenuUi
import de.radiopult.app.FormField
import de.radiopult.app.Mode
import de.radiopult.app.NavForm
import de.radiopult.app.NavItem
import de.radiopult.app.Preset
import de.radiopult.app.R
import de.radiopult.app.RadioUi
import de.radiopult.app.RadioViewModel
import java.text.DateFormat
import java.util.Date

/** Sender-/Titelliste der aktuellen Quelle (Internetradio, DAB, CD, Musik …). */
@Composable
fun BrowseScreen(vm: RadioViewModel, onBack: () -> Unit) {
    val s = vm.ui
    val b = vm.browse
    val mode = s.mode
    val isDab = mode?.id.equals("DAB", ignoreCase = true)
    val isStationList = isDab || mode?.id.equals("IR", ignoreCase = true)
    val isCd = mode?.id.equals("CD", ignoreCase = true)
    var filter by rememberSaveable { mutableStateOf("") }
    var filtering by rememberSaveable { mutableStateOf(false) }
    var searchIn by remember { mutableStateOf<NavItem?>(null) }
    var favoriteFor by remember { mutableStateOf<NavItem?>(null) }
    var confirmOverwrite by remember { mutableStateOf<Pair<NavItem, Preset>?>(null) }

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
        mode?.listTitle() ?: stringResource(R.string.browse_list),
        onBack = goBack,
        actions = {
            if (b.items.size > 10) {
                IconButton(onClick = {
                    filtering = !filtering
                    if (!filtering) filter = ""
                }) { Icon(Icons.Filled.FilterList, contentDescription = stringResource(R.string.browse_filter)) }
            }
            if (isDab) {
                IconButton(onClick = vm::dabScan, enabled = !b.scanning) {
                    Icon(Icons.Filled.SettingsInputAntenna, contentDescription = stringResource(R.string.browse_scan))
                }
            }
            IconButton(onClick = vm::openBrowser, enabled = !b.loading) {
                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.reload))
            }
        },
        bottomBar = { if (s.station.isNotEmpty()) MiniPlayer(s, onPlayStop = vm::playStop) },
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
                    placeholder = { Text(stringResource(R.string.filter_placeholder)) },
                    singleLine = true,
                    trailingIcon = {
                        if (filter.isNotEmpty()) {
                            IconButton(onClick = { filter = "" }) { Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.clear)) }
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
                    HintText(stringResource(R.string.browse_scanning))
                    b.scanFound?.let { n ->
                        Spacer(Modifier.padding(4.dp))
                        Text(pluralStringResource(R.plurals.browse_scan_found, n, n), style = MaterialTheme.typography.titleMedium)
                    }
                }

                b.error != null -> Column(Modifier.padding(16.dp)) {
                    HintText(b.error)
                    TextButton(onClick = vm::openBrowser) { Text(stringResource(R.string.retry)) }
                }

                // Viele CD-Radios geben die Titel der CD gar nicht über die Schnittstelle heraus.
                !b.loading && b.items.isEmpty() && isCd -> CdWithoutList(onPrev = vm::previous, onNext = vm::next)

                !b.loading && b.items.isEmpty() -> Column(Modifier.padding(16.dp)) {
                    HintText(stringResource(R.string.browse_empty))
                    if (isDab) {
                        Spacer(Modifier.padding(4.dp))
                        Button(onClick = vm::dabScan) { Text(stringResource(R.string.browse_start_scan)) }
                    }
                }

                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (b.description.isNotBlank() || b.releaseDate != null) {
                        item(key = "description") { DescriptionCard(b.description, b.releaseDate) }
                    }
                    itemsIndexed(shown, key = { _, it -> it.key }) { index, item ->
                        val playable = !item.folder && !item.search && !item.form && !item.message
                        NavRow(
                            item = item,
                            shape = groupShape(index, shown.lastIndex),
                            station = isStationList,
                            playing = playable && item.name.equals(s.station, ignoreCase = true),
                            enabled = !b.loading,
                            onClick = { if (item.search) searchIn = item else vm.openItem(item) },
                            // Favoriten nur, wenn das Radio für diese Quelle Speicherplätze meldet
                            favorite = if (playable && s.presets.isNotEmpty()) {
                                s.presets.any { it.name.equals(item.name, ignoreCase = true) }
                            } else {
                                null
                            },
                            onFavorite = { favoriteFor = item },
                            onMore = if (item.contextMenu) ({ vm.openContextMenu(item) }) else null,
                        )
                    }
                }
            }
        }
    }

    favoriteFor?.let { item ->
        PresetPickerDialog(
            item = item,
            presets = s.presets,
            onDismiss = { favoriteFor = null },
            onPick = { slot ->
                favoriteFor = null
                if (slot.empty) vm.storeItemAsPreset(item, slot) else confirmOverwrite = item to slot
            },
        )
    }
    confirmOverwrite?.let { (item, slot) ->
        AlertDialog(
            onDismissRequest = { confirmOverwrite = null },
            title = { Text(stringResource(R.string.overwrite_slot_title, slot.key + 1)) },
            text = { Text(stringResource(R.string.overwrite_slot_text, slot.name, item.name)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.storeItemAsPreset(item, slot)
                    confirmOverwrite = null
                }) { Text(stringResource(R.string.overwrite)) }
            },
            dismissButton = { TextButton(onClick = { confirmOverwrite = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    b.contextMenu?.let { menu ->
        ContextMenuDialog(menu, onChoose = vm::chooseContextItem, onDismiss = vm::closeContextMenu)
    }
    b.form?.let { form ->
        FormDialog(form, busy = b.loading, onSubmit = vm::submitForm, onDismiss = vm::closeForm)
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
private fun NavRow(
    item: NavItem,
    shape: Shape,
    station: Boolean,
    playing: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    favorite: Boolean?,
    onFavorite: () -> Unit,
    onMore: (() -> Unit)?,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = shape,
        color = if (playing) cs.primaryContainer else cs.surfaceContainer,
        contentColor = if (playing) cs.onPrimaryContainer else cs.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            // Hinweise des Radios sind nur Text
            .clickable(enabled = enabled && !item.message, onClick = onClick),
    ) {
        Row(
            Modifier
                .heightIn(min = 56.dp)
                .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = if (favorite != null || onMore != null) 4.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                when {
                    playing -> Icons.Filled.GraphicEq
                    item.folder -> Icons.Outlined.Folder
                    item.search -> Icons.Filled.Search
                    item.form -> Icons.AutoMirrored.Filled.Login
                    item.message -> Icons.Outlined.Info
                    station -> Icons.Outlined.Radio
                    else -> Icons.Filled.MusicNote
                },
                contentDescription = if (playing) stringResource(R.string.now_playing) else null,
                tint = if (playing) cs.primary else cs.onSurfaceVariant,
            )
            Spacer(Modifier.width(16.dp))
            Text(
                item.name,
                style = if (item.message) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                fontStyle = if (item.message) FontStyle.Italic else FontStyle.Normal,
                maxLines = if (item.message) 6 else 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (item.folder || item.search || item.form) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = cs.onSurfaceVariant)
            }
            if (favorite != null) {
                IconButton(onClick = onFavorite, enabled = enabled) {
                    Icon(
                        if (favorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                        contentDescription = stringResource(R.string.save_as_favorite),
                        tint = if (favorite) cs.primary else cs.onSurfaceVariant,
                    )
                }
            }
            if (onMore != null) {
                IconButton(onClick = onMore, enabled = enabled) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more))
                }
            }
        }
    }
}

/** Text des Radios zum geöffneten Ordner (Album, Künstler, Sendung) – lang, daher aufklappbar. */
@Composable
private fun DescriptionCard(text: String, releaseDate: Long?) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(20.dp)).clickable { expanded = !expanded },
    ) {
        Column(Modifier.padding(16.dp)) {
            releaseDate?.let {
                Text(
                    stringResource(R.string.browse_release_date, DateFormat.getDateInstance().format(Date(it * 1000))),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            if (text.isNotBlank()) {
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (expanded) Int.MAX_VALUE else 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Kontextmenü eines Eintrags (z. B. „Zu Favoriten“, „Folgen“), so wie das Radio es liefert. */
@Composable
private fun ContextMenuDialog(menu: ContextMenuUi, onChoose: (NavItem) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(menu.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when {
                    menu.loading -> Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    menu.error != null -> HintText(menu.error)
                    menu.items.isEmpty() -> HintText(stringResource(R.string.browse_empty))
                    else -> menu.items.forEach { item ->
                        Text(
                            item.name,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(enabled = !item.message) { onChoose(item) }
                                .padding(vertical = 12.dp, horizontal = 4.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

/** Formular des Radios, z. B. Anmeldung bei einem Musikdienst: Textfelder, Auswahl, Knöpfe. */
@Composable
private fun FormDialog(form: NavForm, busy: Boolean, onSubmit: (FormField, Map<String, String>) -> Unit, onDismiss: () -> Unit) {
    var values by remember(form) {
        mutableStateOf(form.fields.filter { it.choice }.associate { f -> f.id to (f.options.firstOrNull()?.key?.toString() ?: "") })
    }
    val buttons = form.fields.filter { it.button }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(form.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                form.fields.forEach { f ->
                    when {
                        f.text -> OutlinedTextField(
                            value = values[f.id].orEmpty(),
                            onValueChange = { values = values + (f.id to it) },
                            label = { Text(f.label) },
                            supportingText = f.description.takeIf { it.isNotBlank() }?.let { { Text(it) } },
                            singleLine = true,
                            visualTransformation = if (f.password) PasswordVisualTransformation() else VisualTransformation.None,
                            keyboardOptions = KeyboardOptions(keyboardType = if (f.password) KeyboardType.Password else KeyboardType.Text),
                            modifier = Modifier.fillMaxWidth(),
                        )

                        f.choice -> Column {
                            Text(f.label, style = MaterialTheme.typography.titleSmall)
                            f.options.forEach { o ->
                                Row(
                                    Modifier.fillMaxWidth().clickable { values = values + (f.id to o.key.toString()) },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(selected = values[f.id] == o.key.toString(), onClick = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(o.label, modifier = Modifier.padding(vertical = 8.dp))
                                }
                            }
                        }

                        f.button -> Unit

                        else -> listOf(f.label, f.description).filter { it.isNotBlank() }.forEach { HintText(it) }
                    }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Row {
                buttons.forEach { button ->
                    TextButton(onClick = { onSubmit(button, values) }, enabled = !busy) { Text(button.label.ifEmpty { stringResource(R.string.ok) }) }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Ersatz für die Titelliste, wenn das Radio sie im CD-Betrieb nicht liefert: Titel vor/zurück. */
@Composable
private fun CdWithoutList(onPrev: () -> Unit, onNext: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        HintText(stringResource(R.string.browse_cd_no_list))
        Spacer(Modifier.padding(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        ) {
            FilledTonalIconButton(onClick = onPrev, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = stringResource(R.string.previous), modifier = Modifier.size(28.dp))
            }
            FilledTonalIconButton(onClick = onNext, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Filled.SkipNext, contentDescription = stringResource(R.string.skip_next), modifier = Modifier.size(28.dp))
            }
        }
    }
}

/** Unten immer sichtbar, was gerade läuft, mit Stopp/Abspielen – auch tief in der Senderliste. */
@Composable
private fun MiniPlayer(s: RadioUi, onPlayStop: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Radio, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(s.station, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (s.artist.isNotEmpty()) {
                    Text(
                        s.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            FilledIconButton(onClick = onPlayStop) {
                Icon(
                    if (s.playing) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                    contentDescription = if (s.playing) stringResource(R.string.stop) else stringResource(R.string.play),
                )
            }
        }
    }
}

/** Speicherplatz wählen, auf dem ein Eintrag der Senderliste als Favorit landet. */
@Composable
private fun PresetPickerDialog(item: NavItem, presets: List<Preset>, onDismiss: () -> Unit, onPick: (Preset) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.save_as_favorite)) },
        text = {
            Column {
                HintText(stringResource(R.string.favorite_pick_hint, item.name))
                Spacer(Modifier.padding(4.dp))
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    presets.forEach { slot ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onPick(slot) }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "${slot.key + 1}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(
                                if (slot.empty) stringResource(R.string.slot_empty) else slot.name,
                                style = MaterialTheme.typography.bodyLarge,
                                fontStyle = if (slot.empty) FontStyle.Italic else FontStyle.Normal,
                                color = if (slot.empty) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
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
                label = { Text(stringResource(R.string.search_term)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (term.isNotBlank()) onSearch(term.trim()) }),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSearch(term.trim()) }, enabled = term.isNotBlank()) { Text(stringResource(R.string.search)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Überschrift der Liste dieser Quelle. */
@Composable
fun Mode.listTitle(): String = listTitleRes?.let { stringResource(it) } ?: label.ifEmpty { stringResource(R.string.browse_default) }
