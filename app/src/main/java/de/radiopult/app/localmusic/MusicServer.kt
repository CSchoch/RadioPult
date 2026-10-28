package de.radiopult.app.localmusic

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import kotlin.concurrent.thread

/**
 * Kleiner HTTP-Server im Heimnetz, über den das Radio die Musik vom Handy abholt
 * (UNDOK macht das genauso). Er liefert nur Stücke, die gerade in der Warteschlange sind,
 * unter /track/<id>; Bereichsanfragen (Range) für das Spulen werden unterstützt.
 */
class MusicServer(private val context: Context) {

    private var socket: ServerSocket? = null

    /** Stücke, die das Radio abrufen darf. */
    @Volatile
    var allowed: Map<Long, Track> = emptyMap()

    val port: Int get() = socket?.localPort ?: 0

    fun start(address: InetAddress) {
        if (socket != null) return
        val s = ServerSocket(0, 8, address)
        socket = s
        thread(name = "radiopult-music", isDaemon = true) {
            while (!s.isClosed) {
                val client = try {
                    s.accept()
                } catch (ignored: SocketException) {
                    break
                }
                thread(isDaemon = true) { serve(client) }
            }
        }
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
    }

    private fun serve(client: Socket) = client.use { sock ->
        runCatching {
            sock.soTimeout = 30_000
            val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.ISO_8859_1))
            val requestLine = reader.readLine() ?: return@runCatching
            val headers = generateSequence { reader.readLine()?.takeIf { it.isNotEmpty() } }
                .mapNotNull { line -> line.indexOf(':').takeIf { it > 0 }?.let { line.substring(0, it).trim().lowercase() to line.substring(it + 1).trim() } }
                .toMap()
            val parts = requestLine.split(' ')
            val method = parts.getOrNull(0).orEmpty()
            val path = parts.getOrNull(1).orEmpty()
            val out = sock.getOutputStream()
            val id = path.removePrefix("/track/").substringBefore('.').toLongOrNull()
            val track = id?.let { allowed[it] }
            if (track == null || (method != "GET" && method != "HEAD")) {
                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                return@runCatching
            }
            send(out, track, headers["range"], head = method == "HEAD")
        }
    }

    private fun send(out: OutputStream, track: Track, range: String?, head: Boolean) {
        val size = track.size.takeIf { it > 0 }
        var start = 0L
        var end = (size ?: 0L) - 1
        val partial = range != null && size != null && range.startsWith("bytes=")
        if (partial) {
            val spec = range!!.removePrefix("bytes=").substringBefore(',')
            start = spec.substringBefore('-').toLongOrNull() ?: 0L
            end = spec.substringAfter('-').toLongOrNull()?.coerceAtMost(size!! - 1) ?: (size!! - 1)
        }
        val length = if (size != null) end - start + 1 else null
        val header = buildString {
            append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
            append("Content-Type: ${track.mime}\r\n")
            append("Accept-Ranges: bytes\r\n")
            if (length != null) append("Content-Length: $length\r\n")
            if (partial) append("Content-Range: bytes $start-$end/$size\r\n")
            append("transferMode.dlna.org: Streaming\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(header.toByteArray(Charsets.ISO_8859_1))
        if (head) return
        context.contentResolver.openInputStream(track.uri)?.use { input ->
            var skip = start
            while (skip > 0) {
                val n = input.skip(skip)
                if (n <= 0) break
                skip -= n
            }
            val buf = ByteArray(64 * 1024)
            var left = length ?: Long.MAX_VALUE
            while (left > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) break
                out.write(buf, 0, n)
                left -= n
            }
        }
        out.flush()
    }
}
