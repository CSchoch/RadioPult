package de.radiopult.app

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.radiopult.app.fsapi.Discovery
import de.radiopult.app.fsapi.FsApiClient
import de.radiopult.app.fsapi.FsApiException
import de.radiopult.app.fsapi.Nodes
import de.radiopult.app.fsapi.RadioDevice
import de.radiopult.app.fsapi.Values
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException

data class Mode(val key: Int, val label: String, val id: String = "") {

    /** Quellen ohne Liste (UKW, AUX, Bluetooth …) bekommen keine Sender-/Titelliste. */
    val browsable: Boolean
        get() {
            val i = id.uppercase()
            return i != "FM" && NOT_BROWSABLE.none { i.contains(it) }
        }

    /** Überschrift der Liste dieser Quelle. */
    val listTitle: String
        get() = when (id.uppercase()) {
            "IR", "DAB" -> "Senderliste"
            "CD" -> "Titelliste"
            else -> label.ifEmpty { "Durchsuchen" }
        }
}

private val NOT_BROWSABLE = listOf("AUX", "BLUETOOTH", "SPDIF", "OPTICAL", "LINE", "SPOTIFY", "AIRPLAY", "HDMI", "DMR")

/** Speicherplatz für einen Favoriten; [name] ist leer, wenn der Platz frei ist. */
data class Preset(val key: Int, val name: String) {
    val empty get() = name.isEmpty()
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
) {
    val mode get() = modes.firstOrNull { it.key == modeKey }
}

/** Eintrag der Sender-/Titelliste: Ordner, Abspielbares oder Suche. */
data class NavItem(val key: Int, val name: String, val type: Int) {
    val folder get() = type == 0
    val search get() = type == 2
}

data class BrowseUi(
    val loading: Boolean = false,
    val scanning: Boolean = false,
    val path: List<String> = emptyList(),
    val items: List<NavItem> = emptyList(),
    val error: String? = null,
)

