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

        val requestBuilder = Request.Builder()
            .url(url)
            .headers(extractHeaders(session))
        if (range != null) {
            val (start, end) = range
            requestBuilder.header("Range", if (end != null) "bytes=$start-$end" else "bytes=$start-")
        }

        val response = client.newCall(requestBuilder.build()).execute()
        val body = response.body
            ?: return newFixedLengthResponse(
                Response.Status.NO_CONTENT,
                MIME_PLAINTEXT,
                "",
            )

        val bytes = body.bytes()
        val totalSize = response.header("Content-Range")?.let { parseContentRangeTotal(it) }
            ?: (range?.first?.plus(bytes.size) ?: bytes.size).toLong()

        val status = when {
            response.code == 206 || range != null -> Response.Status.PARTIAL_CONTENT
            response.code == 404 -> Response.Status.NOT_FOUND
            else -> Response.Status.OK
        }

        Log.d(TAG, "Segment response: $url status=${response.code} len=${bytes.size} total=$totalSize")

        val resp = newFixedLengthResponse(
            status,
            contentType,
            ByteArrayInputStream(bytes),
            bytes.size.toLong(),
        )
        resp.addHeader("Accept-Ranges", "bytes")
        if (range != null) {
            val start = range.first
            resp.addHeader("Content-Range", "bytes $start-${start + bytes.size - 1}/$totalSize")
        }
        response.header("ETag")?.let { resp.addHeader("ETag", it) }
        response.header("Last-Modified")?.let { resp.addHeader("Last-Modified", it) }
        return resp
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
