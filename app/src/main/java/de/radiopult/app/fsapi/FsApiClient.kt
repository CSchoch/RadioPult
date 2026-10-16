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

/** Eintrag einer Liste; [types] merkt sich den Datentyp je Feld (z. B. "c8_array", "u8", "array"). */
data class FsItem(val key: Int, val fields: Map<String, String>, val types: Map<String, String> = emptyMap()) {

    /** Feld als Text. Byte-Arrays (z. B. WLAN-Namen) liefert das Radio hex-kodiert. */
    fun text(name: String): String {
        val raw = fields[name]?.trim().orEmpty()
        if (types[name] != "array") return raw
        val bytes = hexToBytes(raw) ?: return raw
        return String(bytes, Charsets.UTF_8).trimEnd('\u0000')
    }
}

internal fun hexToBytes(hex: String): ByteArray? {
    if (hex.length % 2 != 0 || hex.any { Character.digit(it, 16) < 0 }) return null
    return ByteArray(hex.length / 2) { i -> hex.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
}

private data class FsResponse(
    val status: String,
    val value: String?,
    val items: List<FsItem>,
    val listEnd: Boolean,
    val valueType: String? = null,
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

    // Favoriten bearbeiten (Schlüssel = Speicherplatz, ab 0)
    const val ADD_PRESET = "netRemote.play.addPreset"
    const val PRESET_DELETE = "netRemote.nav.preset.delete"
    const val PRESET_SWAP_1 = "netRemote.nav.preset.swap.index1"
    const val PRESET_SWAP_2 = "netRemote.nav.preset.swap.index2"
    const val PRESET_SWAP = "netRemote.nav.preset.swap.swap"

    // Senderliste / Titelliste der aktuellen Quelle
    const val NAV_LIST = "netRemote.nav.list"
    const val NAV_STATUS = "netRemote.nav.status"
    const val NAV_NAVIGATE = "netRemote.nav.action.navigate"
    const val NAV_SELECT_ITEM = "netRemote.nav.action.selectItem"
    const val NAV_SEARCH_TERM = "netRemote.nav.searchTerm"
    const val NAV_DAB_SCAN = "netRemote.nav.action.dabScan"

    // Einstellungen
    const val MODEL_NAME = "netRemote.sys.info.modelName"
    const val VERSION = "netRemote.sys.info.version"
    const val IP_ADDRESS = "netRemote.sys.net.ipConfig.address"
    const val WLAN_ENABLED = "netRemote.sys.net.wlan.interfaceEnable"
    const val WLAN_MAC = "netRemote.sys.net.wlan.macAddress"
    const val WLAN_SSID = "netRemote.sys.net.wlan.connectedSSID"
    const val WLAN_RSSI = "netRemote.sys.net.wlan.rssi"
    const val WIRED_MAC = "netRemote.sys.net.wired.macAddress"
    const val LANG = "netRemote.sys.lang"
    const val VALID_LANGS = "netRemote.sys.caps.validLang"
    const val SLEEP = "netRemote.sys.sleep"
    const val CLOCK_MODE = "netRemote.sys.clock.mode"
    const val CLOCK_SOURCE = "netRemote.sys.clock.source"
    const val CLOCK_SOURCES = "netRemote.sys.caps.clockSourceList"
    const val FACTORY_RESET = "netRemote.sys.factoryReset"

    /** Wert eines Bands im eigenen Equalizer („My EQ“), meist 0 = Bass, 1 = Höhen. */
    fun eqCustom(index: Int) = "netRemote.sys.audio.eqCustom.param$index"

    // Ersteinrichtung (nur im Setup-Modus des Radios sinnvoll)
    const val WLAN_SCAN = "netRemote.sys.net.wlan.scan"
    const val WLAN_SCAN_LIST = "netRemote.sys.net.wlan.scanList"
    const val WLAN_SELECT_AP = "netRemote.sys.net.wlan.selectAP"
    const val WLAN_SET_SSID = "netRemote.sys.net.wlan.setSSID"
    const val WLAN_SET_AUTH_TYPE = "netRemote.sys.net.wlan.setAuthType"
    const val WLAN_SET_ENC_TYPE = "netRemote.sys.net.wlan.setEncType"
    const val WLAN_SET_PASSPHRASE = "netRemote.sys.net.wlan.setPassphrase"
    const val NET_COMMIT = "netRemote.sys.net.commitChanges"
    const val RSA_STATUS = "netRemote.sys.rsa.status"
    const val RSA_PUBLIC_KEY = "netRemote.sys.rsa.publicKey"
}

/**
 * Minimaler Client für die Radio-Schnittstelle.
 * Jede Anfrage trägt die PIN mit, damit keine Sitzung nötig ist
 * (eine Sitzung würde andere Apps am selben Radio abmelden).
 */
class FsApiClient(host: String, private val pin: String) {

    private val base = "http://$host/fsapi"
    private val mutex = Mutex() // die Geräte mögen keine parallelen Anfragen

    /** Byte-Arrays (z. B. der WLAN-Name) kommen hex-kodiert und werden als Text zurückgegeben. */
    suspend fun get(node: String): String? {
        val r = call("GET", node)
        if (r.status != "FS_OK") return null
        val raw = r.value.orEmpty()
        if (r.valueType != "array") return raw
        val bytes = hexToBytes(raw.trim()) ?: return raw
        return String(bytes, Charsets.UTF_8).trimEnd('\u0000')
    }

    suspend fun set(node: String, value: String): Boolean =
        call("SET", node, extra = "&value=${enc(value)}").status == "FS_OK"

    /**
     * Ganze Liste seitenweise lesen. Mit [strict] gilt eine Antwort ohne FS_OK/FS_LIST_END als Fehler
     * (IOException) statt als Listenende – sonst ist eine halbe Liste nicht von einer vollständigen zu unterscheiden.
     */
    suspend fun list(node: String, pageSize: Int = 50, strict: Boolean = false): List<FsItem> {
        val all = mutableListOf<FsItem>()
        var start = -1
        repeat(20) { // Sicherheitsgrenze: höchstens 20 Seiten
            val r = call("LIST_GET_NEXT", node, extra = "&maxItems=$pageSize", pathSuffix = "/$start")
            if (r.status == "FS_LIST_END") return all
            if (r.status != "FS_OK") {
                if (strict) throw IOException("Liste $node nicht lesbar (${r.status})")
                return all
            }
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
        var types = mutableMapOf<String, String>()
        var fieldName: String? = null
        var inValue = false
        var valueType: String? = null
        val path = ArrayDeque<String>()

        while (true) {
            when (p.next()) {
                XmlPullParser.START_TAG -> {
                    when (p.name) {
                        "item" -> {
                            itemKey = p.getAttributeValue(null, "key")?.toIntOrNull() ?: -1
                            fields = mutableMapOf()
                            types = mutableMapOf()
                        }
                        "field" -> fieldName = p.getAttributeValue(null, "name")
                        "listend" -> listEnd = true
                        "value" -> inValue = true
                    }
                    fieldName?.let { if (p.name != "field") types[it] = p.name }
                    if (inValue && p.name != "value" && fieldName == null) valueType = p.name
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
                            items += FsItem(itemKey ?: -1, fields, types)
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
        return FsResponse(status, value, items, listEnd, valueType)
    }
}
