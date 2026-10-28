package de.radiopult.app.fsapi

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL

data class RadioDevice(val host: String, val name: String)

/** Findet Radios im WLAN per SSDP (UPnP-Suche). */
object Discovery {

    private val searchTargets = listOf(
        "urn:schemas-frontier-silicon-com:undok:fsapi:1",
        "urn:schemas-frontier-silicon-com:fs_reference:fsapi:1",
    )

    suspend fun discover(context: Context, timeoutMs: Long = 3500): List<RadioDevice> {
        val hosts = search(context, searchTargets, timeoutMs).map { it.host }.distinct()
        return coroutineScope {
            hosts.map { h -> async(Dispatchers.IO) { RadioDevice(h, friendlyName(h) ?: h) } }.awaitAll()
        }
    }

    /** Eine Antwort auf die SSDP-Suche: Adresse des Geräts und seine Beschreibung (LOCATION). */
    data class Answer(val host: String, val location: String?, val target: String?)

    /** SSDP-Suche nach [targets]; liefert jede Antwort einmal. */
    suspend fun search(context: Context, targets: List<String>, timeoutMs: Long = 3500): List<Answer> =
        withContext(Dispatchers.IO) {
            val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
            val lock = wifi?.createMulticastLock("radiopult")?.apply {
                setReferenceCounted(false)
                acquire()
            }
            val answers = linkedSetOf<Answer>()
            try {
                DatagramSocket().use { socket ->
                    socket.soTimeout = 500
                    val group = InetAddress.getByName("239.255.255.250")
                    repeat(2) {
                        for (st in targets) {
                            val msg = ("M-SEARCH * HTTP/1.1\r\n" +
                                "HOST: 239.255.255.250:1900\r\n" +
                                "MAN: \"ssdp:discover\"\r\n" +
                                "MX: 2\r\n" +
                                "ST: $st\r\n\r\n").toByteArray()
                            socket.send(DatagramPacket(msg, msg.size, group, 1900))
                        }
                    }
                    val buf = ByteArray(4096)
                    val end = System.currentTimeMillis() + timeoutMs
                    while (System.currentTimeMillis() < end) {
                        val packet = DatagramPacket(buf, buf.size)
                        try {
                            socket.receive(packet)
                        } catch (ignored: SocketTimeoutException) {
                            continue
                        }
                        val text = String(packet.data, 0, packet.length)
                        fun header(name: String) = text.lineSequence()
                            .firstOrNull { it.startsWith("$name:", ignoreCase = true) }
                            ?.substringAfter(':')?.trim()
                        val location = header("LOCATION")
                        val host = location?.let { runCatching { URL(it).host }.getOrNull() }
                            ?: packet.address?.hostAddress
                        if (!host.isNullOrBlank()) answers += Answer(host, location, header("ST"))
                    }
                }
            } finally {
                lock?.release()
            }
            answers.toList()
        }

    /** Der Name steht ohne PIN unter /device. */
    private fun friendlyName(host: String): String? = runCatching {
        val conn = URL("http://$host/device").openConnection() as HttpURLConnection
        conn.connectTimeout = 2000
        conn.readTimeout = 3000
        try {
            val xml = conn.inputStream.bufferedReader().use { it.readText() }
            Regex("<friendlyName>(.*?)</friendlyName>", RegexOption.DOT_MATCHES_ALL)
                .find(xml)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()
}