data class Choice(val key: Int, val label: String)

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
)

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

    /** Kurze Rückmeldung für die Unterseiten (Snackbar); [consumeNotice] löscht sie. */
    var notice by mutableStateOf<String?>(null)
        private set

    private var client: FsApiClient? = null
    private var pollJob: Job? = null
    private var visible = false
    private var presetsForMode: Int? = null
    private var browseJob: Job? = null

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
                presetsForMode = null
                ui = RadioUi(name = name)
                eq = EqualizerUi()
                browse = BrowseUi()
                settings = SettingsUi()
                loadCapabilities(c)
                current = dev
                startPolling()
            } catch (e: FsApiException) {
                connectError = if (e.message == "PIN falsch") {
                    "Die PIN stimmt nicht. Du findest sie am Radio unter Netzwerk › NetRemote PIN."
                } else e.message
                onFailed()
            } catch (ignored: IOException) {
                connectError = "Radio unter ${device.host} nicht erreichbar. Ist es eingeschaltet und im selben WLAN?"
                onFailed()
            } finally {
                connecting = false
                connectingHost = null
            }
        }
    }

    fun disconnect() {
        pollJob?.cancel()
        pollJob = null
        client = null
        current = null
        ui = RadioUi()
        eq = EqualizerUi()
        browse = BrowseUi()
        settings = SettingsUi()
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
        ui = ui.copy(volumeMax = (steps - 1).coerceAtLeast(1), modes = modes)
    }

    private suspend fun loadPresets(c: FsApiClient, mode: Int): List<Preset> {
        presetsForMode = mode
        return try {
            c.set(Nodes.NAV_STATE, "1")
            // auch freie Plätze behalten – dort kann ein Sender gespeichert werden
            c.list(Nodes.PRESETS).map { item -> Preset(item.key, item.text("name")) }
        } catch (ignored: IOException) {
            presetsForMode = null // beim nächsten Durchlauf erneut versuchen
            emptyList()
        }
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
            val presets = if (mode != null && mode != presetsForMode) loadPresets(c, mode) else ui.presets
            val volume = c.get(Nodes.VOLUME)?.trim()?.toIntOrNull()
            val muted = c.get(Nodes.MUTE)?.trim() == "1"
            val station = c.get(Nodes.INFO_NAME)?.trim().orEmpty()
            val text = c.get(Nodes.INFO_TEXT)?.trim().orEmpty()
            val artist = c.get(Nodes.INFO_ARTIST)?.trim().orEmpty()
            val status = c.get(Nodes.PLAY_STATUS)?.trim()?.toIntOrNull()
            val cover = c.get(Nodes.INFO_GRAPHIC)?.trim()?.takeIf { it.startsWith("http") }

            if (client !== c) return // inzwischen getrennt
            ui = ui.copy(
                connected = true,
                error = null,
                power = true,
                modeKey = mode,
                presets = presets,
                volume = volume ?: ui.volume,
                muted = muted,
                station = station,
                text = text,
                artist = artist,
                // 1 = Puffern, 2 = Spielt, 4 = Puffern nach Unterbrechung
                playing = status == 1 || status == 2 || status == 4,
                coverUrl = cover,
            )
        } catch (e: FsApiException) {
            ui = ui.copy(error = e.message)
        } catch (ignored: IOException) {
            ui = ui.copy(error = "Verbindung zum Radio unterbrochen – versuche es weiter …")
        }
    }

    private fun command(block: suspend (FsApiClient) -> Unit) {
        val c = client ?: return
        viewModelScope.launch {
            try {
                block(c)
                refresh()
            } catch (e: IOException) {
                ui = ui.copy(error = e.message ?: "Befehl fehlgeschlagen")
            }
        }
    }

    fun togglePower() {
        val on = !ui.power
        ui = ui.copy(power = on)
        command { it.set(Nodes.POWER, if (on) "1" else "0") }
    }

    fun selectMode(mode: Mode) {
        ui = ui.copy(modeKey = mode.key, presets = emptyList(), station = "", text = "", artist = "", coverUrl = null)
        command { it.set(Nodes.MODE, mode.key.toString()) }
    }

    fun setVolume(value: Int) {
        val v = value.coerceIn(0, ui.volumeMax)
        ui = ui.copy(volume = v)
        command { it.set(Nodes.VOLUME, v.toString()) }
    }

    fun toggleMute() {
        val m = !ui.muted
        ui = ui.copy(muted = m)
        command { it.set(Nodes.MUTE, if (m) "1" else "0") }
    }

    // Das Radio kennt kein Pause/Fortsetzen, nur Abspielen (1) und Stopp (0).
    fun playStop() {
        val stop = ui.playing
        ui = ui.copy(playing = !stop)
        command { c ->
            if (!stop) {
                if (!c.set(Nodes.PLAY_CONTROL, "1")) throw FsApiException("Das Radio hat Abspielen abgelehnt")
                return@command
            }
            // Manche Radios lehnen Stopp (0) bei Internetradio ab oder ignorieren es;
            // dort beendet Pause (2) den Stream.
            val accepted = c.set(Nodes.PLAY_CONTROL, "0")
            if (accepted) delay(800)
            if (!accepted || c.get(Nodes.PLAY_STATUS)?.trim() == "2") {
                if (!c.set(Nodes.PLAY_CONTROL, "2")) throw FsApiException("Das Radio hat Stopp abgelehnt")
            }
        }
    }

    fun next() = command { it.set(Nodes.PLAY_CONTROL, "3") }

    fun previous() = command { it.set(Nodes.PLAY_CONTROL, "4") }

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
                ui = ui.copy(error = e.message ?: "Equalizer konnte nicht geladen werden")
            } finally {
                if (client === c) eq = eq.copy(loading = false)
            }
        }
    }

    private suspend fun loadEqCapabilities(c: FsApiClient): EqualizerUi {
        val presets = c.list(Nodes.EQ_PRESETS).map { item ->
            EqPreset(item.key, item.fields["label"]?.trim().orEmpty().ifEmpty { "EQ ${item.key + 1}" })
        }
        var bands = c.list(Nodes.EQ_BANDS).map { item ->
            EqBand(
                index = item.key,
                label = item.fields["label"]?.trim().orEmpty().ifEmpty { "Band ${item.key + 1}" },
                min = item.fields["min"]?.trim()?.toIntOrNull() ?: -14,
                max = item.fields["max"]?.trim()?.toIntOrNull() ?: 14,
            )
        }.filter { it.max > it.min }
        if (bands.isEmpty()) {
            // manche Geräte nennen ihre Bänder nicht – Bass und Höhen haben sie trotzdem
            bands = listOf("Bass", "Höhen").mapIndexedNotNull { i, label ->
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
                ui = ui.copy(error = e.message ?: "Befehl fehlgeschlagen")
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
    fun storePreset(slot: Preset) = presetCommand("Sender auf Platz ${slot.key + 1} gespeichert") {
        it.set(Nodes.ADD_PRESET, slot.key.toString())
    }

    fun deletePreset(slot: Preset) {
        ui = ui.copy(presets = ui.presets.map { if (it.key == slot.key) it.copy(name = "") else it })
        presetCommand("Favorit ${slot.key + 1} gelöscht") {
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

    private fun presetCommand(done: String?, block: suspend (FsApiClient) -> Boolean) {
        val c = client ?: return
        viewModelScope.launch {
            try {
                val ok = block(c)
                delay(400) // das Radio braucht einen Moment, bis die Liste neu ist
                presetsForMode = null
                refresh()
                notice = if (ok) done else "Das Radio hat die Änderung abgelehnt."
            } catch (e: IOException) {
                presetsForMode = null
                notice = e.message ?: "Befehl fehlgeschlagen"
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
                if (client === c) browse = browse.copy(error = e.message ?: "Liste konnte nicht geladen werden")
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
        return c.list(Nodes.NAV_LIST).mapNotNull { item ->
            val name = item.text("name")
            if (name.isEmpty()) null
            else NavItem(item.key, name, item.fields["type"]?.trim()?.toIntOrNull() ?: 1)
        }
    }

    /** Liste der aktuellen Quelle von oben öffnen. */
    fun openBrowser() = browseTask { c ->
        browse = BrowseUi(loading = true)
        c.set(Nodes.NAV_STATE, "0") // setzt die Position auf die oberste Ebene zurück
        c.set(Nodes.NAV_STATE, "1")
        browse = browse.copy(items = readNavList(c))
    }

    fun openItem(item: NavItem) {
        when {
            item.folder -> browseTask { c ->
                if (!c.set(Nodes.NAV_NAVIGATE, item.key.toString())) {
                    browse = browse.copy(error = "„${item.name}“ lässt sich nicht öffnen.")
                    return@browseTask
                }
                browse = browse.copy(path = browse.path + item.name, items = readNavList(c))
            }

            else -> command { c ->
                c.set(Nodes.NAV_SELECT_ITEM, item.key.toString())
                notice = "Spiele „${item.name}“"
            }
        }
    }

    fun search(item: NavItem, term: String) = browseTask { c ->
        c.set(Nodes.NAV_SEARCH_TERM, term)
        if (!c.set(Nodes.NAV_NAVIGATE, item.key.toString())) {
            browse = browse.copy(error = "Die Suche wird nicht unterstützt.")
            return@browseTask
        }
        browse = browse.copy(path = browse.path + "„$term“", items = readNavList(c))
    }

    /** Eine Ebene zurück; false, wenn schon oben (dann schließt die Liste). */
    fun browseUp(): Boolean {
        if (browse.path.isEmpty()) return false
        browseTask { c ->
            c.set(Nodes.NAV_NAVIGATE, "-1")
            browse = browse.copy(path = browse.path.dropLast(1), items = readNavList(c))
        }
        return true
    }

    /** DAB-Sendersuchlauf starten und danach die Liste neu laden. */
    fun dabScan() = browseTask { c ->
        if (!c.set(Nodes.NAV_DAB_SCAN, "1")) {
            browse = browse.copy(error = "Der Sendersuchlauf wird nicht unterstützt.")
            return@browseTask
        }
        browse = browse.copy(scanning = true, path = emptyList(), items = emptyList())
        try {
            delay(2000)
            val end = System.currentTimeMillis() + 180_000
            while (System.currentTimeMillis() < end) {
                val scanning = c.get(Nodes.NAV_DAB_SCAN)?.trim() == "1"
                val ready = c.get(Nodes.NAV_STATUS)?.trim() != "0"
                if (!scanning && ready) break
                delay(1500)
            }
        } finally {
            browse = browse.copy(scanning = false)
        }
        c.set(Nodes.NAV_STATE, "0")
        c.set(Nodes.NAV_STATE, "1")
        browse = browse.copy(items = readNavList(c))
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
                notice = e.message ?: "Einstellungen konnten nicht geladen werden"
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
        return SettingsUi(
            loaded = true,
            model = c.get(Nodes.MODEL_NAME)?.trim()?.ifEmpty { null },
            version = c.get(Nodes.VERSION)?.trim()?.ifEmpty { null },
            ip = Values.ipAddress(c.get(Nodes.IP_ADDRESS)),
            mac = c.get(if (wlan) Nodes.WLAN_MAC else Nodes.WIRED_MAC)?.trim()?.ifEmpty { null },
            network = if (wlan) c.get(Nodes.WLAN_SSID)?.trim()?.ifEmpty { null }?.let { "$it (WLAN)" } else "LAN-Kabel",
            signal = if (wlan) c.get(Nodes.WLAN_RSSI)?.trim()?.toIntOrNull()?.coerceIn(0, 100) else null,
            languages = languages,
            languageKey = c.get(Nodes.LANG)?.trim()?.toIntOrNull(),
            sleepSeconds = c.get(Nodes.SLEEP)?.trim()?.toIntOrNull(),
            clock24 = c.get(Nodes.CLOCK_MODE)?.trim()?.let { it == "1" },
            clockSources = sourceIds.filterValues { it.isNotEmpty() }
                .map { (key, id) -> Choice(key, Values.clockSourceName(id)) },
            clockSourceKey = c.get(Nodes.CLOCK_SOURCE)?.trim()?.toIntOrNull(),
            clockSourceIds = sourceIds,
        )
    }

    private fun settingCommand(done: String?, block: suspend (FsApiClient) -> Boolean) {
        val c = client ?: return
        viewModelScope.launch {
            try {
                val ok = block(c)
                if (client === c) settings = readSettings(c)
                notice = if (ok) done else "Das Radio hat die Einstellung abgelehnt."
            } catch (e: IOException) {
                notice = e.message ?: "Befehl fehlgeschlagen"
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
                    notice = "Das Radio hat den Namen abgelehnt."
                    return@launch
                }
                val dev = current ?: return@launch
                prefs.edit().putString("last_name", n).apply()
                ui = ui.copy(name = n)
                current = dev.copy(name = n)
                devices = devices.map { if (it.host == dev.host) it.copy(name = n) else it }
                notice = "Umbenannt in „$n“"
            } catch (e: IOException) {
                notice = e.message ?: "Umbenennen fehlgeschlagen"
            }
        }
    }

    fun setLanguage(choice: Choice) {
        settings = settings.copy(languageKey = choice.key)
        settingCommand("Sprache: ${choice.label}") { it.set(Nodes.LANG, choice.key.toString()) }
    }

    fun setSleep(minutes: Int) {
        settings = settings.copy(sleepSeconds = minutes * 60)
        settingCommand(if (minutes == 0) "Sleep-Timer aus" else "Radio schaltet in $minutes Minuten ab") {
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

    /** Setzt das Radio auf Werkseinstellungen zurück; danach ist es nicht mehr im WLAN. */
    fun factoryReset() {
        val c = client ?: return
        viewModelScope.launch {
            try {
                if (c.set(Nodes.FACTORY_RESET, "1")) {
                    disconnect()
                } else {
                    notice = "Das Radio lässt sich über die App nicht zurücksetzen."
                }
            } catch (e: IOException) {
                // nach dem Zurücksetzen antwortet das Radio oft nicht mehr – das ist gewollt
                disconnect()
            }
        }
    }
}
