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
}
