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
 */
class DashProxyServer(
    private val client: OkHttpClient,
) : NanoHTTPD(0) {

    private var manifestUrl: String = ""

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
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/dash+xml",
            ByteArrayInputStream(bytes),
            bytes.size.toLong(),
        )
    }

    private fun serveSegment(session: IHTTPSession): Response {
        val url = session.parameters["url"]?.firstOrNull()
            ?: return badRequest("Missing url")
        Log.d(TAG, "Segment request: $url")
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
        // OkHttp transparently decompresses gzip unless we add Accept-Encoding
        // manually. If the server compressed the response, Content-Length refers
        // to the compressed size while the stream is already decompressed, so we
        // must serve it chunked without Content-Length.
        val isCompressed = !response.header("Content-Encoding").isNullOrBlank()
        val contentLength = if (isCompressed) -1 else body.contentLength()
        Log.d(TAG, "Segment response: status=${response.code}, type=$contentType, len=${body.contentLength()}, compressed=$isCompressed")
        val stream = body.byteStream().withCloseAction(response::close)
        val resp = if (contentLength >= 0) {
            newFixedLengthResponse(status, contentType, stream, contentLength)
        } else {
            newChunkedResponse(status, contentType, stream)
        }
        // Forward range-related headers so MPV can seek inside the segment.
        // Always advertise Accept-Ranges; this lets MPV request the sidx at
        // the end of large files without downloading the whole file first.
        resp.addHeader("Accept-Ranges", response.header("Accept-Ranges") ?: "bytes")
        response.header("Content-Range")?.let { resp.addHeader("Content-Range", it) }
        response.header("ETag")?.let { resp.addHeader("ETag", it) }
        response.header("Last-Modified")?.let { resp.addHeader("Last-Modified", it) }
        return resp
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
     * Keep only 720p and 1080p video Representations. If none are available,
     * fall back to the highest quality up to 1080p, or the absolute highest
     * if nothing else exists. Audio Representations (no height) are kept.
     */
    private fun filterManifest(manifest: String): String {
        val matches = REPRESENTATION_REGEX.findAll(manifest).toList()
        if (matches.isEmpty()) return manifest

        val heights = matches.mapNotNull { it.groupValues[2].toIntOrNull() }
        val preferred = heights.filter { it in 720..1080 }
        val keepHeights = if (preferred.isNotEmpty()) {
            preferred.toSet()
        } else {
            val upTo1080 = heights.filter { it <= 1080 }
            if (upTo1080.isNotEmpty()) {
                setOf(upTo1080.maxOrNull()!!)
            } else {
                setOf(heights.maxOrNull() ?: return manifest)
            }
        }

        var result = manifest
        matches.forEach { match ->
            val height = match.groupValues[2].toIntOrNull()
            if (height != null && height !in keepHeights) {
                result = result.replace(match.value, "")
            }
        }
        Log.d(TAG, "Filtered DASH manifest: kept heights $keepHeights")
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
        var hasAcceptEncoding = false
        session.headers.forEach { (key, value) ->
            val lower = key.lowercase()
            if (lower in skip) return@forEach
            if (lower == "user-agent") hasUserAgent = true
            if (lower == "accept-encoding") hasAcceptEncoding = true
            try {
                builder.add(key, value)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Skipping invalid header: $key")
            }
        }
        if (!hasUserAgent) {
            builder.add("User-Agent", USER_AGENT)
        }
        // Ask the server not to compress the segment, otherwise Content-Length
        // would mismatch the decompressed stream we forward to the player.
        if (!hasAcceptEncoding) {
            builder.add("Accept-Encoding", "identity")
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
        private const val SOCKET_TIMEOUT = 30_000
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
        private val BASE_URL_REGEX = Regex("""<BaseURL>([^<]+)</BaseURL>""")
        private val REPRESENTATION_REGEX = Regex(
            """<Representation([^>]*height=\"(\\d+)\"[^>]*)>.*?</Representation>""",
            RegexOption.DOT_MATCHES_ALL,
        )
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
