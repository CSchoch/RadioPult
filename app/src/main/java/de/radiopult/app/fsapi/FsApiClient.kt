package de.radiopult.app.fsapi

import android.content.Context
import android.util.Xml
import androidx.annotation.StringRes
import de.radiopult.app.R
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

/** Fehler beim Sprechen mit dem Radio (z. B. falsche PIN); [text] ist die Meldung für die Anzeige. */
open class FsApiException(@StringRes val text: Int, vararg val args: Any) : IOException() {
    fun describe(context: Context): String = context.getString(text, *args)
}

/** Das Radio hat die PIN abgelehnt. */
class WrongPinException : FsApiException(R.string.error_wrong_pin)

/** Eintrag einer Liste; Feldnamen klein geschrieben; [types] merkt sich den Datentyp je Feld (z. B. "c8_array", "u8", "array"). */
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

internal data class FsResponse(
    val status: String,
    val value: String?,
    val items: List<FsItem>,
    val listEnd: Boolean,
    val valueType: String? = null,
    /** Nur bei GET_MULTIPLE: zu welchem Knoten die Antwort gehört. */
    val node: String? = null,
) {
    /** Wert als Text; Byte-Arrays (z. B. der WLAN-Name) kommen hex-kodiert. */
    fun text(): String? {
        if (status != "FS_OK") return null
        val raw = value.orEmpty()
        if (valueType != "array") return raw
        val bytes = hexToBytes(raw.trim()) ?: return raw
        return String(bytes, Charsets.UTF_8).trimEnd('\u0000')
    }
}

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

    /** Bitmaske je Quelle: Bit 0 = Liste/Navigation, Bit 1 = Favoriten, Bit 2 = Favoriten bearbeitbar. */
    const val NAV_CAPS = "netRemote.nav.caps"
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

    // Wiedergabe: was die Quelle kann (Bitmaske, s. PlayCaps), Zufall, Wiederholen, Position
    const val PLAY_CAPS = "netRemote.play.caps"
    const val PLAY_SHUFFLE = "netRemote.play.shuffle"
    const val PLAY_REPEAT = "netRemote.play.repeat"
    const val PLAY_POSITION = "netRemote.play.position"
    const val PLAY_RATING = "netRemote.play.rating"
    const val INFO_DURATION = "netRemote.play.info.duration"
    const val INFO_ALBUM = "netRemote.play.info.album"
    const val INFO_DESCRIPTION = "netRemote.play.info.description"
    const val INFO_ARTIST_DESCRIPTION = "netRemote.play.info.artistDescription"
    const val INFO_ALBUM_DESCRIPTION = "netRemote.play.info.albumDescription"
    const val INFO_PROVIDER = "netRemote.play.info.providerName"
    const val ADD_PRESET_STATUS = "netRemote.play.addPresetStatus"
    const val ALERT_TONE = "netRemote.play.alerttone"

    // UKW: Frequenz in kHz und der Bereich, den das Radio empfängt
    const val FREQUENCY = "netRemote.play.frequency"
    const val FM_LOWER = "netRemote.sys.caps.fmFreqRange.lower"
    const val FM_UPPER = "netRemote.sys.caps.fmFreqRange.upper"
    const val FM_STEP = "netRemote.sys.caps.fmFreqRange.stepSize"

    // Liste: Favorit, der gerade läuft; DAB-Suchlauf; Beschreibung des Ordners (Album, Künstler)
    const val CURRENT_PRESET = "netRemote.nav.preset.currentPreset"
    const val NAV_DAB_SCAN_UPDATE = "netRemote.nav.dabScanUpdate"
    const val NAV_DAB_PRUNE = "netRemote.nav.action.dabPrune"
    const val NAV_DESCRIPTION = "netRemote.nav.description"
    const val NAV_RELEASE_DATE = "netRemote.nav.releaseDate"

    // Kontextmenü eines Listeneintrags (eigene Liste neben der normalen)
    const val NAV_CONTEXT = "netRemote.nav.action.context"
    const val NAV_CONTEXT_LIST = "netRemote.nav.context.list"
    const val NAV_CONTEXT_NAVIGATE = "netRemote.nav.context.navigate"
    const val NAV_CONTEXT_STATUS = "netRemote.nav.context.status"
    const val NAV_CONTEXT_DEPTH = "netRemote.nav.context.depth"

    // Formulare in der Liste (z. B. Anmeldung bei einem Musikdienst)
    const val NAV_FORM_ITEM = "netRemote.nav.form.item"
    const val NAV_FORM_OPTION = "netRemote.nav.form.option"
    const val NAV_FORM_DATA = "netRemote.nav.formData"
    const val NAV_ENC_FORM_DATA = "netRemote.nav.encFormData"

    // Uhr: Zeitzone, Sommerzeit, Datum und Uhrzeit von Hand
    const val CLOCK_TIME_ZONE = "netRemote.sys.clock.timeZone"
    const val CLOCK_UTC_OFFSET = "netRemote.sys.clock.utcOffset"
    const val CLOCK_DST = "netRemote.sys.clock.dst"
    const val CLOCK_LOCAL_DATE = "netRemote.sys.clock.localDate"
    const val CLOCK_LOCAL_TIME = "netRemote.sys.clock.localTime"
    const val UTC_SETTINGS = "netRemote.sys.caps.utcSettingsList"

    // Bluetooth
    const val BT_DISCOVERABLE = "netRemote.bluetooth.discoverableState"
    const val BT_DEVICES = "netRemote.bluetooth.connectedDevices"

    // Netzwerk
    const val IP_DHCP = "netRemote.sys.net.ipConfig.dhcp"
    const val IP_SUBNET = "netRemote.sys.net.ipConfig.subnetMask"
    const val IP_GATEWAY = "netRemote.sys.net.ipConfig.gateway"
    const val IP_DNS1 = "netRemote.sys.net.ipConfig.dnsPrimary"
    const val IP_DNS2 = "netRemote.sys.net.ipConfig.dnsSecondary"
    const val WIRED_ENABLED = "netRemote.sys.net.wired.interfaceEnable"
    const val WLAN_WPS = "netRemote.sys.net.wlan.performWPS"

    // Klang und Gerät
    const val EXT_DELAY = "netRemote.sys.audio.extStaticDelay"
    const val EXT_DELAY_MAX = "netRemote.sys.caps.extStaticDelayMax"
    const val STREAM_QUALITY = "netRemote.sys.audio.airableQuality"
    const val AUDIO_QUALITY = "netRemote.sys.audio.quality"
    const val CONTROLLER_NAME = "netRemote.sys.info.controllerName"
    const val DMR_UUID = "netRemote.sys.info.dmruuid"

    // Multiroom: Gruppen mehrerer Radios
    const val MR_PROTOCOL = "netRemote.multiroom.caps.protocolVersion"
    const val MR_MAX_CLIENTS = "netRemote.multiroom.caps.maxClients"
    const val MR_DEVICES = "netRemote.multiroom.device.listAll"
    const val MR_CLIENT_INDEX = "netRemote.multiroom.device.clientIndex"
    const val MR_GROUP_NAME = "netRemote.multiroom.group.name"
    const val MR_GROUP_ID = "netRemote.multiroom.group.id"
    const val MR_GROUP_STATE = "netRemote.multiroom.group.state"
    const val MR_GROUP_STREAMABLE = "netRemote.multiroom.group.streamable"
    const val MR_GROUP_CREATE = "netRemote.multiroom.group.create"
    const val MR_GROUP_DESTROY = "netRemote.multiroom.group.destroy"
    const val MR_GROUP_ADD = "netRemote.multiroom.group.addClient"
    const val MR_GROUP_REMOVE = "netRemote.multiroom.group.removeClient"
    const val MR_ATTACHED = "netRemote.multiroom.group.attachedClients"
    const val MR_MASTER_VOLUME = "netRemote.multiroom.group.masterVolume"
    fun mrClientVolume(index: Int) = "netRemote.multiroom.client.volume$index"
    fun mrClientMute(index: Int) = "netRemote.multiroom.client.mute$index"

    // Stereopaar aus zwei Radios
    const val MC_STATE = "netRemote.multichannel.system.state"
    const val MC_NAME = "netRemote.multichannel.system.name"
    const val MC_CREATE = "netRemote.multichannel.system.create"
    const val MC_ADD_SECONDARY = "netRemote.multichannel.system.addsecondary"
    const val MC_UNPAIR = "netRemote.multichannel.system.unpair"
    const val MC_PRIMARY_MASK = "netRemote.multichannel.primary.channelmask"
    const val MC_SECONDARY_MASK = "netRemote.multichannel.secondary0.channelmask"
    const val MC_SECONDARY_STATUS = "netRemote.multichannel.secondary0.status"

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

