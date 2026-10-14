package de.radiopult.app.fsapi

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Fehler beim Sprechen mit dem Radio (z. B. falsche PIN). */
class FsApiException(message: String) : IOException(message)

data class FsItem(val key: Int, val fields: Map<String, String>)

private data class FsResponse(
    val status: String,
    val value: String?,
    val items: List<FsItem>,
    val listEnd: Boolean,
)

/** Knoten der HTTP-Schnittstelle, die die Frontier-Silicon-Radios anbieten. */
object Nodes {
    const val FRIENDLY_NAME = "netRemote.sys.info.friendlyName"
    const val POWER = "netRemote.sys.power"
    const val MODE = "netRemote.sys.mode"
    const val VALID_MODES = "netRemote.sys.caps.validModes"
    const val VOLUME = "netRemote.sys.audio.volume"
    const val VOLUME_STEPS = "netRemote.sys.caps.volumeSteps"
    const val MUTE = "netRemote.sys.audio.mute"
    const val INFO_NAME = "netRemote.play.info.name"
    const val INFO_TEXT = "netRemote.play.info.text"
    const val INFO_ARTIST = "netRemote.play.info.artist"
    const val INFO_GRAPHIC = "netRemote.play.info.graphicUri"
    const val PLAY_STATUS = "netRemote.play.status"
    const val PLAY_CONTROL = "netRemote.play.control"
    const val NAV_STATE = "netRemote.nav.state"
    const val PRESETS = "netRemote.nav.presets"
    const val SELECT_PRESET = "netRemote.nav.action.selectPreset"
    const val EQ_PRESETS = "netRemote.sys.caps.eqPresets"
    const val EQ_BANDS = "netRemote.sys.caps.eqBands"
    const val EQ_PRESET = "netRemote.sys.audio.eqPreset"
    const val EQ_LOUDNESS = "netRemote.sys.audio.eqLoudness"

    /** Wert eines Bands im eigenen Equalizer („My EQ“), meist 0 = Bass, 1 = Höhen. */
    fun eqCustom(index: Int) = "netRemote.sys.audio.eqCustom.param$index"
}

/**
 * Minimaler Client für die Radio-Schnittstelle.
 * Jede Anfrage trägt die PIN mit, damit keine Sitzung nötig ist
 * (eine Sitzung würde andere Apps am selben Radio abmelden).
 */
class FsApiClient(host: String, private val pin: String) {

    private val base = "http://$host/fsapi"
    private val mutex = Mutex() // die Geräte mögen keine parallelen Anfragen

    suspend fun get(node: String): String? {
        val r = call("GET", node)
        return if (r.status == "FS_OK") r.value.orEmpty() else null
    }

    suspend fun set(node: String, value: String): Boolean =
        call("SET", node, extra = "&value=${enc(value)}").status == "FS_OK"

    suspend fun list(node: String, pageSize: Int = 50): List<FsItem> {
        val all = mutableListOf<FsItem>()
        var start = -1
        repeat(20) { // Sicherheitsgrenze: höchstens 20 Seiten
            val r = call("LIST_GET_NEXT", node, extra = "&maxItems=$pageSize", pathSuffix = "/$start")
            if (r.status != "FS_OK") return all
            all += r.items
            if (r.listEnd || r.items.isEmpty()) return all
            start = r.items.last().key
        }
        return all
    }

    private suspend fun call(
        op: String,
        node: String,
        extra: String = "",
        pathSuffix: String = "",
    ): FsResponse = mutex.withLock {
        withContext(Dispatchers.IO) {
            val url = URL("$base/$op/$node$pathSuffix?pin=${enc(pin)}$extra")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 6000
            try {
                when (val code = conn.responseCode) {
                    200 -> conn.inputStream.use { parse(it) }
                    403 -> throw FsApiException("PIN falsch")
                    404 -> FsResponse("FS_NODE_DOES_NOT_EXIST", null, emptyList(), true)
                    else -> throw FsApiException("Radio antwortet mit HTTP $code")
                }
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun parse(input: InputStream): FsResponse {
        val p = Xml.newPullParser()
        p.setInput(input, null)

        var status = ""
        var value: String? = null
        var listEnd = false
        val items = mutableListOf<FsItem>()

        var itemKey: Int? = null
        var fields = mutableMapOf<String, String>()
        var fieldName: String? = null
        var inValue = false
        val path = ArrayDeque<String>()

        while (true) {
            when (p.next()) {
                XmlPullParser.START_TAG -> {
                    when (p.name) {
                        "item" -> {
                            itemKey = p.getAttributeValue(null, "key")?.toIntOrNull() ?: -1
                            fields = mutableMapOf()
                        }
                        "field" -> fieldName = p.getAttributeValue(null, "name")
                        "listend" -> listEnd = true
                        "value" -> inValue = true
                    }
                    path.addLast(p.name)
                }

                XmlPullParser.TEXT -> {
                    val parent = path.lastOrNull()
                    val text = p.text ?: ""
                    val fName = fieldName
                    when {
                        parent == "status" -> status = text.trim()
                        fName != null && parent != "field" -> fields[fName] = (fields[fName] ?: "") + text
                        inValue && parent != "value" -> value = (value ?: "") + text
                    }
                }

                XmlPullParser.END_TAG -> {
                    when (p.name) {
                        "item" -> {
                            items += FsItem(itemKey ?: -1, fields)
                            itemKey = null
                        }
                        "field" -> fieldName = null
                        "value" -> inValue = false
                    }
                    path.removeLastOrNull()
                }

                XmlPullParser.END_DOCUMENT -> break
            }
        }
        return FsResponse(status, value, items, listEnd)
    }
}
