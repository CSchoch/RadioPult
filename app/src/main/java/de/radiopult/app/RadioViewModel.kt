package de.radiopult.app

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.radiopult.app.fsapi.Discovery
import de.radiopult.app.fsapi.FsApiClient
import de.radiopult.app.fsapi.FsApiException
import de.radiopult.app.fsapi.FsItem
import de.radiopult.app.fsapi.Nodes
import de.radiopult.app.fsapi.PlayCaps
import de.radiopult.app.fsapi.RadioDevice
import de.radiopult.app.fsapi.RadioSetup
import de.radiopult.app.fsapi.Values
import de.radiopult.app.fsapi.WrongPinException
import de.radiopult.app.localmusic.LocalPlayer
import de.radiopult.app.localmusic.Track
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException

data class Mode(val key: Int, val label: String, val id: String = "") {

    /** Meldet das Radio netRemote.nav.caps nicht: Quellen ohne Liste (UKW, AUX, Bluetooth …) am Namen erkennen. */
    val browsable: Boolean
        get() {
            val i = id.uppercase()
            return i != "FM" && NOT_BROWSABLE.none { i.contains(it) }
        }

    /** UPnP-Abspieler („Musik-Abspieler“, DMR): hier spielt das Radio Musik vom Handy. */
    val isDmr get() = id.uppercase().contains("DMR")

    /** Überschrift der Liste dieser Quelle; null, wenn der Name der Quelle passt. */
    @get:StringRes
    val listTitleRes: Int?
        get() = when (id.uppercase()) {
            "IR", "DAB" -> R.string.browse_station_list
            "CD" -> R.string.browse_track_list
            else -> null
        }
}

private val NOT_BROWSABLE = listOf("AUX", "BLUETOOTH", "SPDIF", "OPTICAL", "LINE", "SPOTIFY", "AIRPLAY", "HDMI", "DMR")

/**
 * Speicherplatz für einen Favoriten; [name] ist leer, wenn der Platz frei ist.
 * [type] und [url] liefern nur neuere Radios (für „Name und Adresse ändern“).
 */
data class Preset(val key: Int, val name: String, val type: String = "", val url: String = "") {
    val empty get() = name.isEmpty()
}

/** UKW-Bereich des Radios in kHz. */
data class FmRange(val lower: Int, val upper: Int, val step: Int)

/** Mehr zum laufenden Titel (Infoknopf), wie UNDOK es zeigt. */
data class TrackDetails(
    val album: String = "",
    val description: String = "",
    val artistDescription: String = "",
    val albumDescription: String = "",
    val provider: String = "",
) {
    val empty get() = listOf(album, description, artistDescription, albumDescription, provider).all { it.isBlank() }
}

data class RadioUi(
    val connected: Boolean = false,
    val error: String? = null,
    val name: String = "",
    val power: Boolean = false,
    val modeKey: Int? = null,
    val modes: List<Mode> = emptyList(),
    val volume: Int = 0,
    val volumeMax: Int = 32,
    val muted: Boolean = false,
    val station: String = "",
    val text: String = "",
    val artist: String = "",
    val playing: Boolean = false,
    val coverUrl: String? = null,
    val presets: List<Preset> = emptyList(),
    /** Ob das Radio für die aktuelle Quelle eine Liste anbietet (netRemote.nav.caps); null = meldet es nicht. */
    val navigable: Boolean? = null,
    /** Ob sich Favoriten direkt ändern lassen (Name, Adresse; netRemote.nav.caps Bit 2); null = meldet es nicht. */
    val presetsEditable: Boolean? = null,
    /** Fähigkeiten der laufenden Quelle; null = meldet das Radio nicht (dann alles anbieten). */
    val caps: PlayCaps? = null,
    val shuffle: Boolean? = null,
    /** 0 = aus, 1 = alle, 2 = einen Titel. */
    val repeat: Int? = null,
    val positionMs: Long? = null,
    val durationMs: Long? = null,
    /** Zeitpunkt (SystemClock.elapsedRealtime), zu dem [positionMs] gelesen wurde – zum Weiterzählen. */
    val positionReadAt: Long = 0,
    val album: String = "",
    /** UKW: eingestellte Frequenz in kHz. */
    val frequency: Int? = null,
    val fmRange: FmRange? = null,
    /** Favorit, der laut Radio gerade läuft (netRemote.nav.preset.currentPreset). */
    val currentPreset: Int? = null,
    val details: TrackDetails? = null,
) {
    val mode get() = modes.firstOrNull { it.key == modeKey }

    /** Wie UNDOK der Angabe des Radios folgen; ohne sie nach dem Namen der Quelle raten. */
    val listAvailable get() = navigable ?: mode?.browsable ?: false

    val isFm get() = mode?.id.equals("FM", ignoreCase = true)
    val isBluetooth get() = mode?.id?.contains("BLUETOOTH", ignoreCase = true) == true

    /** Ohne Angabe des Radios zeigen wie bisher; sonst wie UNDOK nur, was die Quelle kann. */
    val canSkip get() = caps == null || caps.bits == 0L || caps.skipNext || caps.skipPrevious
    val canShuffle get() = caps?.shuffle == true && shuffle != null
    val canRepeat get() = caps?.repeat == true && repeat != null
    val canSeek get() = caps?.seek == true && (durationMs ?: 0) > 0

    /** Ist [preset] der laufende Sender? Erst die Angabe des Radios, sonst der Name. */
    fun isPlaying(preset: Preset): Boolean {
        val current = currentPreset
        if (current != null && presets.any { it.key == current }) return preset.key == current
        return preset.name.equals(station, ignoreCase = true)
    }
}

/**
 * Eintrag der Sender-/Titelliste: Ordner, Abspielbares, Suche, Formular oder Hinweis.
 * [contextMenu]: das Radio bietet für den Eintrag ein Kontextmenü an.
 */
data class NavItem(val key: Int, val name: String, val type: Int, val contextMenu: Boolean = false) {
    val folder get() = type == 0
    val search get() = type == 2
    val form get() = type == 4
    val message get() = type == 5
    val playable get() = type == 1
}

/** Feld eines Formulars des Radios (z. B. Anmeldung bei einem Musikdienst). */
data class FormField(
    val key: Int,
    /** 0 Text, 1 Passwort, 2 Auswahl, 3 Knopf, 4 Beschreibung, 5 Abo, 6 Mehrfachauswahl */
    val type: Int,
    val id: String,
    val label: String,
    val description: String,
    val options: List<Choice> = emptyList(),
) {
    val text get() = type == 0 || type == 1
    val password get() = type == 1
    val choice get() = type == 2 || type == 6
    val button get() = type == 3 || type == 5
}

data class NavForm(val title: String, val fields: List<FormField>)

/** Kontextmenü eines Eintrags: eigene kleine Liste, die das Radio dafür aufbaut. */
data class ContextMenuUi(
    val title: String,
    val loading: Boolean = true,
    val items: List<NavItem> = emptyList(),
    val error: String? = null,
)

data class BrowseUi(
    val loading: Boolean = false,
    val scanning: Boolean = false,
    /** Beim DAB-Suchlauf: bisher gefundene Sender (netRemote.nav.dabScanUpdate). */
    val scanFound: Int? = null,
    val path: List<String> = emptyList(),
    val items: List<NavItem> = emptyList(),
    val error: String? = null,
    /** Beschreibung des geöffneten Ordners (z. B. Album- oder Künstlertext) und Erscheinungsdatum. */
    val description: String = "",
    val releaseDate: Long? = null,
    val form: NavForm? = null,
    val contextMenu: ContextMenuUi? = null,
)