/** Höchstens so viele Knoten je GET_MULTIPLE – lange Anfragen mögen manche Radios nicht. */
private const val MULTIPLE_MAX = 10

/**
 * Minimaler Client für die Radio-Schnittstelle.
 * Jede Anfrage trägt die PIN mit, damit keine Sitzung nötig ist
 * (eine Sitzung würde andere Apps am selben Radio abmelden).
 */
class FsApiClient(val host: String, private val pin: String) {

    private val base = "http://$host/fsapi"
    private val mutex = Mutex() // die Geräte mögen keine parallelen Anfragen

    /** Ältere Radios kennen GET_MULTIPLE nicht; dann einzeln fragen. */
    private var multipleSupported = true

    /** Knoten, die dieses Radio gar nicht hat (HTTP 404) – beim Einzelabfragen überspringen. */
    private val missing: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())

    /** Byte-Arrays (z. B. der WLAN-Name) kommen hex-kodiert und werden als Text zurückgegeben. */
    suspend fun get(node: String): String? = call("GET", node).text()

    /**
     * Mehrere Knoten mit einer Anfrage lesen (GET_MULTIPLE), sonst einzeln.
     * Fehlende Knoten fehlen auch in der Antwort.
     */
    suspend fun getAll(nodes: List<String>): Map<String, String> {
        if (nodes.isEmpty()) return emptyMap()
        if (multipleSupported && nodes.size > 1) {
            val result = mutableMapOf<String, String>()
            for (chunk in nodes.chunked(MULTIPLE_MAX)) {
                val query = chunk.joinToString("") { "&node=${enc(it)}" }
                val responses = try {
                    request("GET_MULTIPLE", "", query, "")
                } catch (e: WrongPinException) {
                    throw e
                } catch (ignored: FsApiException) {
                    emptyList() // z. B. HTTP 400: kennt das Radio nicht
                }
                val byNode = responses.mapNotNull { r -> r.node?.let { it to r } }.toMap()
                if (byNode.isEmpty()) {
                    multipleSupported = false
                    break
                }
                chunk.forEach { n -> byNode[n]?.text()?.let { result[n] = it } }
            }
            if (multipleSupported) return result
        }
        return nodes.filterNot { it in missing }.mapNotNull { n -> get(n)?.let { n to it } }.toMap()
    }

    suspend fun set(node: String, value: String): Boolean =
        call("SET", node, extra = "&value=${enc(value)}").status == "FS_OK"

    /** Einen Listeneintrag ändern (LIST_SET), z. B. Name und Adresse eines Favoriten. */
    suspend fun listSet(node: String, fields: List<Pair<String, String>>): Boolean =
        call("LIST_SET", node, extra = fields.joinToString("") { (k, v) -> "&$k=${enc(v)}" }).status == "FS_OK"

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
                if (strict) throw IOException("List $node not readable (${r.status})")
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
    ): FsResponse = request(op, "/$node$pathSuffix", "", extra).firstOrNull()
        ?: FsResponse("FS_NODE_DOES_NOT_EXIST", null, emptyList(), true).also { if (op == "GET") missing += node }

    private suspend fun request(op: String, path: String, query: String, extra: String): List<FsResponse> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val url = URL("$base/$op$path?pin=${enc(pin)}$query$extra")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 6000
            try {
                when (val code = conn.responseCode) {
                    200 -> conn.inputStream.use { parse(it) }
                    403 -> throw WrongPinException()
                    404 -> emptyList()
                    else -> throw FsApiException(R.string.error_http, code)
                }
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun parse(input: InputStream): List<FsResponse> {
        val p = Xml.newPullParser()
        p.setInput(input, null)
        return parseResponses(p)
    }
}

