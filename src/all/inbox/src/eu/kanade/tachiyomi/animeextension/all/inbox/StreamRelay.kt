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

/** A signed link for one stream, plus the SABR session that goes with it (when it is known). */
internal class RelaySource(val url: String, val sabr: SabrSpec? = null)

/**
 * Small HTTP relay on 127.0.0.1 that sits between the player and googlevideo.
 *
 * An adaptive (DASH) link does not serve the whole file: after roughly a minute of
 * media googlevideo starts answering 403, in any client, with or without a poToken.
 * That is what froze the picture at minute one. The way around it is SABR
 * (`serverAbrStreamingUrl`), the protocol the YouTube apps use: the relay posts a
 * protobuf asking for a position in time and the server pushes segments, each one
 * stating the byte range it occupies in the real mp4. Rebuilding that file from the
 * segments gives the player a normal 1080p stream it can seek through, while the
 * `moov` and the `sidx` (the only bytes a plain range still serves) are fetched
 * directly from the link.
 *
 * A progressive (muxed) link has no such limit, so it stays as the fallback: when
 * SABR refuses, the relay keeps answering with bounded range requests, exactly as
 * before.
 */
internal object StreamRelay {
    private const val TAG = "INboxRelay"

    /** Bounded request size: big enough to be fast, small enough to survive the quota. */
    private const val CHUNK = 1024 * 1024L

    /** How many fresh links to try for a single chunk before slowing down. */
    private const val GRACE_RETRIES = 3

    /**
     * How long the relay keeps a player waiting for a link that will not serve.
     *
     * Measured limit: a plain adaptive link only ever serves its first ~58 seconds, no
     * matter how often it is refreshed. Waiting minutes for it (the previous 5
     * minutes) is what left the player "loading" forever, so the relay closes the
     * stream instead and the player can report the failure and be reopened.
     */
    private const val GIVE_UP_MS = 90 * 1000L

    /** The header (`moov` + `sidx`) is tiny and comes from the fresh link: do not wait long for it. */
    private const val HEADER_BUDGET_MS = 20 * 1000L

    /**
     * SABR requests that deliver nothing, in a row, before that stream is declared
     * unusable and the relay goes back to plain ranges. A request that delivers media
     * resets the count, so a long video is not cut short.
     */
    private const val MAX_SABR_FAILURES = 8

    /** Minimum time between two player requests, so a stubborn link does not hammer InnerTube. */
    private const val MIN_REFRESH_MS = 1000L

    /** A player that keeps the connection idle for longer than this is gone. */
    private const val CLIENT_TIMEOUT_MS = 60_000

    private const val MAX_ENTRIES = 64

    /** `copyRange` returns this instead of a position when the player closed the stream. */
    private const val GONE = Long.MIN_VALUE

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

    /**
     * Set by InnerTube: (videoId, itag, xtags, clientName) -> a freshly signed url and
     * the SABR session that matches it.
     *
     * `xtags` matters: one itag is served in several languages (original, dubbed, drc), so
     * asking for "itag 140" alone can return a different language than the one playing.
     */
    @Volatile
    var resolver: ((String, String, String, String) -> RelaySource?)? = null

