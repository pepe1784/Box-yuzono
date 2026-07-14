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
 * Segment requests are answered with the exact byte range MPV asks for, never
 * loading more than [MAX_IN_MEMORY] bytes into RAM at once. This avoids OOM
 * errors on long videos while still reporting the real total file size so MPV
 * keeps requesting more data.
 */
class DashProxyServer(
    private val client: OkHttpClient,
) : NanoHTTPD(0) {

    private var manifestUrl: String = ""
    private val totalSizes = mutableMapOf<String, Long>()

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
        // Some players handle on-demand manifests better than the main profile.
        val finalManifest = rewritten.replace(
            "urn:mpeg:dash:profile:isoff-main:2011",
            "urn:mpeg:dash:profile:isoff-on-demand:2011",
        )
        val bytes = finalManifest.toByteArray(Charset.defaultCharset())
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
        val contentType = guessContentType(url)

        // Make sure we know the real total size before answering; otherwise MPV
        // may think the file ends after the first chunk.
        val totalSize = totalSizes[url] ?: fetchTotalSize(url).also { totalSizes[url] = it }
        if (totalSize <= 0) {
            Log.w(TAG, "Could not determine total size for $url")
        }

        return if (range != null) {
            serveSegmentRange(session, url, range, contentType, totalSize)
        } else {
            serveSegmentFull(session, url, contentType, totalSize)
        }
    }

    private fun serveSegmentRange(
        session: IHTTPSession,
        url: String,
        range: Pair<Long, Long?>,
        contentType: String,
        totalSize: Long,
    ): Response {
        val (reqStart, reqEnd) = range
        val requestedLength = if (reqEnd != null) reqEnd - reqStart + 1 else totalSize - reqStart

        // For small ranges load the bytes in memory; for large ranges stream.
        return if (requestedLength in 1..MAX_IN_MEMORY) {
            serveSmallRange(session, url, reqStart, reqEnd, contentType, totalSize)
        } else {
            serveStreamRange(session, url, reqStart, reqEnd, contentType, totalSize)
        }
    }

    private fun serveSmallRange(
        session: IHTTPSession,
        url: String,
        start: Long,
        end: Long?,
        contentType: String,
        totalSize: Long,
    ): Response {
        val rangeHeader = if (end != null) "bytes=$start-$end" else "bytes=$start-"
        val request = Request.Builder()
            .url(url)
            .headers(extractHeaders(session))
            .header("Range", rangeHeader)
            .build()
        val response = client.newCall(request).execute()
        val bytes = response.body?.bytes() ?: ByteArray(0)
        response.close()

        Log.d(TAG, "Small range: $url range=$rangeHeader got ${bytes.size} total=$totalSize")

        val resp = newFixedLengthResponse(
            Response.Status.PARTIAL_CONTENT,
            contentType,
            ByteArrayInputStream(bytes),
            bytes.size.toLong(),
        )
        resp.addHeader("Accept-Ranges", "bytes")
        resp.addHeader("Content-Range", "bytes $start-${start + bytes.size - 1}/${totalSize.coerceAtLeast(start + bytes.size)}")
        return resp
    }

    private fun serveStreamRange(
        session: IHTTPSession,
        url: String,
        start: Long,
        end: Long?,
        contentType: String,
        totalSize: Long,
    ): Response {
        val rangeHeader = if (end != null) "bytes=$start-$end" else "bytes=$start-"
        val request = Request.Builder()
            .url(url)
            .headers(extractHeaders(session))
            .header("Range", rangeHeader)
            .build()
        val response = client.newCall(request).execute()
        val body = response.body
            ?: return newFixedLengthResponse(
                Response.Status.NO_CONTENT,
                MIME_PLAINTEXT,
                "",
            )

        val contentLength = body.contentLength()
        val stream = body.byteStream().withCloseAction(response::close)
        val resp = if (contentLength >= 0) {
            newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, contentType, stream, contentLength)
        } else {
            newChunkedResponse(Response.Status.PARTIAL_CONTENT, contentType, stream)
        }
        resp.addHeader("Accept-Ranges", "bytes")
        if (totalSize > 0) {
            val actualEnd = if (end != null) end else totalSize - 1
            resp.addHeader("Content-Range", "bytes $start-$actualEnd/$totalSize")
        }
        return resp
    }

    private fun serveSegmentFull(
        session: IHTTPSession,
        url: String,
        contentType: String,
        totalSize: Long,
    ): Response {
        Log.d(TAG, "Full segment request: $url")
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

        val contentLength = body.contentLength()
        val stream = body.byteStream().withCloseAction(response::close)
        val resp = if (contentLength >= 0) {
            newFixedLengthResponse(Response.Status.OK, contentType, stream, contentLength)
        } else {
            newChunkedResponse(Response.Status.OK, contentType, stream)
        }
        resp.addHeader("Accept-Ranges", "bytes")
        return resp
    }

    private fun fetchTotalSize(url: String): Long {
        return try {
            // Some instances hang on "bytes=0-0"; ask for a small chunk instead.
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Range", "bytes=0-1023")
                .build()
            client.newCall(request).execute().use { response ->
                response.header("Content-Range")?.let { parseContentRangeTotal(it) }
                    ?: response.header("Content-Length")?.toLongOrNull()
                    ?: 0L
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch total size for $url", e)
            0L
        }
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

    private fun parseContentRangeTotal(header: String): Long? {
        val m = Regex("""bytes \\d+-\\d+/(\\d+)""").find(header) ?: return null
        return m.groupValues[1].toLongOrNull()
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
     * Keep only the best video Representation up to 1080p, preferring H.264
     * (avc1) because it has the widest decoder support in Aniyomi/MPV.
     * VP9 (vp9) is used as fallback, then AV1. Audio AdaptationSets are kept
     * untouched. Empty AdaptationSets are removed.
     */
    private fun filterManifest(manifest: String): String {
        val adaptationSets = ADAPTATION_SET_REGEX.findAll(manifest).toList()
        if (adaptationSets.isEmpty()) return manifest

        var audioBlock: String? = null
        var bestVideo: Pair<String, String>? = null
        var bestScore: Triple<Int, Int, Int> = Triple(-1, -1, -1)

        adaptationSets.forEach { asMatch ->
            val asAttrs = parseAttributes(asMatch.groupValues[1])
            val contentType = asAttrs["contentType"] ?: ""
            val block = asMatch.groupValues[0]
            val opening = asMatch.groupValues[1]

            if (contentType == "audio") {
                audioBlock = block
            } else if (contentType == "video") {
                REPRESENTATION_REGEX.findAll(block).forEach { repMatch ->
                    val repAttrs = parseAttributes(repMatch.groupValues[1])
                    val height = repAttrs["height"]?.toIntOrNull() ?: 0
                    val codecs = repAttrs["codecs"] ?: ""
                    val pref = when {
                        codecs.startsWith("avc1") -> 2
                        codecs.startsWith("vp9") -> 1
                        else -> 0
                    }
                    val capped = if (height <= 1080) height else 0
                    val score = Triple(pref, capped, height)
                    if (score > bestScore) {
                        bestScore = score
                        bestVideo = opening to repMatch.groupValues[0]
                    }
                }
            }
        }

        val mpdHead = MPD_OPEN_REGEX.find(manifest)?.groupValues?.get(0)
            ?: manifest.substringBefore("<Period>")
        val sb = StringBuilder()
        sb.append(mpdHead)
        sb.append("<Period>")
        audioBlock?.let { sb.append(it) }
        bestVideo?.let { (opening, rep) ->
            sb.append("<AdaptationSet$opening>$rep</AdaptationSet>")
        }
        sb.append("</Period></MPD>")
        val height = bestScore.third
        Log.d(TAG, "Filtered DASH manifest: kept ${height}p (pref=${bestScore.first})")
        return sb.toString()
    }

    private fun parseAttributes(tag: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val regex = Regex("""(\w+)="([^"]*)"""")
        regex.findAll(tag).forEach { match ->
            map[match.groupValues[1]] = match.groupValues[2]
        }
        return map
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
        private const val MAX_IN_MEMORY = 1L * 1024 * 1024
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
        private val BASE_URL_REGEX = Regex("""<BaseURL>([^<]+)</BaseURL>""")
        private val ADAPTATION_SET_REGEX = Regex(
            """<AdaptationSet([^>]*)>(.*?)</AdaptationSet>""",
            RegexOption.DOT_MATCHES_ALL,
        )
        private val REPRESENTATION_REGEX = Regex(
            """<Representation([^>]*)>(.*?)</Representation>""",
            RegexOption.DOT_MATCHES_ALL,
        )
        private val MPD_OPEN_REGEX = Regex("""<MPD[^>]*>""")
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
            action()
        }
    }
}
