package eu.kanade.tachiyomi.animeextension.all.inbox

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Small HTTP relay on 127.0.0.1 that sits between the player and googlevideo.
 *
 * YouTube only serves a DASH link for a while and only in bounded pieces: an
 * open ended request (`Range: bytes=0-`), which is exactly what a player sends
 * when it starts a stream, is answered with 403, and after a couple of megabytes
 * the link starts refusing everything. A progressive (muxed) link has no such
 * limit, which is why only the 360p entry used to play.
 *
 * The relay asks googlevideo for bounded chunks, keeps one long response open to
 * the player, and when a link is exhausted it asks InnerTube for a fresh one and
 * carries on from the same byte. The player only ever talks to localhost, so no
 * header or range tricks are needed on its side.
 */
internal object StreamRelay {
    private const val TAG = "INboxRelay"

    /** Bounded request size: big enough to be fast, small enough to survive the quota. */
    private const val CHUNK = 1024 * 1024L

    /** How many fresh links to try for a single chunk before giving up. */
    private const val MAX_RETRIES = 5

    /** Minimum time between two player requests, so a stubborn link does not hammer InnerTube. */
    private const val MIN_REFRESH_MS = 1000L

    /** A player that keeps the connection idle for longer than this is gone. */
    private const val CLIENT_TIMEOUT_MS = 60_000

    private const val MAX_ENTRIES = 64

    private val entries = ConcurrentHashMap<String, Entry>()
    private val sequence = AtomicInteger(0)

    @Volatile
    private var port = 0

    @Volatile
    private var server: ServerSocket? = null

