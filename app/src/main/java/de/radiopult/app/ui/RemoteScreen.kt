package de.radiopult.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.radiopult.app.Mode
import de.radiopult.app.Preset
import de.radiopult.app.R
import de.radiopult.app.RadioUi
import de.radiopult.app.RadioViewModel
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Unterseiten der Fernbedienung. */
private enum class Page { REMOTE, SETTINGS, LANGUAGE, SLEEP, CLOCK, FAVORITES, BROWSE }

@Composable
fun RemoteScreen(vm: RadioViewModel) {
    var page by rememberSaveable { mutableStateOf(Page.REMOTE) }
    var showEq by remember { mutableStateOf(false) }
    val openEq = {
        showEq = true
        vm.loadEqualizer()
    }
    // nach dem Trennen oder im Standby zurück zur Fernbedienung
    val s = vm.ui
    val available = s.connected && s.power
    LaunchedEffect(available) { if (!available && page != Page.SETTINGS) page = Page.REMOTE }

    when (page) {
        Page.REMOTE -> RemoteMain(
            vm,
            onEqualizer = openEq,
            onSettings = { page = Page.SETTINGS },
            onFavorites = { page = Page.FAVORITES },
            onBrowse = { page = Page.BROWSE },
        )
        Page.SETTINGS -> SettingsScreen(
            vm,
            onBack = { page = Page.REMOTE },
            onLanguage = { page = Page.LANGUAGE },
            onSleep = { page = Page.SLEEP },
            onEqualizer = openEq,
            onClock = { page = Page.CLOCK },
        )
        Page.LANGUAGE -> LanguageScreen(vm, onBack = { page = Page.SETTINGS })
        Page.SLEEP -> SleepScreen(vm, onBack = { page = Page.SETTINGS })
        Page.CLOCK -> ClockScreen(vm, onBack = { page = Page.SETTINGS })
        Page.FAVORITES -> FavoritesScreen(vm, onBack = { page = Page.REMOTE })
        Page.BROWSE -> BrowseScreen(vm, onBack = { page = Page.REMOTE })
    }

    if (showEq) {
        EqualizerSheet(
            eq = vm.eq,
            onDismiss = { showEq = false },
            onRetry = vm::loadEqualizer,
            onPreset = vm::selectEqPreset,
            onBand = vm::setEqBand,
            onLoudness = vm::toggleLoudness,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemoteMain(
    vm: RadioViewModel,
    onEqualizer: () -> Unit,
    onSettings: () -> Unit,
    onFavorites: () -> Unit,
    onBrowse: () -> Unit,
) {
    val s = vm.ui
    val snackbar = remember { SnackbarHostState() }
    val notice = vm.notice
    LaunchedEffect(notice) {
        if (notice != null) {
            vm.consumeNotice()
            snackbar.showSnackbar(notice)
        }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        s.name.ifEmpty { vm.current?.name.orEmpty() },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = vm::disconnect) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back_to_devices))
                    }
                },
                actions = {
                    if (s.connected && s.power) {
                        IconButton(onClick = onEqualizer) {
                            Icon(Icons.Filled.Tune, contentDescription = stringResource(R.string.equalizer))
                        }
                    }
                    if (s.connected) {
                        IconButton(onClick = onSettings) {
                            Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.settings))
                        }
                    }
                    if (s.connected && s.power) {
                        FilledTonalIconButton(onClick = vm::togglePower) {
                            Icon(Icons.Filled.PowerSettingsNew, contentDescription = stringResource(R.string.power_off))
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                },
            )
        },
    ) { pad ->
        when {
            !s.connected && s.error == null -> Box(
                Modifier.fillMaxSize().padding(pad),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            !s.power -> StandbyPane(Modifier.padding(pad), s.error, onPowerOn = vm::togglePower)

            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(pad)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                s.error?.let { ErrorBanner(it) }
                PlayerCard(
                    s,
                    onPrev = vm::previous,
                    onPlayStop = vm::playStop,
                    onNext = vm::next,
                    onVolume = vm::setVolume,
                    onMute = vm::toggleMute,
                )
                if (s.modes.isNotEmpty()) {
                    SourcesRow(s, onSelect = vm::selectMode, onBrowse = onBrowse)
                }
                PresetsGrid(s, onSelect = vm::selectPreset, onEdit = onFavorites)
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun ErrorBanner(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

/** Standby füllt die Seite: ein großer Einschaltknopf in der Mitte statt einer kleinen Karte oben. */
@Composable
private fun StandbyPane(modifier: Modifier, error: String?, onPowerOn: () -> Unit) {
    Column(
        modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        error?.let { ErrorBanner(it); Spacer(Modifier.height(24.dp)) }
        FilledIconButton(
            onClick = onPowerOn,
            modifier = Modifier.size(112.dp),
            shape = RoundedCornerShape(36.dp),
        ) {
            Icon(Icons.Filled.PowerSettingsNew, contentDescription = stringResource(R.string.power_on), modifier = Modifier.size(48.dp))
        }
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.standby), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.standby_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Alles, was zum laufenden Sender gehört, in einer Fläche: Anzeige, Transport und Lautstärke. */
@Composable
private fun PlayerCard(
    s: RadioUi,
    onPrev: () -> Unit,
    onPlayStop: () -> Unit,
    onNext: () -> Unit,
    onVolume: (Int) -> Unit,
    onMute: () -> Unit,
) {
    val modeLabel = s.modes.firstOrNull { it.key == s.modeKey }?.label.orEmpty()
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Cover(s.coverUrl, Modifier.size(112.dp).clip(RoundedCornerShape(20.dp)))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    if (modeLabel.isNotEmpty()) {
                        Text(
                            modeLabel,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(2.dp))
                    }
                    Text(
                        s.station.ifEmpty { "—" },
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (s.artist.isNotEmpty()) {
                        Text(
                            s.artist,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (s.text.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    s.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(20.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalIconButton(onClick = onPrev, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = stringResource(R.string.previous), modifier = Modifier.size(28.dp))
                }
                FilledIconButton(
                    onClick = onPlayStop,
                    modifier = Modifier.width(112.dp).height(72.dp),
                    shape = RoundedCornerShape(24.dp),
                ) {
                    Icon(
                        if (s.playing) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                        contentDescription = if (s.playing) stringResource(R.string.stop) else stringResource(R.string.play),
                        modifier = Modifier.size(36.dp),
                    )
                }
                FilledTonalIconButton(onClick = onNext, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Filled.SkipNext, contentDescription = stringResource(R.string.skip_next), modifier = Modifier.size(28.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
            VolumeRow(s, onVolume, onMute)
        }
    }
}

@Composable
private fun VolumeRow(s: RadioUi, onVolume: (Int) -> Unit, onMute: () -> Unit) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shown = dragging ?: s.volume.toFloat()
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onMute) {
            Icon(
                if (s.muted || s.volume == 0) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = if (s.muted) stringResource(R.string.unmute) else stringResource(R.string.mute),
            )
        }
        Slider(
            value = shown.coerceIn(0f, s.volumeMax.toFloat()),
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { onVolume(it.roundToInt()) }
                dragging = null
            },
            valueRange = 0f..s.volumeMax.toFloat(),
            modifier = Modifier.weight(1f),
        )
        Text(
            shown.roundToInt().toString(),
            modifier = Modifier.width(36.dp),
            textAlign = TextAlign.End,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun SectionHeader(title: String, action: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        action()
    }
}

/** Quelle und ihre Liste gehören zusammen: der Listen-Knopf sitzt an der Überschrift statt als breiter Balken. */
@Composable
private fun SourcesRow(s: RadioUi, onSelect: (Mode) -> Unit, onBrowse: () -> Unit) {
    Column {
        SectionHeader(stringResource(R.string.source)) {
            s.mode?.takeIf { s.listAvailable }?.let { mode ->
                TextButton(onClick = onBrowse) {
                    Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(mode.listTitle())
                }
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(s.modes, key = { it.key }) { mode ->
                val selected = mode.key == s.modeKey
                FilterChip(
                    selected = selected,
                    onClick = { onSelect(mode) },
                    label = { Text(mode.label) },
                    leadingIcon = if (selected) {
                        { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    } else null,
                )
            }
        }
    }
}

/** Favoriten als Tastenfeld wie am Radio: zwei Spalten, mehr Sender ohne Scrollen sichtbar. */
@Composable
private fun PresetsGrid(s: RadioUi, onSelect: (Preset) -> Unit, onEdit: () -> Unit) {
    val stored = s.presets.filterNot { it.empty }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(stringResource(R.string.favorites)) {
            if (s.presets.isNotEmpty()) {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.edit_favorites))
                }
            }
        }
        if (stored.isEmpty()) {
            Text(
                stringResource(R.string.no_favorites_stored),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        stored.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { preset ->
                    PresetTile(preset, active = preset.name.equals(s.station, ignoreCase = true), onClick = { onSelect(preset) }, Modifier.weight(1f))
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PresetTile(preset: Preset, active: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = if (active) cs.primaryContainer else cs.surfaceContainer,
        contentColor = if (active) cs.onPrimaryContainer else cs.onSurface,
        modifier = modifier.height(72.dp),
    ) {
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(12.dp))
                    .background(if (active) cs.primary else cs.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                if (active) {
                    Icon(Icons.Filled.GraphicEq, contentDescription = stringResource(R.string.now_playing), tint = cs.onPrimary, modifier = Modifier.size(20.dp))
                } else {
                    Text("${preset.key + 1}", style = MaterialTheme.typography.titleSmall, color = cs.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(
                preset.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Cover(url: String?, modifier: Modifier) {
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) { bitmap = url?.let { loadBitmap(it) } }
    Box(modifier.background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
        val b = bitmap
        if (b != null) {
            Image(b, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(
                Icons.Filled.Radio,
                contentDescription = null,
                modifier = Modifier.size(44.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

private suspend fun loadBitmap(url: String): ImageBitmap? = withContext(Dispatchers.IO) {
    runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 4000
        conn.readTimeout = 6000
        try {
            conn.inputStream.use { BitmapFactory.decodeStream(it) }?.asImageBitmap()
        } finally {
            conn.disconnect()
        }
    }.getOrNull()
}
