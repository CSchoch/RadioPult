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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException

data class Mode(val key: Int, val label: String)
data class Preset(val key: Int, val name: String)

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
    var current by mutableStateOf<RadioDevice?>(null)
        private set
    var ui by mutableStateOf(RadioUi())
        private set
    var eq by mutableStateOf(EqualizerUi())
        private set

    private var client: FsApiClient? = null
    private var pollJob: Job? = null
    private var visible = false
    private var presetsForMode: Int? = null

    init {
        prefs.getString("last_host", null)?.let { host ->
            devices = listOf(RadioDevice(host, prefs.getString("last_name", host) ?: host))
        }
        scan()
    }

    fun savedPin(host: String): String = prefs.getString("pin_$host", "1234") ?: "1234"

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

    fun connect(device: RadioDevice, pin: String) {
        if (connecting) return
        viewModelScope.launch {
            connecting = true
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
                loadCapabilities(c)
                current = dev
                startPolling()
            } catch (e: FsApiException) {
                connectError = if (e.message == "PIN falsch") {
                    "Die PIN stimmt nicht. Du findest sie am Radio unter Netzwerk › NetRemote PIN."
                } else e.message
            } catch (ignored: IOException) {
                connectError = "Radio unter ${device.host} nicht erreichbar. Ist es eingeschaltet und im selben WLAN?"
            } finally {
                connecting = false
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
                    Mode(item.key, label.ifEmpty { item.fields["id"]?.trim().orEmpty() })
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
            c.list(Nodes.PRESETS).mapNotNull { item ->
                val name = item.fields["name"]?.trim().orEmpty()
                if (name.isEmpty()) null else Preset(item.key, name)
            }
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
                playing = status == 2,
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

    fun playPause() {
        val cmd = if (ui.playing) "2" else "1"
        ui = ui.copy(playing = !ui.playing)
        command { it.set(Nodes.PLAY_CONTROL, cmd) }
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
}
