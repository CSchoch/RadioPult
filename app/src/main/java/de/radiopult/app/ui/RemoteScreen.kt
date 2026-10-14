package de.radiopult.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.radiopult.app.Mode
import de.radiopult.app.Preset
import de.radiopult.app.RadioUi
import de.radiopult.app.RadioViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteScreen(vm: RadioViewModel) {
    val s = vm.ui
    Scaffold(
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zur Geräteliste")
                    }
                },
                actions = {
                    IconButton(onClick = vm::togglePower) {
                        Icon(
                            Icons.Filled.PowerSettingsNew,
                            contentDescription = if (s.power) "Ausschalten" else "Einschalten",
                            tint = if (s.power) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
            s.error?.let { ErrorBanner(it) }

            when {
                !s.connected && s.error == null -> Box(
                    Modifier.fillMaxWidth().padding(48.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                !s.power -> StandbyCard(onPowerOn = vm::togglePower)

                else -> {
                    NowPlayingCard(s, onPrev = vm::previous, onPlayPause = vm::playPause, onNext = vm::next)
                    VolumeRow(s, onVolume = vm::setVolume, onMute = vm::toggleMute)
                    if (s.modes.isNotEmpty()) SourcesRow(s, onSelect = vm::selectMode)
                    PresetsSection(s, onSelect = vm::selectPreset)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ErrorBanner(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun StandbyCard(onPowerOn: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Filled.PowerSettingsNew,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Text("Das Radio ist im Standby", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onPowerOn) { Text("Einschalten") }
        }
    }
}

@Composable
private fun NowPlayingCard(
    s: RadioUi,
    onPrev: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
) {
    val modeLabel = s.modes.firstOrNull { it.key == s.modeKey }?.label.orEmpty()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Cover(s.coverUrl, Modifier.size(96.dp).clip(RoundedCornerShape(14.dp)))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    if (modeLabel.isNotEmpty()) {
                        Text(
                            modeLabel.uppercase(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(
                        s.station.ifEmpty { "—" },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (s.artist.isNotEmpty()) {
                        Text(s.artist, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (s.text.isNotEmpty()) {
                        Text(
                            s.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onPrev) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "Zurück", modifier = Modifier.size(32.dp))
                }
                FilledIconButton(onClick = onPlayPause, modifier = Modifier.size(64.dp)) {
                    Icon(
                        if (s.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (s.playing) "Pause" else "Abspielen",
                        modifier = Modifier.size(36.dp),
                    )
                }
                IconButton(onClick = onNext) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "Weiter", modifier = Modifier.size(32.dp))
                }
            }
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
                contentDescription = if (s.muted) "Ton an" else "Stumm",
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
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun SourcesRow(s: RadioUi, onSelect: (Mode) -> Unit) {
    Column {
        Text("Quelle", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(s.modes, key = { it.key }) { mode ->
                FilterChip(
                    selected = mode.key == s.modeKey,
                    onClick = { onSelect(mode) },
                    label = { Text(mode.label) },
                )
            }
        }
    }
}

@Composable
private fun PresetsSection(s: RadioUi, onSelect: (Preset) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Favoriten", style = MaterialTheme.typography.titleMedium)
        if (s.presets.isEmpty()) {
            Text(
                "Für diese Quelle sind am Radio keine Favoriten gespeichert.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        s.presets.forEach { preset ->
            val active = preset.name.equals(s.station, ignoreCase = true)
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (active) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onSelect(preset) },
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "${preset.key + 1}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(preset.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun Cover(url: String?, modifier: Modifier) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, url) {
        value = url?.let { loadBitmap(it) }
    }
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