data class Choice(val key: Int, val label: String)

/** Eintrag der Zeitzonenliste des Radios: Abstand zu UTC in Sekunden und Name der Region. */
data class TimeZoneChoice(val key: Int, val offset: Int, val region: String)

data class SettingsUi(
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val model: String? = null,
    val version: String? = null,
    val ip: String? = null,
    val mac: String? = null,
    val network: String? = null,
    val signal: Int? = null,
    val languages: List<Choice> = emptyList(),
    val languageKey: Int? = null,
    /** Schlummer-Restzeit in Sekunden; null = vom Radio nicht unterstützt. */
    val sleepSeconds: Int? = null,
    val clock24: Boolean? = null,
    val clockSources: List<Choice> = emptyList(),
    val clockSourceKey: Int? = null,
    /** Rohname der Zeitquellen (DAB, FM, NETWORK, NO_UPDATE …) je Schlüssel. */
    val clockSourceIds: Map<Int, String> = emptyMap(),
    val timeZones: List<TimeZoneChoice> = emptyList(),
    val timeZone: String? = null,
    val utcOffset: Int? = null,
    val dst: Boolean? = null,
    /** Ob sich Datum und Uhrzeit von Hand stellen lassen (netRemote.sys.clock.localDate). */
    val manualClock: Boolean = false,
    // Netzwerk
    val wired: Boolean = false,
    val dhcp: Boolean? = null,
    val subnet: String? = null,
    val gateway: String? = null,
    val dns1: String? = null,
    val dns2: String? = null,
    // Klang
    /** Verzögerung für externe Lautsprecher in ms; null = vom Radio nicht unterstützt. */
    val extDelay: Int? = null,
    val extDelayMax: Int? = null,
    /** Qualität der Internet-Streams: 0 niedrig, 1 normal, 2 hoch. */
    val streamQuality: Int? = null,
)

/** Bluetooth des Radios: sichtbar zum Koppeln und die verbundenen Geräte. */
data class BluetoothUi(
    val loading: Boolean = false,
    val discoverable: Boolean? = null,
    val devices: List<BluetoothDevice> = emptyList(),
)

/** [state]: 0 getrennt, 1 verbunden, 2 gekoppelt. */
data class BluetoothDevice(val key: Int, val name: String, val state: Int)

data class EqPreset(val key: Int, val label: String)
data class EqBand(val index: Int, val label: String, val min: Int, val max: Int, val value: Int = 0)

data class EqualizerUi(
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val presets: List<EqPreset> = emptyList(),
    val presetKey: Int? = null,
    val customKey: Int? = null,
    val bands: List<EqBand> = emptyList(),
    val loudness: Boolean? = null,
) {
    val available get() = presets.isNotEmpty() || bands.isNotEmpty() || loudness != null

    /** Bänder und Loudness wirken nur im eigenen Preset („My EQ“) – falls das Radio eines hat. */
    val customActive get() = customKey == null || customKey == presetKey
}

/** Das Radio liefert lange Beschreibungen in Stücken; dieses Zeichen heißt „es kommt noch mehr“. */
private const val MORE_MARKER = "~!@#$~"

/**
 * Abspielen bzw. anhalten. Das Radio kennt kein Pause/Fortsetzen, nur Abspielen (1) und Stopp (0);
 * manche lehnen Stopp bei Internetradio ab oder ignorieren es, dort beendet Pause (2) den Stream.
 */
internal suspend fun FsApiClient.playOrStop(play: Boolean) {
    if (play) {
        if (!set(Nodes.PLAY_CONTROL, "1")) throw FsApiException(R.string.error_play_rejected)
        return
    }
    val accepted = set(Nodes.PLAY_CONTROL, "0")
    if (accepted) delay(800)
    if (!accepted || get(Nodes.PLAY_STATUS)?.trim() == "2") {
        if (!set(Nodes.PLAY_CONTROL, "2")) throw FsApiException(R.string.error_stop_rejected)
    }
}

private val CUSTOM_EQ = Regex("my ?eq|mein|eigen|benutzer|custom|user", RegexOption.IGNORE_CASE)

class RadioViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("radiopult", Context.MODE_PRIVATE)

    var devices by mutableStateOf<List<RadioDevice>>(emptyList())
        private set
    var scanning by mutableStateOf(false)
        private set
    var connecting by mutableStateOf(false)
        private set
    var connectError by mutableStateOf<String?>(null)
        private set

    /** Das Radio, mit dem gerade verbunden wird. */
    var connectingHost by mutableStateOf<String?>(null)
        private set
    var current by mutableStateOf<RadioDevice?>(null)
        private set
    var ui by mutableStateOf(RadioUi())
        private set
    var eq by mutableStateOf(EqualizerUi())
        private set
    var browse by mutableStateOf(BrowseUi())
        private set
    var settings by mutableStateOf(SettingsUi())
        private set
    var bluetooth by mutableStateOf(BluetoothUi())
        private set

    /** Kurze Rückmeldung für die Unterseiten (Snackbar); [consumeNotice] löscht sie. */
    var notice by mutableStateOf<String?>(null)
        private set

    private var client: FsApiClient? = null

    /** Andere Radios (Multiroom, Stereo) mit ihrer gespeicherten PIN. */
    private val otherClients = mutableMapOf<String, FsApiClient>()

    val multiroom = MultiroomController(
        viewModelScope,
        clientFor = { host ->
            client?.takeIf { host == current?.host } ?: otherClients.getOrPut(host) { FsApiClient(host, savedPin(host)) }
        },
        text = { id, args -> str(id, *args) },
        onNotice = { notice = it },
    )
    private var pollJob: Job? = null
    private var visible = false
    private val presetTracker = PresetTracker()
    private var browseJob: Job? = null

    private fun str(@StringRes id: Int, vararg args: Any): String = getApplication<Application>().getString(id, *args)

    private fun plural(@PluralsRes id: Int, n: Int): String = getApplication<Application>().resources.getQuantityString(id, n, n)

    /** Meldung für die Anzeige: Fehler des Radios übersetzt, sonst die Systemmeldung oder [fallback]. */
    private fun describe(e: IOException, @StringRes fallback: Int): String =
        if (e is FsApiException) e.describe(getApplication()) else e.message ?: str(fallback)

    init {
        prefs.getString("last_host", null)?.let { host ->
            val last = RadioDevice(host, prefs.getString("last_name", host) ?: host)
            devices = listOf(last)
            // Beim Start gleich mit dem zuletzt genutzten Radio verbinden; klappt das nicht, bleibt die Liste.
            if (hasSavedPin(host)) connect(last, savedPin(host)) { connectError = null }
        }
        scan()
    }

    fun savedPin(host: String): String = prefs.getString("pin_$host", "1234") ?: "1234"

    /** Wurde mit diesem Radio schon einmal erfolgreich verbunden, ist seine PIN gespeichert. */
    fun hasSavedPin(host: String): Boolean = prefs.contains("pin_$host")

    /** Nach der Ersteinrichtung: neues Radio vorn in die Liste. */
    fun addDevice(device: RadioDevice) {
        devices = (listOf(device) + devices).distinctBy { it.host }
    }

    fun clearError() {
        connectError = null
    }

    fun scan() {
        if (scanning) return
        viewModelScope.launch {
            scanning = true
            try {
                val found = Discovery.discover(getApplication<Application>())
                devices = (found + devices).distinctBy { it.host }
            } catch (ignored: Exception) {
                // Suche fehlgeschlagen – manuelle IP bleibt möglich
            } finally {
                scanning = false
            }
        }
    }

    /** [onFailed] läuft, wenn die Verbindung nicht klappt (z. B. gespeicherte PIN stimmt nicht mehr). */
    fun connect(device: RadioDevice, pin: String, onFailed: () -> Unit = {}) {
        if (connecting) return
        viewModelScope.launch {
            connecting = true
            connectingHost = device.host
            connectError = null
            val c = FsApiClient(device.host, pin)
            try {
                val name = c.get(Nodes.FRIENDLY_NAME)?.trim()?.ifEmpty { null } ?: device.name
                prefs.edit()
                    .putString("pin_${device.host}", pin)
                    .putString("last_host", device.host)
                    .putString("last_name", name)
                    .apply()
                val dev = device.copy(name = name)
                devices = (listOf(dev) + devices).distinctBy { it.host }
                client = c
                presetTracker.reset()
                ui = RadioUi(name = name)
                eq = EqualizerUi()
                browse = BrowseUi()
                settings = SettingsUi()
                bluetooth = BluetoothUi()
                multiroom.reset()
                otherClients.clear()
                loadCapabilities(c)
                // wie UNDOK: dem Radio sagen, wer es steuert (manche zeigen das an)
                runCatching { c.set(Nodes.CONTROLLER_NAME, controllerName()) }
                current = dev
                RadioService.attach(getApplication(), dev.host, pin, name)
                startPolling()
            } catch (e: FsApiException) {
                connectError = if (e is WrongPinException) {
                    str(R.string.error_wrong_pin_connect)
                } else e.describe(getApplication())
                onFailed()
            } catch (ignored: IOException) {
                connectError = str(R.string.error_unreachable, device.host)
                onFailed()
            } finally {
                connecting = false
                connectingHost = null
            }
        }
    }

    fun disconnect() {
        RadioService.detach(getApplication())
        pollJob?.cancel()
        pollJob = null
        client = null
        current = null
        ui = RadioUi()
        eq = EqualizerUi()
        browse = BrowseUi()
        settings = SettingsUi()
        bluetooth = BluetoothUi()
        multiroom.reset()
        otherClients.clear()
    }

    /** Medien-Benachrichtigung und Sperrbildschirm-Steuerung (aus ab Werk). */
    var notificationOn by mutableStateOf(RadioService.notificationEnabled(app))
        private set

    fun setNotification(on: Boolean) {
        notificationOn = on
        RadioService.setNotificationEnabled(getApplication(), on)
    }

    /** Musik vom Handy auf diesem Radio abspielen: [queue] ab Stück [index]. */
    fun playLocal(queue: List<Track>, index: Int) {
        val dev = current ?: return
        LocalPlayer.play(
            getApplication(), dev.host, savedPin(dev.host), dev.name,
            ui.modes.firstOrNull { it.isDmr }?.key, queue, index,
        )
    }

    fun loadMultiroom() {
        current?.host?.let { multiroom.load(it, ui.volumeMax) }
    }

    /** Name dieses Handys, so wie der Nutzer es in den Einstellungen genannt hat. */
    private fun controllerName(): String {
        val device = runCatching {
            Settings.Global.getString(getApplication<Application>().contentResolver, Settings.Global.DEVICE_NAME)
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: Build.MODEL
        return "RadioPult ($device)".take(32)
    }

    fun consumeNotice() {
        notice = null
    }

    fun onVisible() {
        visible = true
        if (client != null) startPolling()
    }

    fun onHidden() {
        visible = false
        pollJob?.cancel()
        pollJob = null
    }

    private fun startPolling() {
        if (!visible) return
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                refresh()
                delay(2500)
            }
        }
    }

    private suspend fun loadCapabilities(c: FsApiClient) {
        val steps = c.get(Nodes.VOLUME_STEPS)?.trim()?.toIntOrNull() ?: 33
        val modes = try {
            c.list(Nodes.VALID_MODES)
                .filter { it.fields["selectable"]?.trim() != "0" }
                .map { item ->
                    val label = item.fields["label"]?.trim().orEmpty()
                    val id = item.fields["id"]?.trim().orEmpty()
                    Mode(item.key, label.ifEmpty { id }, id)
                }
        } catch (ignored: IOException) {
            emptyList()
        }
        val fm = c.getAll(listOf(Nodes.FM_LOWER, Nodes.FM_UPPER, Nodes.FM_STEP))
        val lower = fm[Nodes.FM_LOWER]?.trim()?.toIntOrNull()
        val upper = fm[Nodes.FM_UPPER]?.trim()?.toIntOrNull()
        val range = if (lower != null && upper != null && upper > lower) {
            FmRange(lower, upper, fm[Nodes.FM_STEP]?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: 50)
        } else null
        ui = ui.copy(volumeMax = (steps - 1).coerceAtLeast(1), modes = modes, fmRange = range)
    }

    /** Favoriten der Quelle [mode] laden; bis die Liste zweimal gleich ankommt, bei jeder Abfrage erneut. */
    private suspend fun loadPresets(c: FsApiClient, mode: Int): List<Preset> {
        val loaded = try {
            // nach einem Quellenwechsel hängt die Liste im Radio sonst noch an der alten Quelle
            if (presetTracker.needsReset(mode)) c.set(Nodes.NAV_STATE, "0")
            c.set(Nodes.NAV_STATE, "1")
            awaitNav(c, maxMs = 3000)
            // auch freie Plätze behalten – dort kann ein Sender gespeichert werden
            val list = c.list(Nodes.PRESETS, strict = true).map { item ->
                Preset(item.key, item.text("name"), item.text("type"), item.text("url"))
            }
            list.takeIf { c.get(Nodes.MODE)?.trim()?.toIntOrNull() == mode } // inzwischen umgeschaltet
        } catch (ignored: IOException) {
            null // beim nächsten Durchlauf erneut versuchen
        }
        return presetTracker.onLoaded(mode, loaded)
            ?: if (ui.modeKey == mode) ui.presets else emptyList()
    }

    private suspend fun refresh() {
        val c = client ?: return
        try {
            val power = c.get(Nodes.POWER)?.trim() == "1"
            if (!power) {
                ui = ui.copy(connected = true, error = null, power = false)
                return
            }
            if (ui.modes.isEmpty()) loadCapabilities(c)

            val mode = c.get(Nodes.MODE)?.trim()?.toIntOrNull()
            val presets = if (mode != null && presetTracker.needsLoad(mode)) loadPresets(c, mode) else ui.presets
            val fm = ui.modes.firstOrNull { it.key == mode }?.id.equals("FM", ignoreCase = true)
            val nodes = buildList {
                addAll(
                    listOf(
                        Nodes.VOLUME, Nodes.MUTE, Nodes.INFO_NAME, Nodes.INFO_TEXT, Nodes.INFO_ARTIST,
                        Nodes.PLAY_STATUS, Nodes.INFO_GRAPHIC, Nodes.NAV_CAPS, Nodes.PLAY_CAPS,
                    ),
                )
                // Zufall, Wiederholen und Position nur, wo die Quelle sie haben kann
                if (!fm) addAll(listOf(Nodes.PLAY_SHUFFLE, Nodes.PLAY_REPEAT, Nodes.PLAY_POSITION, Nodes.INFO_DURATION, Nodes.INFO_ALBUM))
                if (fm) add(Nodes.FREQUENCY)
                if (presets.isNotEmpty()) add(Nodes.CURRENT_PRESET)
            }
            val v = c.getAll(nodes)
            val readAt = SystemClock.elapsedRealtime()
            fun int(node: String) = v[node]?.trim()?.toIntOrNull()
            fun long(node: String) = v[node]?.trim()?.toLongOrNull()
            val status = int(Nodes.PLAY_STATUS)
            val navCaps = long(Nodes.NAV_CAPS)

            if (client !== c) return // inzwischen getrennt
            ui = ui.copy(
                connected = true,
                error = null,
                power = true,
                modeKey = mode,
                presets = presets,
                volume = int(Nodes.VOLUME) ?: ui.volume,
                muted = v[Nodes.MUTE]?.trim() == "1",
                station = v[Nodes.INFO_NAME]?.trim().orEmpty(),
                text = v[Nodes.INFO_TEXT]?.trim().orEmpty(),
                artist = v[Nodes.INFO_ARTIST]?.trim().orEmpty(),
                // 1 = Puffern, 2 = Spielt, 4 = Puffern nach Unterbrechung
                playing = status == 1 || status == 2 || status == 4,
                coverUrl = v[Nodes.INFO_GRAPHIC]?.trim()?.takeIf { it.startsWith("http") },
                navigable = navCaps?.let { it and 1L != 0L },
                presetsEditable = navCaps?.let { it and 4L != 0L },
                caps = long(Nodes.PLAY_CAPS)?.let { PlayCaps(it) },
                shuffle = v[Nodes.PLAY_SHUFFLE]?.trim()?.let { it == "1" },
                repeat = int(Nodes.PLAY_REPEAT),
                positionMs = long(Nodes.PLAY_POSITION),
                durationMs = long(Nodes.INFO_DURATION),
                positionReadAt = readAt,
                album = v[Nodes.INFO_ALBUM]?.trim().orEmpty(),
                frequency = int(Nodes.FREQUENCY)?.takeIf { it > 0 },
                currentPreset = int(Nodes.CURRENT_PRESET),
            )
        } catch (e: FsApiException) {
            ui = ui.copy(error = e.describe(getApplication()))
        } catch (ignored: IOException) {
            ui = ui.copy(error = str(R.string.error_connection_lost))
        }
    }

    private fun command(block: suspend (FsApiClient) -> Unit) {
        val c = client ?: return
        viewModelScope.launch {
            try {
                block(c)
                refresh()
            } catch (e: IOException) {
                ui = ui.copy(error = describe(e, R.string.error_command_failed))
            }
        }
    }

    fun togglePower() {
        val on = !ui.power
        ui = ui.copy(power = on)
        command { it.set(Nodes.POWER, if (on) "1" else "0") }
    }

    fun selectMode(mode: Mode) {
        ui = ui.copy(modeKey = mode.key, presets = emptyList(), station = "", text = "", artist = "", coverUrl = null, navigable = null)
        command { it.set(Nodes.MODE, mode.key.toString()) }
    }

    fun setVolume(value: Int) {
        val v = value.coerceIn(0, ui.volumeMax)
        ui = ui.copy(volume = v)
        command { it.set(Nodes.VOLUME, v.toString()) }
    }

    /** Lauter/leiser in Schritten, z. B. mit den Lautstärketasten des Handys. */
    fun volumeStep(delta: Int) {
        if (!ui.connected || !ui.power) return
        setVolume(ui.volume + delta)
        if (ui.muted && delta > 0) toggleMute()
    }

    fun toggleMute() {
        val m = !ui.muted
        ui = ui.copy(muted = m)
        command { it.set(Nodes.MUTE, if (m) "1" else "0") }
    }

    fun playStop() {
        if (localActive()) return LocalPlayer.toggle()
        val stop = ui.playing
        ui = ui.copy(playing = !stop)
        command { c -> c.playOrStop(!stop) }
    }

    fun next() {
        if (localActive()) return LocalPlayer.next()
        command { it.set(Nodes.PLAY_CONTROL, "3") }
    }

    fun previous() {
        if (localActive()) return LocalPlayer.previous()
        command { it.set(Nodes.PLAY_CONTROL, "4") }
    }

    /** Läuft gerade Musik vom Handy auf diesem Radio? Dann steuern die Knöpfe die Warteschlange. */
    private fun localActive() = LocalPlayer.ui.active && LocalPlayer.ui.radioHost == current?.host && ui.mode?.isDmr == true

    fun toggleShuffle() {
        val on = ui.shuffle != true
        ui = ui.copy(shuffle = on)
        command { it.set(Nodes.PLAY_SHUFFLE, if (on) "1" else "0") }
    }

    /** Aus → alle → einen Titel (wenn die Quelle das kann) → aus. */
    fun cycleRepeat() {
        val next = when (ui.repeat) {
            0 -> 1
            1 -> if (ui.caps?.repeatOne == true) 2 else 0
            else -> 0
        }
        ui = ui.copy(repeat = next)
        command { it.set(Nodes.PLAY_REPEAT, next.toString()) }
    }

    fun seekTo(ms: Long) {
        val pos = ms.coerceIn(0, ui.durationMs ?: ms)
        ui = ui.copy(positionMs = pos, positionReadAt = SystemClock.elapsedRealtime())
        command { it.set(Nodes.PLAY_POSITION, pos.toString()) }
    }

    /** UKW-Frequenz in kHz einstellen (auf das Raster des Radios gerundet). */
    fun setFrequency(khz: Int) {
        val range = ui.fmRange ?: return
        val stepped = range.lower + ((khz - range.lower + range.step / 2) / range.step) * range.step
        val f = stepped.coerceIn(range.lower, range.upper)
        ui = ui.copy(frequency = f)
        command { it.set(Nodes.FREQUENCY, f.toString()) }
    }

    fun stepFrequency(delta: Int) {
        val range = ui.fmRange ?: return
        val f = ui.frequency ?: return
        setFrequency(f + delta * range.step)
    }

    /** Mehr zum laufenden Titel lesen (Album, Beschreibungen). */
    fun loadDetails() {
        val c = client ?: return
        viewModelScope.launch {
            try {
                val v = c.getAll(
                    listOf(Nodes.INFO_ALBUM, Nodes.INFO_DESCRIPTION, Nodes.INFO_ARTIST_DESCRIPTION, Nodes.INFO_ALBUM_DESCRIPTION, Nodes.INFO_PROVIDER),
                )
                if (client !== c) return@launch
                ui = ui.copy(
                    details = TrackDetails(
                        album = v[Nodes.INFO_ALBUM]?.trim().orEmpty(),
                        description = v[Nodes.INFO_DESCRIPTION]?.trim().orEmpty(),
                        artistDescription = v[Nodes.INFO_ARTIST_DESCRIPTION]?.trim().orEmpty(),
                        albumDescription = v[Nodes.INFO_ALBUM_DESCRIPTION]?.trim().orEmpty(),
                        provider = v[Nodes.INFO_PROVIDER]?.trim().orEmpty(),
                    ),
                )
            } catch (e: IOException) {
                notice = describe(e, R.string.error_command_failed)
            }
        }
    }

    fun clearDetails() {
        ui = ui.copy(details = null)
    }

    /** Das Radio spielt einen Hinweiston – praktisch, um es im Haus wiederzufinden. */
    fun locate() {
        val c = client ?: return
        viewModelScope.launch {
            notice = try {
                if (c.set(Nodes.ALERT_TONE, "1")) str(R.string.notice_locate) else str(R.string.error_locate_unsupported)
            } catch (e: IOException) {
                describe(e, R.string.error_command_failed)
            }
        }
    }

    fun selectPreset(preset: Preset) = command {
        it.set(Nodes.NAV_STATE, "1")
        it.set(Nodes.SELECT_PRESET, preset.key.toString())
    }

    fun loadEqualizer() {
        val c = client ?: return
        if (eq.loading) return
        eq = eq.copy(loading = true)
        viewModelScope.launch {
            try {
                val base = if (eq.loaded) eq else loadEqCapabilities(c)
                val state = readEqState(c, base)
                if (client === c) eq = state
            } catch (e: IOException) {
                ui = ui.copy(error = describe(e, R.string.error_eq_load))
            } finally {
                if (client === c) eq = eq.copy(loading = false)
            }
        }
    }

    private suspend fun loadEqCapabilities(c: FsApiClient): EqualizerUi {
        val presets = c.list(Nodes.EQ_PRESETS).map { item ->
            EqPreset(item.key, item.fields["label"]?.trim().orEmpty().ifEmpty { str(R.string.eq_preset_default, item.key + 1) })
        }
        var bands = c.list(Nodes.EQ_BANDS).map { item ->
            EqBand(
                index = item.key,
                label = item.fields["label"]?.trim().orEmpty().ifEmpty { str(R.string.eq_band_default, item.key + 1) },
                min = item.fields["min"]?.trim()?.toIntOrNull() ?: -14,
                max = item.fields["max"]?.trim()?.toIntOrNull() ?: 14,
            )
        }.filter { it.max > it.min }
        if (bands.isEmpty()) {
            // manche Geräte nennen ihre Bänder nicht – Bass und Höhen haben sie trotzdem
            bands = listOf(str(R.string.eq_bass), str(R.string.eq_treble)).mapIndexedNotNull { i, label ->
                if (c.get(Nodes.eqCustom(i)) != null) EqBand(i, label, -14, 14) else null
            }
        }
        val customKey = presets.firstOrNull { CUSTOM_EQ.containsMatchIn(it.label) }?.key
        return EqualizerUi(loaded = true, presets = presets, customKey = customKey, bands = bands)
    }

    private suspend fun readEqState(c: FsApiClient, base: EqualizerUi): EqualizerUi = base.copy(
        presetKey = if (base.presets.isEmpty()) null else c.get(Nodes.EQ_PRESET)?.trim()?.toIntOrNull(),
        bands = base.bands.map { band ->
            band.copy(value = c.get(Nodes.eqCustom(band.index))?.trim()?.toIntOrNull() ?: band.value)
        },
        loudness = c.get(Nodes.EQ_LOUDNESS)?.trim()?.let { it == "1" },
    )

    private fun eqCommand(block: suspend (FsApiClient) -> Unit) {
        val c = client ?: return
        viewModelScope.launch {
            try {
                block(c)
                val state = readEqState(c, eq)
                if (client === c) eq = state.copy(loading = eq.loading)
            } catch (e: IOException) {
                ui = ui.copy(error = describe(e, R.string.error_command_failed))
            }
        }
    }

    fun selectEqPreset(preset: EqPreset) {
        eq = eq.copy(presetKey = preset.key)
        eqCommand { it.set(Nodes.EQ_PRESET, preset.key.toString()) }
    }

    fun setEqBand(index: Int, value: Int) {
        val band = eq.bands.firstOrNull { it.index == index } ?: return
        val v = value.coerceIn(band.min, band.max)
        eq = eq.copy(bands = eq.bands.map { if (it.index == index) it.copy(value = v) else it })
        eqCommand { it.set(Nodes.eqCustom(index), v.toString()) }
    }

    fun toggleLoudness() {
        val on = eq.loudness != true
        eq = eq.copy(loudness = on)
        eqCommand { it.set(Nodes.EQ_LOUDNESS, if (on) "1" else "0") }
    }

    // ---- Favoriten bearbeiten ----

    /** Aktuellen Sender auf diesem Speicherplatz ablegen. */
    fun storePreset(slot: Preset) = presetCommand(str(R.string.notice_station_stored, slot.key + 1)) {
        it.set(Nodes.ADD_PRESET, slot.key.toString()) && presetStored(it)
    }

    /** Neuere Radios melden, ob das Speichern geklappt hat (0 = gespeichert, 1 = nicht). */
    private suspend fun presetStored(c: FsApiClient): Boolean {
        delay(300)
        return c.get(Nodes.ADD_PRESET_STATUS)?.trim() != "1"
    }

    /**
     * Name und Stream-Adresse eines Favoriten direkt ändern (wie „Bearbeiten“ in UNDOK).
     * Geht nur bei Radios, die Favoriten über LIST_SET ändern lassen.
     */
    fun editPreset(slot: Preset, name: String, url: String) {
        val n = name.trim()
        val u = url.trim()
        if (n.isEmpty()) return
        ui = ui.copy(presets = ui.presets.map { if (it.key == slot.key) it.copy(name = n, url = u) else it })
        val type = slot.type.ifEmpty {
            when {
                ui.mode?.id.equals("IR", ignoreCase = true) -> "AirableRadio"
                ui.mode?.id?.contains("PODCAST", ignoreCase = true) == true -> "Podcasts"
                else -> ""
            }
        }
        presetCommand(str(R.string.notice_preset_edited, slot.key + 1), failed = str(R.string.error_preset_edit)) {
            it.set(Nodes.NAV_STATE, "1")
            it.listSet(
                Nodes.PRESETS,
                listOf("key" to slot.key.toString(), "name" to n, "type" to type, "url" to u),
            )
        }
    }

    fun deletePreset(slot: Preset) {
        ui = ui.copy(presets = ui.presets.map { if (it.key == slot.key) it.copy(name = "") else it })
        presetCommand(str(R.string.notice_favorite_deleted, slot.key + 1)) {
            it.set(Nodes.NAV_STATE, "1")
            it.set(Nodes.PRESET_DELETE, slot.key.toString())
        }
    }

    /** Favorit um eine Position nach oben (-1) oder unten (+1) verschieben. */
    fun movePreset(slot: Preset, delta: Int) {
        val list = ui.presets
        val i = list.indexOfFirst { it.key == slot.key }
        val j = i + delta
        if (i < 0 || j !in list.indices) return
        val other = list[j]
        ui = ui.copy(presets = list.map {
            when (it.key) {
                slot.key -> it.copy(name = other.name)
                other.key -> it.copy(name = slot.name)
                else -> it
            }
        })
        presetCommand(null) {
            it.set(Nodes.NAV_STATE, "1")
            it.set(Nodes.PRESET_SWAP_1, slot.key.toString()) &&
                it.set(Nodes.PRESET_SWAP_2, other.key.toString()) &&
                it.set(Nodes.PRESET_SWAP, "1")
        }
    }

    /**
     * Eintrag aus der Senderliste auf diesem Speicherplatz ablegen. Das Radio kann nur den laufenden
     * Sender speichern, deshalb wird der Eintrag zuerst abgespielt.
     */
    fun storeItemAsPreset(item: NavItem, slot: Preset) {
        ui = ui.copy(presets = ui.presets.map { if (it.key == slot.key) it.copy(name = item.name) else it })
        presetCommand(
            str(R.string.notice_item_stored, item.name, slot.key + 1),
            failed = str(R.string.notice_item_store_failed, item.name),
        ) {
            val before = it.get(Nodes.INFO_NAME)?.trim().orEmpty()
            it.set(Nodes.NAV_SELECT_ITEM, item.key.toString()) &&
                awaitStation(it, item.name, before) &&
                it.set(Nodes.ADD_PRESET, slot.key.toString()) &&
                presetStored(it)
        }
    }

    /** Wartet, bis das Radio den gewählten Sender spielt; false, wenn es nicht umschaltet. */
    private suspend fun awaitStation(c: FsApiClient, name: String, before: String, maxMs: Long = 10_000): Boolean {
        val end = System.currentTimeMillis() + maxMs
        while (System.currentTimeMillis() < end) {
            val now = c.get(Nodes.INFO_NAME)?.trim().orEmpty()
            // der angezeigte Name weicht manchmal leicht vom Listeneintrag ab – dann zählt der Wechsel
            if (now.equals(name, ignoreCase = true) || (now.isNotEmpty() && now != before)) {
                delay(500) // kurz warten, bis der Sender wirklich läuft
                return true
            }
            delay(400)
        }
        return false
    }

    private fun presetCommand(
        done: String?,
        failed: String = str(R.string.error_change_rejected),
        block: suspend (FsApiClient) -> Boolean,
    ) {
        val c = client ?: return
        viewModelScope.launch {
            try {
                val ok = block(c)
                delay(400) // das Radio braucht einen Moment, bis die Liste neu ist
                presetTracker.invalidate()
                refresh()
                notice = if (ok) done else failed
            } catch (e: IOException) {
                presetTracker.invalidate()
                notice = describe(e, R.string.error_command_failed)
            }
        }
    }

    // ---- Sender-/Titelliste ----

    private fun browseTask(block: suspend (FsApiClient) -> Unit) {
        val c = client ?: return
        browseJob?.cancel()
        browseJob = viewModelScope.launch {
            browse = browse.copy(loading = true, error = null)
            try {
                block(c)
            } catch (e: IOException) {
                if (client === c) browse = browse.copy(error = describe(e, R.string.error_list_load))
            } finally {
                if (client === c) browse = browse.copy(loading = false)
            }
        }
    }

    /** Wartet, bis das Radio die Liste fertig aufgebaut hat. */
    private suspend fun awaitNav(c: FsApiClient, maxMs: Long = 10_000) {
        val end = System.currentTimeMillis() + maxMs
        while (System.currentTimeMillis() < end) {
            val status = c.get(Nodes.NAV_STATUS)?.trim() ?: return // Knoten fehlt: nicht warten
            if (status == "1") return
            delay(300)
        }
    }

    private suspend fun readNavList(c: FsApiClient): List<NavItem> {
        awaitNav(c)
        return c.list(Nodes.NAV_LIST).mapNotNull { it.toNavItem() }
    }

    private fun FsItem.toNavItem(): NavItem? {
        val name = text("name")
        return if (name.isEmpty()) null
        else NavItem(key, name, fields["type"]?.trim()?.toIntOrNull() ?: 1, fields["contextmenu"]?.trim() == "1")
    }

    /** Liste neu lesen, dazu die Beschreibung des Ordners (lange Texte kommen in Stücken). */
    private suspend fun showNavList(c: FsApiClient, path: List<String>) {
        val items = readNavList(c)
        val info = c.getAll(listOf(Nodes.NAV_DESCRIPTION, Nodes.NAV_RELEASE_DATE))
        var description = info[Nodes.NAV_DESCRIPTION].orEmpty()
        var chunks = 0
        while (description.endsWith(MORE_MARKER) && chunks++ < 10) {
            description = description.removeSuffix(MORE_MARKER) + c.get(Nodes.NAV_DESCRIPTION).orEmpty()
        }
        browse = browse.copy(
            path = path,
            items = items,
            description = description.removeSuffix(MORE_MARKER).trim(),
            releaseDate = info[Nodes.NAV_RELEASE_DATE]?.trim()?.toLongOrNull()?.takeIf { it > 0 },
        )
    }

    /** Liste der aktuellen Quelle von oben öffnen. */
    fun openBrowser() = browseTask { c ->
        browse = BrowseUi(loading = true)
        c.set(Nodes.NAV_STATE, "0") // setzt die Position auf die oberste Ebene zurück
        c.set(Nodes.NAV_STATE, "1")
        showNavList(c, emptyList())
    }

    fun openItem(item: NavItem) {
        when {
            item.folder -> browseTask { c ->
                if (!c.set(Nodes.NAV_NAVIGATE, item.key.toString())) {
                    browse = browse.copy(error = str(R.string.error_cannot_open, item.name))
                    return@browseTask
                }
                showNavList(c, browse.path + item.name)
            }

            item.form -> browseTask { c -> openForm(c, item) }

            item.message -> Unit

            else -> command { c ->
                c.set(Nodes.NAV_SELECT_ITEM, item.key.toString())
                notice = str(R.string.notice_playing, item.name)
            }
        }
    }

    fun search(item: NavItem, term: String) = browseTask { c ->
        c.set(Nodes.NAV_SEARCH_TERM, term)
        if (!c.set(Nodes.NAV_NAVIGATE, item.key.toString())) {
            browse = browse.copy(error = str(R.string.error_search_unsupported))
            return@browseTask
        }
        showNavList(c, browse.path + str(R.string.search_path, term))
    }

    /** Eine Ebene zurück; false, wenn schon oben (dann schließt die Liste). */
    fun browseUp(): Boolean {
        if (browse.path.isEmpty()) return false
        browseTask { c ->
            c.set(Nodes.NAV_NAVIGATE, "-1")
            showNavList(c, browse.path.dropLast(1))
        }
        return true
    }

    /**
     * DAB-Sendersuchlauf starten, dabei die gefundenen Sender zählen; danach wie UNDOK
     * Sender entfernen, die nicht mehr empfangen werden, und die Liste neu laden.
     */
    fun dabScan() = browseTask { c ->
        if (!c.set(Nodes.NAV_DAB_SCAN, "1")) {
            browse = browse.copy(error = str(R.string.error_scan_unsupported))
            return@browseTask
        }
        browse = browse.copy(scanning = true, scanFound = null, path = emptyList(), items = emptyList(), description = "")
        var found: Int? = null
        try {
            delay(2000)
            val end = System.currentTimeMillis() + 180_000
            while (System.currentTimeMillis() < end) {
                val v = c.getAll(listOf(Nodes.NAV_DAB_SCAN, Nodes.NAV_STATUS, Nodes.NAV_DAB_SCAN_UPDATE))
                v[Nodes.NAV_DAB_SCAN_UPDATE]?.trim()?.toIntOrNull()?.let {
                    found = it
                    browse = browse.copy(scanFound = it)
                }
                val scanning = v[Nodes.NAV_DAB_SCAN]?.trim() == "1"
                val ready = v[Nodes.NAV_STATUS]?.trim() != "0"
                if (!scanning && ready) break
                delay(1500)
            }
            runCatching { c.set(Nodes.NAV_DAB_PRUNE, "1") }
        } finally {
            browse = browse.copy(scanning = false, scanFound = null)
        }
        c.set(Nodes.NAV_STATE, "0")
        c.set(Nodes.NAV_STATE, "1")
        showNavList(c, emptyList())
        found?.let { notice = plural(R.plurals.notice_dab_found, it) }
    }

    // ---- Formulare und Kontextmenü ----

    /** Formular öffnen: Felder und Auswahlmöglichkeiten lesen (Optionen kommen der Reihe nach je Feld). */
    private suspend fun openForm(c: FsApiClient, item: NavItem) {
        if (!c.set(Nodes.NAV_NAVIGATE, item.key.toString())) {
            browse = browse.copy(error = str(R.string.error_cannot_open, item.name))
            return
        }
        awaitNav(c)
        val raw = c.list(Nodes.NAV_FORM_ITEM)
        if (raw.isEmpty()) {
            // doch kein Formular, sondern eine gewöhnliche Liste
            showNavList(c, browse.path + item.name)
            return
        }
        val options = c.list(Nodes.NAV_FORM_OPTION).map { Choice(it.key, it.text("name")) }
        var next = 0
        val fields = raw.map { f ->
            val count = f.fields["optionscount"]?.trim()?.toIntOrNull() ?: 0
            val own = options.drop(next).take(count)
            next += count
            FormField(
                key = f.key,
                type = f.fields["formitemtype"]?.trim()?.toIntOrNull() ?: 0,
                id = f.text("id"),
                label = f.text("label"),
                description = f.text("description"),
                options = own,
            )
        }
        browse = browse.copy(path = browse.path + item.name, items = emptyList(), form = NavForm(item.name, fields))
    }

    /**
     * Formular abschicken: Werte als „id=wert&…“, wenn möglich verschlüsselt mit dem Schlüssel
     * des Radios (wie das WLAN-Passwort bei der Einrichtung), dann den Knopf [button] auslösen.
     */
    fun submitForm(button: FormField, values: Map<String, String>) = browseTask { c ->
        val form = browse.form ?: return@browseTask
        val data = form.fields.filter { it.text || it.choice }.joinToString("&") { f ->
            "${f.id}=${formEscape(values[f.id].orEmpty())}"
        }
        val sent = runCatching {
            val key = c.get(Nodes.RSA_PUBLIC_KEY)?.trim().orEmpty()
            key.isNotEmpty() && c.set(Nodes.NAV_ENC_FORM_DATA, RadioSetup.encrypt(key, data))
        }.getOrDefault(false) || c.set(Nodes.NAV_FORM_DATA, data)
        if (!sent || !c.set(Nodes.NAV_NAVIGATE, button.key.toString())) {
            browse = browse.copy(error = str(R.string.error_form_rejected))
            return@browseTask
        }
        browse = browse.copy(form = null)
        showNavList(c, browse.path.dropLast(1))
    }

    fun closeForm() {
        if (browse.form == null) return
        browse = browse.copy(form = null)
        browseUp()
    }

    private fun formEscape(v: String) = v.replace("+", "%2B").replace(";", "%3B").replace("/", "%2F")
        .replace("?", "%3F").replace(":", "%3A").replace("@", "%40").replace("=", "%3D").replace("&", "%26")

    /** Kontextmenü eines Eintrags öffnen (eigene Liste, die das Radio dafür aufbaut). */
    fun openContextMenu(item: NavItem) {
        val c = client ?: return
        browse = browse.copy(contextMenu = ContextMenuUi(title = item.name))
        viewModelScope.launch {
            try {
                if (!c.set(Nodes.NAV_CONTEXT, item.key.toString())) {
                    browse = browse.copy(contextMenu = ContextMenuUi(item.name, loading = false, error = str(R.string.error_context_unsupported)))
                    return@launch
                }
                showContextList(c, item.name)
            } catch (e: IOException) {
                browse = browse.copy(contextMenu = ContextMenuUi(item.name, loading = false, error = describe(e, R.string.error_list_load)))
            }
        }
    }

    fun chooseContextItem(item: NavItem) {
        val c = client ?: return
        val menu = browse.contextMenu ?: return
        browse = browse.copy(contextMenu = menu.copy(loading = true))
        viewModelScope.launch {
            try {
                if (!c.set(Nodes.NAV_CONTEXT_NAVIGATE, item.key.toString())) {
                    browse = browse.copy(contextMenu = menu.copy(loading = false, error = str(R.string.error_change_rejected)))
                    return@launch
                }
                if (item.folder) {
                    showContextList(c, item.name)
                } else {
                    // Aktion ausgeführt: Menü zu, Liste und Favoriten können sich geändert haben
                    browse = browse.copy(contextMenu = null)
                    notice = str(R.string.notice_context_done, item.name)
                    presetTracker.invalidate()
                    showNavList(c, browse.path)
                }
            } catch (e: IOException) {
                browse = browse.copy(contextMenu = menu.copy(loading = false, error = describe(e, R.string.error_list_load)))
            }
        }
    }

    fun closeContextMenu() {
        browse = browse.copy(contextMenu = null)
    }

    private suspend fun showContextList(c: FsApiClient, title: String) {
        val end = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < end) {
            val status = c.get(Nodes.NAV_CONTEXT_STATUS)?.trim() ?: break
            if (status != "0") break // 0 = wartet
            delay(300)
        }
        val items = c.list(Nodes.NAV_CONTEXT_LIST).mapNotNull { it.toNavItem() }
        browse = browse.copy(contextMenu = ContextMenuUi(title, loading = false, items = items))
    }

    // ---- Einstellungen ----

    fun loadSettings() {
        val c = client ?: return
        if (settings.loading) return
        settings = settings.copy(loading = true)
        viewModelScope.launch {
            try {
                val s = readSettings(c)
                if (client === c) settings = s
            } catch (e: IOException) {
                notice = describe(e, R.string.error_settings_load)
            } finally {
                if (client === c) settings = settings.copy(loading = false)
            }
        }
    }

    /** Nur die Restzeit des Sleep-Timers neu lesen. */
    fun refreshSleep() {
        val c = client ?: return
        viewModelScope.launch {
            try {
                val sec = c.get(Nodes.SLEEP)?.trim()?.toIntOrNull()
                if (client === c && sec != null) settings = settings.copy(sleepSeconds = sec)
            } catch (ignored: IOException) {
                // beim nächsten Mal erneut
            }
        }
    }

    private suspend fun readSettings(c: FsApiClient): SettingsUi {
        val wlan = c.get(Nodes.WLAN_ENABLED)?.trim() != "0"
        val languages = c.list(Nodes.VALID_LANGS).mapNotNull { item ->
            val label = item.fields["langlabel"] ?: item.fields.values.firstOrNull()
            label?.trim()?.takeIf { it.isNotEmpty() }?.let { Choice(item.key, Values.languageName(it)) }
        }
        val sourceItems = c.list(Nodes.CLOCK_SOURCES)
        val sourceIds = sourceItems.associate { item ->
            item.key to (item.fields["source"] ?: item.fields.values.firstOrNull()).orEmpty().trim()
        }
        val zones = c.list(Nodes.UTC_SETTINGS).mapNotNull { item ->
            val offset = item.fields["time"]?.trim()?.toIntOrNull() ?: return@mapNotNull null
            TimeZoneChoice(item.key, offset, item.text("region"))
        }
        val v = c.getAll(
            listOf(
                Nodes.MODEL_NAME, Nodes.VERSION, Nodes.IP_ADDRESS, if (wlan) Nodes.WLAN_MAC else Nodes.WIRED_MAC,
                Nodes.WLAN_SSID, Nodes.WLAN_RSSI, Nodes.LANG, Nodes.SLEEP, Nodes.CLOCK_MODE, Nodes.CLOCK_SOURCE,
                Nodes.CLOCK_TIME_ZONE, Nodes.CLOCK_UTC_OFFSET, Nodes.CLOCK_DST, Nodes.CLOCK_LOCAL_DATE,
                Nodes.IP_DHCP, Nodes.IP_SUBNET, Nodes.IP_GATEWAY, Nodes.IP_DNS1, Nodes.IP_DNS2,
                Nodes.EXT_DELAY, Nodes.EXT_DELAY_MAX, Nodes.STREAM_QUALITY,
            ),
        )
        fun text(node: String) = v[node]?.trim()?.ifEmpty { null }
        fun int(node: String) = v[node]?.trim()?.toIntOrNull()
        return SettingsUi(
            loaded = true,
            model = text(Nodes.MODEL_NAME),
            version = text(Nodes.VERSION),
            ip = Values.ipAddress(v[Nodes.IP_ADDRESS]),
            mac = text(if (wlan) Nodes.WLAN_MAC else Nodes.WIRED_MAC),
            network = if (wlan) text(Nodes.WLAN_SSID)?.let { str(R.string.network_wifi, it) } else str(R.string.network_lan),
            signal = if (wlan) int(Nodes.WLAN_RSSI)?.coerceIn(0, 100) else null,
            languages = languages,
            languageKey = int(Nodes.LANG),
            sleepSeconds = int(Nodes.SLEEP),
            clock24 = text(Nodes.CLOCK_MODE)?.let { it == "1" },
            clockSources = sourceIds.filterValues { it.isNotEmpty() }
                .map { (key, id) -> Choice(key, Values.clockSourceRes(id)?.let { str(it) } ?: id) },
            clockSourceKey = int(Nodes.CLOCK_SOURCE),
            clockSourceIds = sourceIds,
            timeZones = zones,
            timeZone = v[Nodes.CLOCK_TIME_ZONE]?.trim(),
            utcOffset = int(Nodes.CLOCK_UTC_OFFSET),
            dst = text(Nodes.CLOCK_DST)?.let { it == "1" },
            manualClock = v.containsKey(Nodes.CLOCK_LOCAL_DATE),
            wired = !wlan,
            dhcp = text(Nodes.IP_DHCP)?.let { it == "1" },
            subnet = Values.ipAddress(v[Nodes.IP_SUBNET]),
            gateway = Values.ipAddress(v[Nodes.IP_GATEWAY]),
            dns1 = Values.ipAddress(v[Nodes.IP_DNS1]),
            dns2 = Values.ipAddress(v[Nodes.IP_DNS2]),
            extDelay = int(Nodes.EXT_DELAY),
            extDelayMax = int(Nodes.EXT_DELAY_MAX)?.takeIf { it > 0 },
            streamQuality = int(Nodes.STREAM_QUALITY),
        )
    }

    private fun settingCommand(done: String?, block: suspend (FsApiClient) -> Boolean) {
        val c = client ?: return
        viewModelScope.launch {
            try {
                val ok = block(c)
                if (client === c) settings = readSettings(c)
                notice = if (ok) done else str(R.string.error_setting_rejected)
            } catch (e: IOException) {
                notice = describe(e, R.string.error_command_failed)
            }
        }
    }

    fun rename(name: String) {
        val n = name.trim()
        if (n.isEmpty()) return
        val c = client ?: return
        viewModelScope.launch {
            try {
                if (!c.set(Nodes.FRIENDLY_NAME, n)) {
                    notice = str(R.string.error_name_rejected)
                    return@launch
                }
                val dev = current ?: return@launch
                prefs.edit().putString("last_name", n).apply()
                ui = ui.copy(name = n)
                current = dev.copy(name = n)
                devices = devices.map { if (it.host == dev.host) it.copy(name = n) else it }
                notice = str(R.string.notice_renamed, n)
            } catch (e: IOException) {
                notice = describe(e, R.string.error_rename_failed)
            }
        }
    }

    fun setLanguage(choice: Choice) {
        settings = settings.copy(languageKey = choice.key)
        settingCommand(str(R.string.notice_language, choice.label)) { it.set(Nodes.LANG, choice.key.toString()) }
    }

    fun setSleep(minutes: Int) {
        settings = settings.copy(sleepSeconds = minutes * 60)
        settingCommand(if (minutes == 0) str(R.string.notice_sleep_off) else plural(R.plurals.notice_sleep_on, minutes)) {
            it.set(Nodes.SLEEP, (minutes * 60).toString())
        }
    }

    fun setClock24(on: Boolean) {
        settings = settings.copy(clock24 = on)
        settingCommand(null) { it.set(Nodes.CLOCK_MODE, if (on) "1" else "0") }
    }

    fun setClockSource(choice: Choice) {
        settings = settings.copy(clockSourceKey = choice.key)
        settingCommand(null) { it.set(Nodes.CLOCK_SOURCE, choice.key.toString()) }
    }

    /** Zeitzone aus der Liste des Radios: Abstand zu UTC und Name der Region setzen. */
    fun setTimeZone(zone: TimeZoneChoice) {
        settings = settings.copy(timeZone = zone.region, utcOffset = zone.offset)
        settingCommand(null) {
            val ok = it.set(Nodes.CLOCK_UTC_OFFSET, zone.offset.toString())
            if (zone.region.isNotEmpty()) it.set(Nodes.CLOCK_TIME_ZONE, zone.region)
            ok
        }
    }

    fun setDst(on: Boolean) {
        settings = settings.copy(dst = on)
        settingCommand(null) { it.set(Nodes.CLOCK_DST, if (on) "1" else "0") }
    }

    /** Datum und Uhrzeit des Handys aufs Radio übertragen (nur bei Zeitquelle „von Hand“ sinnvoll). */
    fun setClockFromPhone() {
        val now = java.util.Calendar.getInstance()
        val date = Values.clockDate(now.get(java.util.Calendar.YEAR), now.get(java.util.Calendar.MONTH) + 1, now.get(java.util.Calendar.DAY_OF_MONTH))
        val time = Values.clockTime(now.get(java.util.Calendar.HOUR_OF_DAY), now.get(java.util.Calendar.MINUTE), now.get(java.util.Calendar.SECOND))
        settingCommand(str(R.string.notice_clock_set)) {
            it.set(Nodes.CLOCK_LOCAL_DATE, date) && it.set(Nodes.CLOCK_LOCAL_TIME, time)
        }
    }

    fun setExtDelay(ms: Int) {
        val v = ms.coerceIn(0, settings.extDelayMax ?: ms.coerceAtLeast(0))
        settings = settings.copy(extDelay = v)
        settingCommand(null) { it.set(Nodes.EXT_DELAY, v.toString()) }
    }

    fun setStreamQuality(level: Int) {
        settings = settings.copy(streamQuality = level)
        settingCommand(null) { it.set(Nodes.STREAM_QUALITY, level.toString()) }
    }

    // ---- Bluetooth ----

    fun loadBluetooth() {
        val c = client ?: return
        bluetooth = bluetooth.copy(loading = true)
        viewModelScope.launch {
            try {
                val discoverable = c.get(Nodes.BT_DISCOVERABLE)?.trim()?.let { it == "1" }
                val devices = c.list(Nodes.BT_DEVICES).map { item ->
                    BluetoothDevice(item.key, item.text("devicename"), item.fields["devicestate"]?.trim()?.toIntOrNull() ?: 0)
                }.filter { it.name.isNotBlank() }
                if (client === c) bluetooth = BluetoothUi(discoverable = discoverable, devices = devices)
            } catch (e: IOException) {
                notice = describe(e, R.string.error_list_load)
                bluetooth = bluetooth.copy(loading = false)
            }
        }
    }

    /** Das Radio für ein neues Gerät sichtbar machen (wie „Neues Gerät koppeln“ in UNDOK). */
    fun pairBluetooth() {
        val c = client ?: return
        bluetooth = bluetooth.copy(discoverable = true)
        viewModelScope.launch {
            try {
                notice = if (c.set(Nodes.BT_DISCOVERABLE, "1")) str(R.string.notice_bt_discoverable) else str(R.string.error_bt_pair)
            } catch (e: IOException) {
                notice = describe(e, R.string.error_command_failed)
            }
            loadBluetooth()
        }
    }

    /** Setzt das Radio auf Werkseinstellungen zurück; danach ist es nicht mehr im WLAN. */
    fun factoryReset() {
        val c = client ?: return
        viewModelScope.launch {
            try {
                if (c.set(Nodes.FACTORY_RESET, "1")) {
                    disconnect()
                } else {
                    notice = str(R.string.error_reset_unsupported)
                }
            } catch (e: IOException) {
                // nach dem Zurücksetzen antwortet das Radio oft nicht mehr – das ist gewollt
                disconnect()
            }
        }
    }
}
