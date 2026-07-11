package eu.kanade.tachiyomi.animeextension.all.box

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URLEncoder
import java.nio.charset.Charset

/**
 * Tiny local HTTP proxy for Invidious DASH manifests.
 *
 * Aniyomi/MPV often refuses to play DASH when the URL has no `.mpd` extension.
 * This server exposes the manifest at `http://127.0.0.1:<port>/manifest.mpd`,
 * rewrites every `<BaseURL>` to point back at this proxy, and forwards segment
 * requests using the OkHttp client (so the Anubis auth cookie is preserved).
 *
 * To keep the buffer from stalling on long videos, segment data is fetched in
 * 1 MiB blocks and cached in memory.
 */
class DashProxyServer(
    private val client: OkHttpClient,
) : NanoHTTPD(0) {

    private var manifestUrl: String = ""

    // 8 blocks of 1 MiB each.
    private val blockCache = object : LinkedHashMap<String, ByteArray>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>): Boolean {
            return size > MAX_CACHE_BLOCKS
        }
    }

    // Total segment size per URL, parsed from upstream Content-Range.
    private val segmentSizes = mutableMapOf<String, Long>()

    val proxyUrl: String
        get() = "http://127.0.0.1:$listeningPort/manifest.mpd"

    fun serveManifest(manifestUrl: String): String {
        if (!isAlive) {
            start(SOCKET_TIMEOUT, false)
            Log.d(TAG, "DASH proxy started on port $listeningPort")
        }
        this.manifestUrl = manifestUrl
        return "$proxyUrl?url=${URLEncoder.encode(manifestUrl, "UTF-8")}"
    }

    override fun serve(session: IHTTPSession): Response {
        return try {
            when (session.uri) {
                "/manifest.mpd" -> serveManifest(session)
                "/segment" -> serveSegment(session)
                else -> newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    MIME_PLAINTEXT,
                    "Not found",
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Proxy error serving ${session.uri}: ${e.message}", e)
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                "Proxy error: ${e.message}",
            )
        }
    }

    private fun serveManifest(session: IHTTPSession): Response {
        val url = session.parameters["url"]?.firstOrNull()
            ?: return badRequest("Missing url")
        val manifest = fetchManifest(url)
        val filtered = filterManifest(manifest)
        val rewritten = rewriteManifest(filtered)
        val bytes = rewritten.toByteArray(Charset.defaultCharset())
        val resp = newFixedLengthResponse(
            Response.Status.OK,
            "application/dash+xml",
            ByteArrayInputStream(bytes),
            bytes.size.toLong(),
        )
        resp.addHeader("Cache-Control", "no-store, no-cache, must-revalidate")
        resp.addHeader("Pragma", "no-cache")
        resp.addHeader("Expires", "0")
        return resp
    }

    private fun serveSegment(session: IHTTPSession): Response {
        val url = session.parameters["url"]?.firstOrNull()
            ?: return badRequest("Missing url")
        val range = parseRange(session.headers["range"])

        return if (range != null) {
            serveSegmentRange(session, url, range)
        } else {
            serveSegmentFull(session, url)
        }
    }

    private fun serveSegmentRange(
        session: IHTTPSession,
        url: String,
        range: Pair<Long, Long?>,
    ): Response {
        val (reqStart, reqEnd) = range
        val contentType = guessContentType(url)

        // If the requested range spans more than one block, fetch exactly what
        // was asked to avoid complicated multi-block reads.
        val blockStart = (reqStart / BLOCK_SIZE) * BLOCK_SIZE
        val blockEnd = if (reqEnd != null) (reqEnd / BLOCK_SIZE) * BLOCK_SIZE else blockStart
        if (blockEnd != blockStart) {
            return fetchExactRange(session, url, reqStart, reqEnd, contentType)
        }

        val cacheKey = "$url|$blockStart"
        val block = blockCache.get(cacheKey) ?: fetchBlock(url, blockStart, session).also {
            blockCache[cacheKey] = it
        }

        val startInBlock = (reqStart - blockStart).toInt().coerceIn(0, block.size)
        val endInBlock = if (reqEnd != null) {
            (reqEnd - blockStart + 1).toInt().coerceIn(startInBlock, block.size)
        } else {
            block.size
        }
        val data = block.copyOfRange(startInBlock, endInBlock)
        val totalSize = segmentSizes[url] ?: block.size.toLong()

        val resp = newFixedLengthResponse(
            Response.Status.PARTIAL_CONTENT,
            contentType,
            ByteArrayInputStream(data),
            data.size.toLong(),
        )
        resp.addHeader("Accept-Ranges", "bytes")
        resp.addHeader(
            "Content-Range",
            "bytes $reqStart-${reqStart + data.size - 1}/$totalSize",
        )
        return resp
    }

    private fun fetchExactRange(
        session: IHTTPSession,
        url: String,
        start: Long,
        end: Long?,
        contentType: String,
    ): Response {
        val range = if (end != null) "bytes=$start-$end" else "bytes=$start-"
        val request = Request.Builder()
            .url(url)
            .headers(extractHeaders(session))
            .header("Range", range)
            .build()
        Log.d(TAG, "Fetching exact range: $url range=$range")
        return client.newCall(request).execute().use { response ->
            val body = response.body ?: return@use newFixedLengthResponse(
                Response.Status.NO_CONTENT,
                MIME_PLAINTEXT,
                "",
            )
            val bytes = body.bytes()
            val total = response.header("Content-Range")?.let { parseContentRangeTotal(it) } ?: (start + bytes.size)
            segmentSizes[url] = total
            val resp = newFixedLengthResponse(
                Response.Status.PARTIAL_CONTENT,
                contentType,
                ByteArrayInputStream(bytes),
                bytes.size.toLong(),
            )
            resp.addHeader("Accept-Ranges", "bytes")
            resp.addHeader("Content-Range", "bytes $start-${start + bytes.size - 1}/$total")
            resp
        }
    }

    private fun serveSegmentFull(session: IHTTPSession, url: String): Response {
        Log.d(TAG, "Segment request (full): $url")
        val request = Request.Builder()
            .url(url)
            .headers(extractHeaders(session))
            .build()
        val response = client.newCall(request).execute()
        val body = response.body
            ?: return newFixedLengthResponse(
                Response.Status.NO_CONTENT,
                MIME_PLAINTEXT,
                "",
            )
        val status = when (response.code) {
            200 -> Response.Status.OK
            206 -> Response.Status.PARTIAL_CONTENT
            404 -> Response.Status.NOT_FOUND
            else -> Response.Status.OK
        }
        val contentType = body.contentType()?.toString() ?: "video/mp4"
        val isCompressed = !response.header("Content-Encoding").isNullOrBlank()
        val contentLength = if (isCompressed) -1 else body.contentLength()
        Log.d(TAG, "Segment response: status=${response.code}, type=$contentType, len=${body.contentLength()}, compressed=$isCompressed")
        val stream = body.byteStream().withCloseAction(response::close)
        val resp = if (contentLength >= 0) {
            newFixedLengthResponse(status, contentType, stream, contentLength)
        } else {
            newChunkedResponse(status, contentType, stream)
        }
        resp.addHeader("Accept-Ranges", response.header("Accept-Ranges") ?: "bytes")
        response.header("Content-Range")?.let { resp.addHeader("Content-Range", it) }
        response.header("ETag")?.let { resp.addHeader("ETag", it) }
        response.header("Last-Modified")?.let { resp.addHeader("Last-Modified", it) }
        return resp
    }

    private fun fetchBlock(url: String, blockStart: Long, session: IHTTPSession): ByteArray {
        val range = "bytes=$blockStart-${blockStart + BLOCK_SIZE - 1}"
        val request = Request.Builder()
            .url(url)
            .headers(extractHeaders(session))
            .header("Range", range)
            .build()
        Log.d(TAG, "Fetching block: $url range=$range")
        return client.newCall(request).execute().use { response ->
            response.header("Content-Range")?.let { parseContentRangeTotal(it) }?.let {
                segmentSizes[url] = it
            }
            response.body?.bytes() ?: ByteArray(0)
        }
    }

    private fun parseContentRangeTotal(header: String): Long? {
        val m = Regex("""bytes \\d+-\\d+/(\\d+)""").find(header) ?: return null
        return m.groupValues[1].toLongOrNull()
    }

    private fun guessContentType(url: String): String {
        return when {
            url.contains("itag=140") || url.contains("audio") -> "audio/mp4"
            url.contains("itag=") -> "video/mp4"
            else -> "video/mp4"
        }
    }

    private fun parseRange(header: String?): Pair<Long, Long?>? {
        if (header.isNullOrBlank()) return null
        val m = RANGE_REGEX.find(header) ?: return null
        val start = m.groupValues[1].toLongOrNull() ?: return null
        val end = m.groupValues[2].toLongOrNull()
        return start to end
    }

    private fun fetchManifest(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/dash+xml")
            .build()
        return client.newCall(request).execute().use { it.body.string() }
    }

    private fun rewriteManifest(manifest: String): String {
        val port = listeningPort
        val base = manifestUrl.substringBeforeLast("/")
        val host = manifestHost
        return manifest.replace(BASE_URL_REGEX) { match ->
            val raw = match.groupValues[1].replace("&amp;", "&")
            val absolute = when {
                raw.startsWith("http://") || raw.startsWith("https://") -> raw
                raw.startsWith("/") -> "$host$raw"
                else -> "$base/$raw"
            }
            val proxy = "http://127.0.0.1:$port/segment?url=${URLEncoder.encode(absolute, "UTF-8")}"
            "<BaseURL>${proxy.replace("&", "&amp;")}</BaseURL>"
        }
    }

    /**
     * Keep only the best video Representation up to 1080p. This dramatically
     * reduces the work MPV has to do on startup, especially for long videos
     * where each Representation is a single large file. Audio Representations
     * (no height) are always kept.
     */
    private fun filterManifest(manifest: String): String {
        val matches = REPRESENTATION_REGEX.findAll(manifest).toList()
        if (matches.isEmpty()) return manifest

        val heights = matches.mapNotNull { it.groupValues[2].toIntOrNull() }
        val targetHeight = when {
            heights.contains(1080) -> 1080
            heights.contains(720) -> 720
            else -> heights.filter { it <= 1080 }.maxOrNull() ?: heights.maxOrNull() ?: return manifest
        }

        var result = manifest
        matches.forEach { match ->
            val height = match.groupValues[2].toIntOrNull()
            if (height != null && height != targetHeight) {
                result = result.replace(match.value, "")
            }
        }
        Log.d(TAG, "Filtered DASH manifest: kept ${targetHeight}p")
        return result
    }

    private val manifestHost: String
        get() = try {
            java.net.URL(manifestUrl).let { "${it.protocol}://${it.host}" }
        } catch (_: Exception) {
            manifestUrl.substringBeforeLast("/")
        }

    private fun extractHeaders(session: IHTTPSession): Headers {
        val builder = Headers.Builder()
        val skip = setOf(
            "host", "connection", "keep-alive", "proxy-connection",
            "content-length", "transfer-encoding", "accept-encoding",
            "upgrade-insecure-requests",
        )
        var hasUserAgent = false
        session.headers.forEach { (key, value) ->
            val lower = key.lowercase()
            if (lower in skip) return@forEach
            if (lower == "user-agent") hasUserAgent = true
            try {
                builder.add(key, value)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Skipping invalid header: $key")
            }
        }
        if (!hasUserAgent) {
            builder.add("User-Agent", USER_AGENT)
        }
        return builder.build()
    }

    private fun badRequest(message: String): Response {
        return newFixedLengthResponse(
            Response.Status.BAD_REQUEST,
            MIME_PLAINTEXT,
            message,
        )
    }

    companion object {
        private const val TAG = "DashProxyServer"
        private const val SOCKET_TIMEOUT = 120_000
        private const val BLOCK_SIZE = 1L * 1024 * 1024
        private const val MAX_CACHE_BLOCKS = 8
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
        private val BASE_URL_REGEX = Regex("""<BaseURL>([^<]+)</BaseURL>""")
        private val REPRESENTATION_REGEX = Regex(
            """<Representation([^>]*height=\"(\d+)\"[^>]*)>.*?</Representation>""",
            RegexOption.DOT_MATCHES_ALL,
        )
        private val RANGE_REGEX = Regex("""bytes=(\d+)-(\d*)""")
    }
}

/**
 * Returns an [InputStream] that invokes [action] after the stream is closed.
 * Used to close the OkHttp [Response] once NanoHTTPD finishes serving it.
 */
private fun InputStream.withCloseAction(action: () -> Unit): InputStream {
    val base = this
    return object : InputStream() {
        override fun read(): Int = base.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = base.read(b, off, len)
        override fun available(): Int = base.available()
        override fun close() {
            base.close()
            Log.d("DashProxyServer", "Segment stream closed")
            action()
        }
    }
}