    private val workers = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "inbox-relay").apply { isDaemon = true }
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** Set by InnerTube: (videoId, itag, clientName) -> a freshly signed googlevideo url. */
    @Volatile
    var resolver: ((String, String, String) -> String?)? = null

    private data class Entry(
        val videoId: String,
        val itag: String,
        val clientName: String,
        val contentType: String,
        val length: Long,
        @Volatile var url: String,
        @Volatile var lastRefresh: Long,
    )

    private val refreshLock = Any()

    /**
     * Starts the server (once) and returns its port, or 0 when it could not listen.
     */
    @Synchronized
    private fun ensureStarted(): Int {
        if (port > 0) return port
        return try {
            val socket = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
            server = socket
            port = socket.localPort
            thread(isDaemon = true, name = "inbox-relay-accept") {
                while (!socket.isClosed) {
                    val client = try {
                        socket.accept()
                    } catch (e: IOException) {
                        break
                    }
                    workers.execute { runCatching { serve(client) } }
                }
            }
            Log.d(TAG, "relay listening on 127.0.0.1:$port")
            port
        } catch (e: Exception) {
            Log.w(TAG, "relay could not start: ${e.message}")
            0
        }
    }

    /**
     * Registers a stream and returns the local url the player should receive,
     * or the original url when the server is not available.
     */
    fun register(
        videoId: String,
        itag: String,
        clientName: String,
        url: String,
        length: Long,
        contentType: String,
    ): String {
        val listenPort = ensureStarted()
        if (listenPort == 0) return url
        if (entries.size >= MAX_ENTRIES) entries.clear()
        val token = "${sequence.incrementAndGet()}-$itag-${clientName.hashCode().toUInt().toString(16)}"
        entries[token] = Entry(videoId, itag, clientName, contentType, length, url, System.currentTimeMillis())
        return "http://127.0.0.1:$listenPort/s/$token"
    }

    /** True when [url] points at this relay. */
    fun isRelay(url: String): Boolean = url.startsWith("http://127.0.0.1:")

    // ================================ server ================================

    private fun serve(client: Socket) {
        client.use { socket ->
            socket.soTimeout = CLIENT_TIMEOUT_MS
            socket.tcpNoDelay = true
            val input = BufferedInputStream(socket.getInputStream(), 8192)
            val output = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
            val head = readHead(input) ?: return
            val lines = head.split("\r\n")
            val requestLine = lines.firstOrNull().orEmpty().split(" ")
            if (requestLine.size < 2) return
            val method = requestLine[0].uppercase()
            val path = requestLine[1]
            val token = path.substringAfter("/s/", "").substringBefore('?').substringBefore('#')
            val entry = entries[token]
            if (entry == null) {
                respondError(output, 404, "not found")
                return
            }
            if (method == "OPTIONS") {
                respondError(output, 204, "")
                return
            }
            val range = lines.drop(1)
                .firstOrNull { it.startsWith("Range:", ignoreCase = true) }
                ?.substringAfter("=", "")
            val (start, requestedEnd) = parseRange(range, entry.length)
            val end = when {
                entry.length <= 0 -> -1L
                requestedEnd < 0 -> entry.length - 1
                else -> minOf(requestedEnd, entry.length - 1)
            }
            val partial = entry.length > 0 && (start > 0 || end < entry.length - 1)
            val header = buildString {
                append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                append("Content-Type: ${entry.contentType}\r\n")
                if (entry.length > 0) {
                    append("Accept-Ranges: bytes\r\n")
                    append("Content-Length: ${end - start + 1}\r\n")
                    if (partial) append("Content-Range: bytes $start-$end/${entry.length}\r\n")
                }
                append("Connection: close\r\n")
                append("\r\n")
            }
            output.write(header.toByteArray())
            output.flush()
            if (method == "HEAD") return
            relay(entry, start, end, output)
        }
    }

    private fun relay(entry: Entry, start: Long, end: Long, output: BufferedOutputStream) {
        val buffer = ByteArray(64 * 1024)
        var position = start
        var retries = 0
        while (end < 0 || position <= end) {
            val chunkEnd = if (end < 0) position + CHUNK - 1 else minOf(position + CHUNK - 1, end)
            val response = try {
                fetch(entry, position, chunkEnd)
            } catch (e: Exception) {
                null
            }
            if (response == null || response.code !in 200..299 || (response.code == 200 && position > 0)) {
                response?.close()
                if (retries++ >= MAX_RETRIES) {
                    Log.w(TAG, "giving up at byte $position (${entry.videoId}/${entry.itag})")
                    return
                }
                Log.d(TAG, "HTTP ${response?.code} at $position, asking for a fresh link")
                refresh(entry)
                continue
            }
            var written = 0L
            var upstreamCut = false
            var playerGone = false
            response.use {
                val source = it.body.source()
                while (end < 0 || position + written <= end) {
                    val read = try {
                        source.read(buffer, 0, buffer.size)
                    } catch (e: IOException) {
                        upstreamCut = true
                        0
                    }
                    if (upstreamCut) break
                    if (read == -1) break
                    val allowed = if (end < 0) read else minOf(read.toLong(), end + 1 - (position + written)).toInt()
                    if (allowed <= 0) break
                    try {
                        output.write(buffer, 0, allowed)
                        if (written % (256 * 1024) < buffer.size) output.flush()
                    } catch (e: IOException) {
                        playerGone = true
                        break
                    }
                    written += allowed
                }
            }
            if (playerGone) return
            runCatching { output.flush() }
            if (written > 0) {
                position += written
                retries = 0
                if (upstreamCut) Log.d(TAG, "upstream cut at $position, continuing with a fresh link")
                continue
            }
            if (retries++ >= MAX_RETRIES) {
                Log.w(TAG, "no data at byte $position, giving up (${entry.videoId}/${entry.itag})")
                return
            }
            refresh(entry)
        }
    }

    private fun fetch(entry: Entry, start: Long, end: Long): okhttp3.Response {
        val request = Request.Builder()
            .url(entry.url)
            .header("User-Agent", InnerTube.PLAYBACK_UA)
            .header("Accept", "*/*")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Origin", InnerTube.BASE_URL)
            .header("Referer", "${InnerTube.BASE_URL}/")
            .header("Range", "bytes=$start-$end")
            .get()
            .build()
        return http.newCall(request).execute()
    }

    private fun refresh(entry: Entry) {
        synchronized(refreshLock) {
            val wait = MIN_REFRESH_MS - (System.currentTimeMillis() - entry.lastRefresh)
            if (wait > 0) {
                runCatching { Thread.sleep(wait) }
            }
            val fresh = runCatching {
                resolver?.invoke(entry.videoId, entry.itag, entry.clientName)
            }.getOrNull()
            if (!fresh.isNullOrBlank()) {
                entry.url = fresh
                Log.d(TAG, "fresh link for itag ${entry.itag}")
            }
            entry.lastRefresh = System.currentTimeMillis()
        }
    }

    private fun readHead(input: InputStream): String? {
        val builder = StringBuilder()
        var matched = 0
        while (builder.length < 16384) {
            val byte = input.read()
            if (byte == -1) return null
            builder.append(byte.toChar())
            matched = when {
                byte.toChar() == '\r' && (matched == 0 || matched == 2) -> matched + 1
                byte.toChar() == '\n' && (matched == 1 || matched == 3) -> matched + 1
                else -> 0
            }
            if (matched == 4) return builder.toString()
        }
        return builder.toString()
    }

    private fun parseRange(header: String?, length: Long): Pair<Long, Long> {
        if (header.isNullOrBlank()) return 0L to -1L
        val spec = header.substringBefore(',').trim()
        val parts = spec.removePrefix("bytes=").split("-", limit = 2)
        val startText = parts.getOrNull(0).orEmpty().trim()
        val endText = parts.getOrNull(1).orEmpty().trim()
        if (startText.isEmpty()) {
            // "bytes=-N": the last N bytes.
            val suffix = endText.toLongOrNull() ?: return 0L to -1L
            if (length <= 0) return 0L to -1L
            return maxOf(0, length - suffix) to length - 1
        }
        val start = startText.toLongOrNull() ?: return 0L to -1L
        val end = endText.toLongOrNull() ?: -1L
        return if (end in 0 until start) start to -1L else start to end
    }

    private fun respondError(output: BufferedOutputStream, code: Int, message: String) {
        val reason = when (code) {
            404 -> "Not Found"
            204 -> "No Content"
            else -> "Error"
        }
        val body = message.toByteArray()
        val head = buildString {
            append("HTTP/1.1 $code $reason\r\n")
            append("Content-Type: text/plain\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        runCatching {
            output.write(head.toByteArray())
            output.write(body)
            output.flush()
        }
    }
}
