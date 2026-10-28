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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import de.radiopult.app.R
import de.radiopult.app.RadioViewModel
import de.radiopult.app.fsapi.Values
import de.radiopult.app.localmusic.LocalPlayer
import de.radiopult.app.localmusic.LocalPlayerUi
import de.radiopult.app.localmusic.MusicLibrary
import de.radiopult.app.localmusic.Track

private enum class LibraryView { SONGS, ALBUMS, ARTISTS }

/** Musik vom Handy auf dem Radio abspielen (UNDOK: „Lokale Musik“). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalMusicScreen(vm: RadioViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    var tracks by remember { mutableStateOf<List<Track>?>(null) }
    LaunchedEffect(granted) { if (granted) tracks = MusicLibrary.tracks(context) }

    var view by rememberSaveable { mutableStateOf(LibraryView.SONGS) }
    // geöffnetes Album bzw. geöffneter Künstler
    var group by rememberSaveable { mutableStateOf<String?>(null) }
    var filter by rememberSaveable { mutableStateOf("") }
    val goBack = { if (group != null) group = null else onBack() }

    val player = LocalPlayer.ui
    val radioName = vm.ui.name.ifEmpty { vm.current?.name.orEmpty() }
    SubScreen(
        vm,
        group ?: stringResource(R.string.local_music),
        onBack = goBack,
        bottomBar = { if (player.active || player.error != null) LocalMiniPlayer(player) },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            when {
                !granted -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    HintText(stringResource(R.string.local_music_permission))
                    Button(onClick = { ask.launch(permission) }) { Text(stringResource(R.string.local_music_allow)) }
                }

                tracks == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

                tracks.orEmpty().isEmpty() -> HintText(stringResource(R.string.local_music_empty), Modifier.padding(16.dp))

                else -> {
                    val all = tracks.orEmpty()
                    HintText(stringResource(R.string.local_music_hint, radioName), Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    if (group == null) {
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                            LibraryView.entries.forEachIndexed { i, v ->
                                SegmentedButton(
                                    selected = view == v,
                                    onClick = { view = v },
                                    shape = SegmentedButtonDefaults.itemShape(i, LibraryView.entries.size),
                                ) {
                                    Text(
                                        stringResource(
                                            when (v) {
                                                LibraryView.SONGS -> R.string.local_songs
                                                LibraryView.ALBUMS -> R.string.local_albums
                                                LibraryView.ARTISTS -> R.string.local_artists
                                            },
                                        ),
                                    )
                                }
                            }
                        }
                    }
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
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    val term = filter.trim()
                    fun Track.matches() = term.isEmpty() || listOf(title, artist, album).any { it.contains(term, ignoreCase = true) }
                    val g = group
                    when {
                        g != null || view == LibraryView.SONGS -> {
                            val list = all.filter { t ->
                                (g == null || (if (view == LibraryView.ALBUMS) t.album else t.artist) == g) && t.matches()
                            }
                            TrackList(list, player, onPlay = { vm.playLocal(list, it) })
                        }

                        else -> {
                            val key: (Track) -> String = if (view == LibraryView.ALBUMS) Track::album else Track::artist
                            val groups = all.filter { it.matches() }.groupBy(key).filterKeys { it.isNotBlank() }.toList()
                            GroupList(groups, album = view == LibraryView.ALBUMS, onOpen = {
                                group = it
                                filter = ""
                            })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackList(tracks: List<Track>, player: LocalPlayerUi, onPlay: (Int) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        itemsIndexed(tracks, key = { _, t -> t.id }) { i, t ->
            val playing = player.active && player.track?.id == t.id
            LibraryRow(
                icon = if (playing) Icons.Filled.GraphicEq else Icons.Filled.MusicNote,
                title = t.title,
                subtitle = listOf(t.artist, Values.duration(t.durationMs)).filter { it.isNotEmpty() }.joinToString(" · "),
                index = i,
                last = tracks.lastIndex,
                highlighted = playing,
                onClick = { onPlay(i) },
            )
        }
    }
}

@Composable
private fun GroupList(groups: List<Pair<String, List<Track>>>, album: Boolean, onOpen: (String) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        itemsIndexed(groups, key = { _, g -> g.first }) { i, (name, list) ->
            LibraryRow(
                icon = if (album) Icons.Filled.Album else Icons.Filled.Person,
                title = name,
                subtitle = pluralStringResource(R.plurals.local_track_count, list.size, list.size) +
                    if (album) list.first().artist.takeIf { it.isNotEmpty() }?.let { " · $it" }.orEmpty() else "",
                index = i,
                last = groups.lastIndex,
                highlighted = false,
                chevron = true,
                onClick = { onOpen(name) },
            )
        }
    }
}

@Composable
private fun LibraryRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    index: Int,
    last: Int,
    highlighted: Boolean,
    chevron: Boolean = false,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val shape = groupShape(index, last)
    Surface(
        shape = shape,
        color = if (highlighted) cs.primaryContainer else cs.surfaceContainer,
        modifier = Modifier.fillMaxWidth().clip(shape).clickable(onClick = onClick),
    ) {
        Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = if (highlighted) cs.primary else cs.onSurfaceVariant)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotEmpty()) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (chevron) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = cs.onSurfaceVariant)
        }
    }
}

/** Was vom Handy läuft, mit vor/zurück und Beenden. */
@Composable
private fun LocalMiniPlayer(p: LocalPlayerUi) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                val t = p.track
                Text(
                    p.error ?: t?.title.orEmpty(),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (p.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (p.error == null) {
                    Text(
                        stringResource(R.string.local_on_radio, p.radioName),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (p.active) {
                IconButton(onClick = LocalPlayer::previous) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = stringResource(R.string.previous))
                }
                if (p.starting) {
                    CircularProgressIndicator(Modifier.size(40.dp).padding(8.dp))
                } else {
                    FilledIconButton(onClick = LocalPlayer::toggle) {
                        Icon(
                            if (p.playing) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                            contentDescription = stringResource(if (p.playing) R.string.stop else R.string.play),
                        )
                    }
                }
                IconButton(onClick = LocalPlayer::next, enabled = p.hasNext) {
                    Icon(Icons.Filled.SkipNext, contentDescription = stringResource(R.string.skip_next))
                }
            }
            IconButton(onClick = LocalPlayer::stop) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.local_stop))
            }
        }
    }
}