/**
 * Eine Antwort (fsapiResponse) oder mehrere (GET_MULTIPLE: fsapiGetMultipleResponse mit je
 * einem fsapiResponse samt Knotenname) in der Reihenfolge des Dokuments.
 */
internal fun parseResponses(p: XmlPullParser): List<FsResponse> {
    val responses = mutableListOf<FsResponse>()

    var status = ""
    var value: String? = null
    var listEnd = false
    var items = mutableListOf<FsItem>()
    var node: String? = null

    var itemKey: Int? = null
    var fields = mutableMapOf<String, String>()
    var types = mutableMapOf<String, String>()
    var fieldName: String? = null
    var inValue = false
    var valueType: String? = null
    val path = ArrayDeque<String>()

    fun reset() {
        status = ""
        value = null
        listEnd = false
        items = mutableListOf()
        node = null
        valueType = null
    }

    while (true) {
        when (p.next()) {
            XmlPullParser.START_TAG -> {
                when (p.name) {
                    "fsapiResponse" -> reset()
                    "item" -> {
                        itemKey = p.getAttributeValue(null, "key")?.toIntOrNull() ?: -1
                        fields = mutableMapOf()
                        types = mutableMapOf()
                    }
                    "field" -> fieldName = p.getAttributeValue(null, "name")?.lowercase() // Groß-/Kleinschreibung wechselt je Gerät
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
                    parent == "node" && fName == null -> node = text.trim()
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
                    "fsapiResponse" -> responses += FsResponse(status, value, items, listEnd, valueType, node)
                }
                path.removeLastOrNull()
            }

            XmlPullParser.END_DOCUMENT -> break
        }
    }
    return responses
}
