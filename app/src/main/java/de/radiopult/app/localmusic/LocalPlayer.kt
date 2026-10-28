package de.radiopult.app.localmusic

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import de.radiopult.app.R
import de.radiopult.app.RadioService
import de.radiopult.app.fsapi.FsApiClient
import de.radiopult.app.fsapi.Nodes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetAddress

data class LocalPlayerUi(
    val active: Boolean = false,
    val starting: Boolean = false,
    val radioHost: String? = null,
    val radioName: String = "",
    val queue: List<Track> = emptyList(),
    val index: Int = -1,
    val playing: Boolean = false,
    val error: String? = null,
) {
    val track get() = queue.getOrNull(index)
    val hasNext get() = index < queue.lastIndex
    val hasPrevious get() = index > 0
}

/**
 * Spielt Musik vom Handy auf dem Radio: das Handy stellt die Stücke per HTTP bereit,
 * der UPnP-Abspieler des Radios holt sie ab. Die Warteschlange läuft hier weiter, auch wenn
 * die App im Hintergrund ist (dafür hält [RadioService] den Prozess am Leben).
 */
object LocalPlayer {

    var ui by mutableStateOf(LocalPlayerUi())
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var app: Context? = null
    private var server: MusicServer? = null
    private var renderer: Renderer? = null
    private var address: InetAddress? = null
    private var monitor: Job? = null
    private var dmrMode: Int? = null
    var pin: String = "1234"
        private set

    /** Warteschlange [queue] ab [index] auf dem Radio [host] abspielen. */
    fun play(context: Context, host: String, pin: String, radioName: String, dmrModeKey: Int?, queue: List<Track>, index: Int) {
        val ctx = context.applicationContext
        app = ctx
        this.pin = pin
        dmrMode = dmrModeKey
        val sameRadio = ui.radioHost == host
        ui = LocalPlayerUi(active = true, starting = true, radioHost = host, radioName = radioName, queue = queue, index = index)
        monitor?.cancel()
        monitor = scope.launch {
            try {
                if (!sameRadio || renderer == null) {
                    renderer = Renderer.find(ctx, host) ?: throw IOException(ctx.getString(R.string.error_local_no_renderer))
                }
                switchToDmr(host)
                val addr = localAddressFor(host)
                if (server == null || address != addr) {
                    server?.stop()
                    server = MusicServer(ctx).also { withContext(Dispatchers.IO) { it.start(addr) } }
                    address = addr
                }
                server?.allowed = queue.associateBy { it.id }
                RadioService.update(ctx)
                startTrack(index)
                watch()
            } catch (e: IOException) {
                fail(e.message ?: ctx.getString(R.string.error_local_play))
            }
        }
    }

    /** Start fehlgeschlagen: aufräumen, nur die Meldung bleibt stehen. */
    private fun fail(message: String) {
        server?.stop()
        server = null
        address = null
        ui = LocalPlayerUi(radioHost = ui.radioHost, radioName = ui.radioName, error = message)
        app?.let { RadioService.update(it) }
    }

    fun next() {
        if (ui.hasNext) jump(ui.index + 1)
    }

    fun previous() {
        if (ui.hasPrevious) jump(ui.index - 1) else jump(ui.index)
    }

    /** Wiedergabe anhalten bzw. das aktuelle Stück wieder starten. */
    fun toggle() {
        if (ui.playing) {
            scope.launch {
                runCatching { renderer?.stop() }
                ui = ui.copy(playing = false)
            }
        } else {
            jump(ui.index)
        }
    }

    private fun jump(i: Int) {
        if (!ui.active || i !in ui.queue.indices) return
        monitor?.cancel()
        monitor = scope.launch {
            try {
                startTrack(i)
                watch()
            } catch (e: IOException) {
                ui = ui.copy(playing = false, error = e.message)
            }
        }
    }

    /** Alles beenden: Radio stoppen, Server zu, Benachrichtigung weg. */
    fun stop() {
        monitor?.cancel()
        val r = renderer
        scope.launch { runCatching { r?.stop() } }
        server?.stop()
        server = null
        address = null
        ui = LocalPlayerUi()
        app?.let { RadioService.update(it) }
    }

    private suspend fun startTrack(i: Int) {
        val track = ui.queue.getOrNull(i) ?: return
        val s = server ?: return
        val addr = address ?: return
        ui = ui.copy(index = i, starting = true, error = null)
        val url = "http://${addr.hostAddress}:${s.port}/track/${track.id}.${extension(track.mime)}"
        renderer?.play(url, track)
        ui = ui.copy(starting = false, playing = true)
        app?.let { RadioService.update(it) }
    }

    /**
     * Ende eines Stücks erkennen und das nächste starten. Wechselt jemand am Radio die Quelle,
     * endet die Wiedergabe vom Handy.
     */
    private suspend fun watch() {
        val host = ui.radioHost ?: return
        val fs = FsApiClient(host, pin)
        var seenPlaying = false
        var rounds = 0
        while (scope.isActive && ui.active) {
            delay(2000)
            val state = runCatching { renderer?.state() }.getOrNull()
            if (state == "PLAYING" || state == "TRANSITIONING") seenPlaying = true
            if (seenPlaying && (state == "STOPPED" || state == "NO_MEDIA_PRESENT") && ui.playing) {
                if (ui.hasNext) {
                    seenPlaying = false
                    startTrack(ui.index + 1)
                } else {
                    ui = ui.copy(playing = false)
                    app?.let { RadioService.update(it) }
                }
            }
            if (++rounds % 3 == 0) {
                val mode = runCatching { fs.get(Nodes.MODE)?.trim()?.toIntOrNull() }.getOrNull()
                val dmr = dmrMode
                if (dmr != null && mode != null && mode != dmr) {
                    stop()
                    return
                }
            }
        }
    }

    /** Das Radio auf seine UPnP-Quelle umschalten, damit es die Stücke annimmt. */
    private suspend fun switchToDmr(host: String) {
        val key = dmrMode ?: return
        val fs = FsApiClient(host, pin)
        if (fs.get(Nodes.POWER)?.trim() != "1") fs.set(Nodes.POWER, "1")
        if (fs.get(Nodes.MODE)?.trim()?.toIntOrNull() != key) {
            fs.set(Nodes.MODE, key.toString())
            delay(1500)
        }
    }

    /** Die eigene Adresse in dem Netz, in dem das Radio ist (dort muss der Server lauschen). */
    private suspend fun localAddressFor(host: String): InetAddress = withContext(Dispatchers.IO) {
        DatagramSocket().use { s ->
            s.connect(InetAddress.getByName(host), 9)
            s.localAddress
        }
    }

    private fun extension(mime: String) = when (mime.lowercase()) {
        "audio/flac", "audio/x-flac" -> "flac"
        "audio/mp4", "audio/m4a", "audio/x-m4a" -> "m4a"
        "audio/aac" -> "aac"
        "audio/ogg", "application/ogg" -> "ogg"
        "audio/wav", "audio/x-wav" -> "wav"
        else -> "mp3"
    }
}
