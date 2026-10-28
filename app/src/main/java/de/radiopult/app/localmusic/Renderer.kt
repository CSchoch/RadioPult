package de.radiopult.app.localmusic

import android.content.Context
import de.radiopult.app.fsapi.Discovery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Der UPnP-Abspieler (MediaRenderer) im Radio. Über seinen AVTransport-Dienst bekommt er die
 * Adresse eines Stücks auf dem Handy und spielt es ab – so wie UNDOK „Lokale Musik“ abspielt.
 */
class Renderer private constructor(private val control: URL) {

    suspend fun play(url: String, track: Track) {
        soap(
            "SetAVTransportURI",
            "<InstanceID>0</InstanceID><CurrentURI>${xml(url)}</CurrentURI><CurrentURIMetaData>${xml(didl(url, track))}</CurrentURIMetaData>",
        )
        soap("Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
    }

    suspend fun stop() {
        soap("Stop", "<InstanceID>0</InstanceID>")
    }

    /** Zustand des Abspielers: PLAYING, STOPPED, PAUSED_PLAYBACK, TRANSITIONING, NO_MEDIA_PRESENT … */
    suspend fun state(): String? {
        val answer = soap("GetTransportInfo", "<InstanceID>0</InstanceID>")
        return Regex("<CurrentTransportState>(.*?)</CurrentTransportState>").find(answer)?.groupValues?.get(1)
    }

    private suspend fun soap(action: String, args: String): String = withContext(Dispatchers.IO) {
        val body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><u:$action xmlns:u=\"$SERVICE\">$args</u:$action></s:Body></s:Envelope>"
        val conn = control.openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 3000
            conn.readTimeout = 8000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            conn.setRequestProperty("SOAPACTION", "\"$SERVICE#$action\"")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode !in 200..299) throw IOException("$action: HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val SERVICE = "urn:schemas-upnp-org:service:AVTransport:1"

        /** Den Abspieler des Radios unter [host] im Netz suchen; null, wenn es keinen anbietet. */
        suspend fun find(context: Context, host: String): Renderer? {
            val answers = Discovery.search(context, listOf("urn:schemas-upnp-org:device:MediaRenderer:1"), 3000)
            val location = answers.firstOrNull { it.host == host && it.location != null }?.location ?: return null
            return withContext(Dispatchers.IO) {
                val conn = URL(location).openConnection() as HttpURLConnection
                val description = try {
                    conn.connectTimeout = 3000
                    conn.readTimeout = 5000
                    conn.inputStream.bufferedReader().use { it.readText() }
                } finally {
                    conn.disconnect()
                }
                controlUrl(location, description)?.let { Renderer(it) }
            }
        }

        /** controlURL des AVTransport-Dienstes aus der Gerätebeschreibung (relativ zu URLBase oder zur Beschreibung). */
        internal fun controlUrl(location: String, description: String): URL? {
            val service = Regex("<service>(.*?)</service>", RegexOption.DOT_MATCHES_ALL).findAll(description)
                .map { it.groupValues[1] }
                .firstOrNull { it.contains("AVTransport") } ?: return null
            val path = Regex("<controlURL>(.*?)</controlURL>").find(service)?.groupValues?.get(1)?.trim() ?: return null
            val base = Regex("<URLBase>(.*?)</URLBase>").find(description)?.groupValues?.get(1)?.trim()?.ifEmpty { null } ?: location
            return runCatching { URL(URL(base), path) }.getOrNull()
        }

        private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

        /** Beschreibung des Stücks für das Display des Radios (DIDL-Lite wie bei UNDOK). */
        private fun didl(url: String, t: Track): String =
            "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
                "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
                "<item id=\"${t.id}\" parentID=\"0\" restricted=\"1\">" +
                "<dc:title>${xml(t.title)}</dc:title>" +
                (if (t.artist.isNotEmpty()) "<upnp:artist>${xml(t.artist)}</upnp:artist><dc:creator>${xml(t.artist)}</dc:creator>" else "") +
                (if (t.album.isNotEmpty()) "<upnp:album>${xml(t.album)}</upnp:album>" else "") +
                "<upnp:class>object.item.audioItem.musicTrack</upnp:class>" +
                "<res protocolInfo=\"http-get:*:${t.mime}:*\" size=\"${t.size}\" duration=\"${duration(t.durationMs)}\">${xml(url)}</res>" +
                "</item></DIDL-Lite>"

        private fun duration(ms: Long): String {
            val s = ms / 1000
            return "%d:%02d:%02d.000".format(s / 3600, (s % 3600) / 60, s % 60)
        }
    }
}