    private class Entry(
        val videoId: String,
        val itag: String,
        val xTags: String,
        val clientName: String,
        val contentType: String,
        val length: Long,
        @Volatile var url: String,
        @Volatile var lastRefresh: Long,
        @Volatile var sabr: SabrSpec?,
        /** The `sidx` of the stream, read the first time a byte offset has to become a time. */
        @Volatile var index: SabrIndex? = null,
        /** True once SABR proved useless for this stream; the relay stays on ranges. */
        @Volatile var sabrOff: Boolean = false,
        /** Last stream protection status the server reported: 1 ok, 2 pending, 3 rejected. */
        @Volatile var protection: Int = 0,
        /** Last SABR failure in plain words, to see it in the diagnostic screen. */
        @Volatile var sabrError: String? = null,
    )

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
        xTags: String,
        clientName: String,
        url: String,
        length: Long,
        contentType: String,
        sabr: SabrSpec? = null,
    ): String {
        val listenPort = ensureStarted()
        if (listenPort == 0) return url
        if (entries.size >= MAX_ENTRIES) entries.clear()
        val token = "${sequence.incrementAndGet()}-$itag-${clientName.hashCode().toUInt().toString(16)}"
        entries[token] = Entry(
            videoId = videoId,
            itag = itag,
            xTags = xTags,
            clientName = clientName,
            contentType = contentType,
            length = length,
            url = url,
            lastRefresh = System.currentTimeMillis(),
            // Without an init range the moov cannot be served, so SABR would hand the
            // player fragments with no header in front of them.
            sabr = sabr?.takeIf { it.headEnd > 0 && it.length > 0 },
        )
        return "http://127.0.0.1:$listenPort/s/$token"
    }

    /** True when [url] points at this relay. */
    fun isRelay(url: String): Boolean = url.startsWith("http://127.0.0.1:")

    /** How one relay url is being served, for the diagnostic screen. */
    fun describe(url: String): String {
        val token = url.substringAfter("/s/", "").substringBefore('?').substringBefore('#')
        val entry = entries[token] ?: return "sin entrada"
        val spec = entry.sabr ?: return "solo rangos (${entry.clientName})"
        val extra = if (entry.protection != 0) " protección=${entry.protection}" else ""
        val failure = entry.sabrError?.let { " error=" + it.take(70) }.orEmpty()
        return "SABR itag=${spec.itag} cliente=${spec.clientId} cabecera=0..${spec.headEnd} " +
            "tamaño=${spec.length} audio=${spec.isAudio} índice=${spec.indexStart}..${spec.indexEnd}" +
            extra + failure
    }

    /** Size of the file being relayed, for the diagnostic screen. */
    fun lengthOf(url: String): Long {
        val token = url.substringAfter("/s/", "").substringBefore('?').substringBefore('#')
        return entries[token]?.length ?: 0L
    }

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
        var position = start
        val spec = entry.sabr
        if (spec != null && !entry.sabrOff) {
            val reached = relaySabr(entry, spec, position, end, output)
            if (reached == GONE) return
            position = reached
            if (end >= 0 && position > end) return
        }
        relayRanges(entry, position, end, output)
    }

    // ================================ SABR ================================

    /**
     * Serves [start] onwards with SABR and returns the first byte it could not deliver
     * (or [GONE] when the player closed the connection).
     */
    private fun relaySabr(
        entry: Entry,
        spec: SabrSpec,
        start: Long,
        end: Long,
        output: BufferedOutputStream,
    ): Long {
        var position = start
        // The moov and the sidx are the only bytes a plain link still serves, and they
        // are what the player needs before anything else, so they come from the link.
        if (position <= spec.headEnd) {
            val stop = if (end < 0) spec.headEnd else minOf(end, spec.headEnd)
            position = copyRange(entry, position, stop, output, HEADER_BUDGET_MS)
            if (position == GONE) return GONE
            if (position <= stop) return position
            if (end >= 0 && position > end) return position
        }
        // Minting takes a second or two, so it runs next to the first SABR request
        // instead of in front of it: the session starts with whatever token exists.
        runCatching { PotProvider.request(entry.videoId) }
        // The session starts attested when the proof bound to this video is ready: it
        // is what keeps the picture coming after the first minute of an app session.
        runCatching { PotProvider.bindWait(entry.videoId, 4000) }
        var active = entry.sabr ?: spec
        var stream = SabrStream(active, http) { token(entry) }
        var time = timeFor(entry, active, position)
        var failures = 0
        var empty = 0
        while (end < 0 || position <= end) {
            val writer = SabrWriter(active, output, position, end, time)
            stream.request(time, writer)
            if (stream.protection != 0) {
                entry.protection = stream.protection
            }
            entry.sabrError = stream.lastError
            if (writer.gone) return GONE
            if (writer.written > 0L) {
                position = writer.position
                // The position is what matters, so the time is taken from it: asking
                // the server for a second it already sent is what made the loop spin.
                time = maxOf(writer.lastTime, timeFor(entry, active, position))
                empty = 0
                failures = 0
                continue
            }
            val status = stream.protection
            Log.d(TAG, "SABR empty at ${time}ms byte=$position status=$status (${stream.lastError})")
            empty++
            failures++
            if (failures > MAX_SABR_FAILURES) {
                entry.sabrOff = true
                Log.w(TAG, "SABR gave up at byte $position (${entry.videoId}/${entry.itag})")
                return position
            }
            if (status == 3) {
                // The server refused the session as unattested: mint a token and start
                // a new one from the same position, with the link refreshed too.
                runCatching { PotProvider.request(entry.videoId) }
                runCatching { PotProvider.bindSoon(entry.videoId) }
                refresh(entry)
                runCatching { Thread.sleep(1500) }
            } else if (empty >= 3) {
                refresh(entry)
                runCatching { Thread.sleep(800) }
                empty = 0
                // Nothing at this second: step over it rather than asking forever.
                time += 2_000
            } else {
                runCatching { Thread.sleep(400) }
            }
            active = entry.sabr ?: active
            stream = SabrStream(active, http) { token(entry) }
        }
        return position
    }

    /**
     * The token the relay sends, when one has been minted for this stream.
     *
     * The web sessions take the proof bound to the page's visitor, the app ones the
     * proof bound to the video id; sending the visitor one to an app session is what
     * YouTube answered with "rejected" at the minute, and its media servers are the
     * only ones this network actually serves.
     */
    private fun token(entry: Entry): String? {
        val clientId = entry.sabr?.clientId ?: 3
        return if (clientId == 1 || clientId == 2) {
            runCatching { PotProvider.peek(entry.videoId) }.getOrNull()
        } else {
            runCatching { PotProvider.bind(entry.videoId) }.getOrNull()
        }
    }

    /**
     * Position in time for a byte of the file, using the `sidx` of the stream.
     *
     * The estimate is never later than the real fragment: asking early only makes the
     * server send a segment the relay skips, asking late would leave a hole.
     */
    private fun timeFor(entry: Entry, spec: SabrSpec, byte: Long): Long {
        if (byte <= spec.headEnd) return 0
        val index = entry.index ?: loadIndex(entry, spec)?.also { entry.index = it }
        index?.timeFor(byte)?.let { return it }
        if (spec.length <= 0 || spec.durationMs <= 0) return 0
        return byte * spec.durationMs * 4 / (spec.length * 5)
    }

    private fun loadIndex(entry: Entry, spec: SabrSpec): SabrIndex? {
        if (spec.indexEnd <= spec.indexStart) return null
        return try {
            fetch(entry, spec.indexStart, spec.indexEnd).use { response ->
                if (response.code !in 200..299) return null
                SabrIndex.parse(response.body.bytes(), spec.indexStart)
            }
        } catch (e: Exception) {
            Log.w(TAG, "no sidx for itag ${entry.itag}: ${e.message}")
            null
        }
    }

    /**
     * Writes the part of the SABR segments that falls inside the range the player asked
     * for. Each segment states its byte range, so a range request is answered exactly.
     */
    private class SabrWriter(
        private val spec: SabrSpec,
        private val output: BufferedOutputStream,
        start: Long,
        private val end: Long,
        startTime: Long,
    ) : SabrSink {
        private val headers = HashMap<Int, SabrHeader>()

        var position: Long = start
            private set

        var written: Long = 0
            private set

        var gone = false
            private set

        var lastTime: Long = startTime
            private set

        override fun header(header: SabrHeader) {
            headers[header.headerId] = header
        }

        override fun data(headerId: Int, bytes: ByteArray, offset: Int, length: Int) {
            val header = headers[headerId] ?: return
            val from = header.byteStart + header.seen
            header.seen += length
            if (header.itag != spec.itag) return
            lastTime = header.startMs + header.durationMs
            val sliceStart = maxOf(from, position)
            val sliceEnd = minOf(from + length - 1, if (end < 0) Long.MAX_VALUE else end)
            if (sliceEnd < sliceStart) return
            val skip = (sliceStart - from).toInt()
            val count = (sliceEnd - sliceStart + 1).toInt()
            try {
                output.write(bytes, offset + skip, count)
                written += count
                position = sliceEnd + 1
            } catch (e: IOException) {
                gone = true
            }
        }

        override fun end(headerId: Int) {
            val header = headers[headerId] ?: return
            if (header.itag == spec.itag && header.startMs + header.durationMs > lastTime) {
                lastTime = header.startMs + header.durationMs
            }
        }
    }

    // ============================== plain ranges ==============================

    private fun relayRanges(entry: Entry, start: Long, end: Long, output: BufferedOutputStream) {
        copyRange(entry, start, end, output, GIVE_UP_MS)
    }

    /**
     * Copies [from]..[to] from googlevideo in bounded chunks, asking for a fresh link
     * whenever one runs out of quota. Returns the position reached, or [GONE].
     */
    private fun copyRange(
        entry: Entry,
        from: Long,
        to: Long,
        output: BufferedOutputStream,
        budgetMs: Long,
    ): Long {
        val buffer = ByteArray(64 * 1024)
        var position = from
        var retries = 0
        // A link that stops serving is normal. Giving up on it is not: YouTube throttles
        // for a while and then answers again, so the relay waits it out instead of
        // closing the stream (which is the player freezing for good).
        var stalledSince = System.currentTimeMillis()
        while (to < 0 || position <= to) {
            val chunkEnd = if (to < 0) position + CHUNK - 1 else minOf(position + CHUNK - 1, to)
            val response = try {
                fetch(entry, position, chunkEnd)
            } catch (e: Exception) {
                null
            }
            if (response == null || response.code !in 200..299 || (response.code == 200 && position > 0)) {
                response?.close()
                retries++
                if (retries > GRACE_RETRIES) {
                    val wait = minOf(1500L shl minOf(retries - GRACE_RETRIES, 3), 12_000L)
                    runCatching { Thread.sleep(wait) }
                }
                if (System.currentTimeMillis() - stalledSince > budgetMs) {
                    Log.w(TAG, "giving up at byte $position after ${budgetMs / 1000}s (${entry.videoId}/${entry.itag})")
                    return position
                }
                Log.d(TAG, "HTTP ${response?.code} at $position, asking for a fresh link")
                if (needsToken(entry)) runCatching { PotProvider.request(entry.videoId) }
                refresh(entry)
                continue
            }
            var written = 0L
            var upstreamCut = false
            var playerGone = false
            response.use {
                val source = it.body.source()
                while (to < 0 || position + written <= to) {
                    val read = try {
                        source.read(buffer, 0, buffer.size)
                    } catch (e: IOException) {
                        upstreamCut = true
                        0
                    }
                    if (upstreamCut) break
                    if (read == -1) break
                    val allowed = if (to < 0) read else minOf(read.toLong(), to + 1 - (position + written)).toInt()
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
            if (playerGone) return GONE
            runCatching { output.flush() }
            if (written > 0) {
                position += written
                retries = 0
                stalledSince = System.currentTimeMillis()
                if (upstreamCut) Log.d(TAG, "upstream cut at $position, continuing with a fresh link")
                continue
            }
            // The response was fine but empty: same treatment, wait and try again.
            retries++
            if (retries > GRACE_RETRIES) {
                val wait = minOf(1500L shl minOf(retries - GRACE_RETRIES, 3), 12_000L)
                runCatching { Thread.sleep(wait) }
            }
            if (System.currentTimeMillis() - stalledSince > budgetMs) {
                Log.w(TAG, "no data at byte $position, giving up (${entry.videoId}/${entry.itag})")
                return position
            }
            runCatching { if (needsToken(entry)) PotProvider.request(entry.videoId) }
            refresh(entry)
        }
        return position
    }

    private fun fetch(entry: Entry, start: Long, end: Long): okhttp3.Response {
        // Never wait for the token here: setting up a WebView to mint it takes a
        // second or two, and that delay would be paid before the picture appears.
        // `refresh` asks for it in the background instead.
        val pot = if (needsToken(entry)) token(entry) else null
        val url = if (pot.isNullOrBlank()) {
            entry.url
        } else {
            entry.url + "&pot=" + java.net.URLEncoder.encode(pot, "UTF-8")
        }
        val request = Request.Builder()
            .url(url)
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

    /**
     * `VISIONOS` links serve the whole file by themselves (no `n`, no poToken), so the
     * relay does not mint a token for them: it would only cost a WebView round trip.
     */
    private fun needsToken(entry: Entry): Boolean = entry.clientName != "VISIONOS"

    private fun refresh(entry: Entry) {
        // One lock per stream: video and audio must not wait for each other, that
        // stall is heard as the sound stopping while the picture keeps going.
        synchronized(entry) {
            val wait = MIN_REFRESH_MS - (System.currentTimeMillis() - entry.lastRefresh)
            if (wait > 0) {
                runCatching { Thread.sleep(wait) }
            }
            val fresh = runCatching {
                resolver?.invoke(entry.videoId, entry.itag, entry.xTags, entry.clientName)
            }.getOrNull()
            if (fresh != null && fresh.url.isNotBlank()) {
                entry.url = fresh.url
                adopt(entry, fresh.sabr)
                Log.d(TAG, "fresh link for itag ${entry.itag} (${entry.xTags})")
            } else {
                Log.w(TAG, "no fresh link for itag ${entry.itag} (${entry.xTags})")
            }
            entry.lastRefresh = System.currentTimeMillis()
        }
    }

    /**
     * Takes the SABR session of a fresh link, but only when it describes the same file.
     *
     * A refresh can arrive from another client (that is the whole point: the web one
     * is the one that accepts the token), and two clients do not always hand out the
     * same bytes for the same itag. Adopting a session whose ranges belong to another
     * file would splice two videos together, so the layout has to match.
     */
    private fun adopt(entry: Entry, fresh: SabrSpec?) {
        if (fresh == null) return
        val current = entry.sabr
        val sameFile = current == null || (
            current.length == fresh.length &&
                (current.lastModified == fresh.lastModified || fresh.lastModified.isBlank())
            )
        if (!sameFile) {
            Log.w(TAG, "ignoring SABR from client ${fresh.clientId}: different layout for itag ${entry.itag}")
            return
        }
        if (current?.clientId != fresh.clientId) {
            // New session, new sidx: the cached one belongs to the old client.
            entry.index = null
            // A client that never worked must not blacklist the one that just arrived.
            entry.sabrOff = false
        }
        entry.sabr = fresh
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
