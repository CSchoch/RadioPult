package de.radiopult.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import de.radiopult.app.fsapi.FsApiClient
import de.radiopult.app.fsapi.Nodes
import de.radiopult.app.fsapi.PlayCaps
import de.radiopult.app.localmusic.LocalPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Hält die Steuerung im Hintergrund am Leben:
 * - auf Wunsch (Einstellung, aus ab Werk) eine Medien-Benachrichtigung mit Sperrbildschirm-Steuerung
 *   und Lautstärketasten für das verbundene Radio,
 * - immer, solange Musik vom Handy auf dem Radio läuft (sonst beendet Android den kleinen Server).
 */
class RadioService : Service() {

    private data class Target(val host: String, val pin: String, val name: String)

    /** Was die Benachrichtigung zeigt. */
    private data class State(
        val power: Boolean = true,
        val playing: Boolean = false,
        val title: String = "",
        val text: String = "",
        val coverUrl: String? = null,
        val volume: Int = 0,
        val canSkip: Boolean = true,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var session: MediaSession
    private var pollJob: Job? = null
    private var client: FsApiClient? = null
    private var volumeMax = 32
    private var volumeProvider: VolumeProvider? = null
    private var state = State()
    private var cover: Bitmap? = null
    private var coverFor: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            },
        )
        session = MediaSession(this, "RadioPult").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = playStop(play = true)
                override fun onPause() = playStop(play = false)
                override fun onStop() = playStop(play = false)
                override fun onSkipToNext() = skip(next = true)
                override fun onSkipToPrevious() = skip(next = false)
            })
            setSessionActivity(openApp())
            isActive = true
        }
        // muss sofort geschehen, sonst beendet Android den Dienst
        val notification = build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        poll()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> playStop(play = !state.playing)
            ACTION_NEXT -> skip(next = true)
            ACTION_PREVIOUS -> skip(next = false)
            ACTION_CLOSE -> {
                if (LocalPlayer.ui.active) LocalPlayer.stop()
                dismissed = true
                update(this)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        instance = null
        scope.cancel()
        session.release()
        super.onDestroy()
    }

    private fun shutdown() {
        pollJob?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Gleich neu lesen (nach einem Befehl oder wenn sich etwas geändert hat). */
    private fun refreshSoon() = poll()

    private fun poll() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                // inzwischen getrennt, ausgeschaltet oder Musik vom Handy beendet
                if (!needed(this@RadioService)) return@launch shutdown()
                read()
                show()
                delay(POLL_MS)
            }
        }
    }

    /** Das Radio, das die Benachrichtigung steuert: das verbundene, sonst das, auf dem Musik vom Handy läuft. */
    private fun target(): Target? {
        radio?.let { return it }
        val local = LocalPlayer.ui
        val host = local.radioHost ?: return null
        return Target(host, LocalPlayer.pin, local.radioName)
    }

    private suspend fun read() {
        val t = target() ?: return
        val c = client?.takeIf { it.host == t.host } ?: FsApiClient(t.host, t.pin).also {
            client = it
            volumeMax = runCatching { it.get(Nodes.VOLUME_STEPS)?.trim()?.toIntOrNull() }.getOrNull()?.minus(1)?.coerceAtLeast(1) ?: 32
            volumeProvider = null
        }
        try {
            val v = c.getAll(
                listOf(
                    Nodes.POWER, Nodes.PLAY_STATUS, Nodes.INFO_NAME, Nodes.INFO_TEXT, Nodes.INFO_ARTIST,
                    Nodes.INFO_GRAPHIC, Nodes.VOLUME, Nodes.PLAY_CAPS,
                ),
            )
            val status = v[Nodes.PLAY_STATUS]?.trim()?.toIntOrNull()
            val caps = v[Nodes.PLAY_CAPS]?.trim()?.toLongOrNull()?.let { PlayCaps(it) }
            val artist = v[Nodes.INFO_ARTIST]?.trim().orEmpty()
            val text = v[Nodes.INFO_TEXT]?.trim().orEmpty()
            state = State(
                power = v[Nodes.POWER]?.trim() == "1",
                playing = status == 1 || status == 2 || status == 4,
                title = v[Nodes.INFO_NAME]?.trim().orEmpty(),
                text = listOf(artist, text).filter { it.isNotEmpty() }.distinct().joinToString(" – "),
                coverUrl = v[Nodes.INFO_GRAPHIC]?.trim()?.takeIf { it.startsWith("http") },
                volume = v[Nodes.VOLUME]?.trim()?.toIntOrNull() ?: state.volume,
                canSkip = caps == null || caps.bits == 0L || caps.skipNext || caps.skipPrevious,
            )
        } catch (ignored: IOException) {
            // Radio gerade nicht erreichbar: letzten Stand weiter zeigen
        }
        loadCover(state.coverUrl)
    }

    private suspend fun loadCover(url: String?) {
        if (url == coverFor) return
        coverFor = url
        cover = url?.let {
            withContext(Dispatchers.IO) {
                runCatching {
                    val conn = URL(it).openConnection() as HttpURLConnection
                    try {
                        conn.connectTimeout = 3000
                        conn.readTimeout = 5000
                        conn.inputStream.use { s -> BitmapFactory.decodeStream(s) }
                    } finally {
                        conn.disconnect()
                    }
                }.getOrNull()?.let { b ->
                    val scale = 512f / maxOf(b.width, b.height)
                    if (scale < 1f) Bitmap.createScaledBitmap(b, (b.width * scale).toInt(), (b.height * scale).toInt(), true) else b
                }
            }
        }
    }

    private fun show() {
        val local = LocalPlayer.ui.takeIf { it.active }
        val t = target()
        val title: String
        val text: String
        val playing: Boolean
        val canSkip: Boolean
        if (local != null) {
            val track = local.track
            title = track?.title ?: getString(R.string.local_music)
            text = track?.artist.orEmpty()
            playing = local.playing
            canSkip = true
        } else {
            title = if (state.power) state.title.ifEmpty { t?.name.orEmpty() } else t?.name.orEmpty()
            text = if (state.power) state.text else getString(R.string.notification_standby)
            playing = state.power && state.playing
            canSkip = state.power && state.canSkip
        }
        var actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_STOP or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PAUSE
        if (canSkip) actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_STOPPED, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build(),
        )
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, text)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, t?.name.orEmpty())
                .apply { if (local == null) cover?.let { putBitmap(MediaMetadata.METADATA_KEY_ART, it) } }
                .build(),
        )
        // Lautstärketasten bei gesperrtem Bildschirm steuern das Radio
        if (t != null) {
            val provider = volumeProvider ?: object : VolumeProvider(VOLUME_CONTROL_ABSOLUTE, volumeMax, state.volume) {
                override fun onSetVolumeTo(volume: Int) = setVolume(volume)
                override fun onAdjustVolume(direction: Int) = setVolume(currentVolume + direction)
            }.also {
                volumeProvider = it
                session.setPlaybackToRemote(it)
            }
            provider.currentVolume = state.volume
        }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, build(title, text, playing, canSkip, t?.name))
    }

    private fun build(title: String = "", text: String = "", playing: Boolean = false, canSkip: Boolean = true, radioName: String? = null): Notification {
        val actions = buildList {
            if (canSkip) add(action(R.drawable.ic_skip_previous, R.string.action_previous, ACTION_PREVIOUS))
            add(
                if (playing) action(R.drawable.ic_stop, R.string.action_stop, ACTION_TOGGLE)
                else action(R.drawable.ic_play, R.string.action_play, ACTION_TOGGLE),
            )
            if (canSkip) add(action(R.drawable.ic_skip_next, R.string.action_next, ACTION_NEXT))
            add(action(R.drawable.ic_close, R.string.action_close, ACTION_CLOSE))
        }
        val compact = if (canSkip) intArrayOf(0, 1, 2) else intArrayOf(0)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title.ifEmpty { getString(R.string.app_name) })
            .setContentText(text)
            .setSubText(radioName)
            .setLargeIcon(if (LocalPlayer.ui.active) null else cover)
            .setContentIntent(openApp())
            .setDeleteIntent(serviceIntent(ACTION_CLOSE))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(*compact))
            .apply { actions.forEach { addAction(it) } }
            .build()
    }

    private fun action(icon: Int, label: Int, action: String) =
        Notification.Action.Builder(Icon.createWithResource(this, icon), getString(label), serviceIntent(action)).build()

    private fun serviceIntent(action: String): PendingIntent = PendingIntent.getService(
        this,
        action.hashCode(),
        Intent(this, RadioService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun radioCommand(block: suspend (FsApiClient) -> Unit) {
        val t = target() ?: return
        val c = client?.takeIf { it.host == t.host } ?: FsApiClient(t.host, t.pin).also { client = it }
        scope.launch {
            runCatching { block(c) }
            delay(500)
            refreshSoon()
        }
    }

    private fun playStop(play: Boolean) {
        if (LocalPlayer.ui.active) {
            if (play != LocalPlayer.ui.playing) LocalPlayer.toggle()
            return
        }
        state = state.copy(playing = play)
        radioCommand { c ->
            if (!state.power || c.get(Nodes.POWER)?.trim() != "1") c.set(Nodes.POWER, "1") else c.playOrStop(play)
        }
    }

    private fun skip(next: Boolean) {
        if (LocalPlayer.ui.active) return if (next) LocalPlayer.next() else LocalPlayer.previous()
        radioCommand { it.set(Nodes.PLAY_CONTROL, if (next) "3" else "4") }
    }

    private fun setVolume(value: Int) {
        val v = value.coerceIn(0, volumeMax)
        state = state.copy(volume = v)
        volumeProvider?.currentVolume = v
        radioCommand { it.set(Nodes.VOLUME, v.toString()) }
    }

    companion object {
        private const val CHANNEL = "playback"
        private const val NOTIFICATION_ID = 1
        private const val POLL_MS = 3000L
        private const val PREFS = "radiopult"
        private const val PREF_NOTIFICATION = "notification"
        private const val ACTION_TOGGLE = "de.radiopult.app.TOGGLE"
        private const val ACTION_NEXT = "de.radiopult.app.NEXT"
        private const val ACTION_PREVIOUS = "de.radiopult.app.PREVIOUS"
        private const val ACTION_CLOSE = "de.radiopult.app.CLOSE"

        // nur auf dem Hauptthread benutzt
        private var instance: RadioService? = null
        private var radio: Target? = null

        /** Weggewischt: erst beim nächsten Verbinden wieder zeigen. */
        private var dismissed = false

        fun notificationEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PREF_NOTIFICATION, false)

        fun setNotificationEnabled(context: Context, on: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PREF_NOTIFICATION, on).apply()
            if (on) dismissed = false
            update(context)
        }

        /** Mit einem Radio verbunden (aus der App). */
        fun attach(context: Context, host: String, pin: String, name: String) {
            radio = Target(host, pin, name)
            dismissed = false
            update(context)
        }

        fun detach(context: Context) {
            radio = null
            update(context)
        }

        /** Dienst starten, auffrischen oder beenden – je nachdem, ob gerade etwas zu zeigen ist. */
        private fun needed(context: Context) =
            LocalPlayer.ui.active || (radio != null && !dismissed && notificationEnabled(context))

        fun update(context: Context) {
            val needed = needed(context)
            val running = instance
            when {
                running != null && needed -> running.refreshSoon()
                running != null -> running.shutdown()
                needed -> runCatching {
                    context.startForegroundService(Intent(context, RadioService::class.java))
                }
            }
        }
    }
}
