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
            Log.e(TAG, "Proxy error: ${e.message}", e)
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                e.message ?: "Error",
            )
        }
    }

    private fun serveManifest(session: IHTTPSession): Response {
        val url = session.parameters["url"]?.firstOrNull()
            ?: return badRequest("Missing url")
        val manifest = fetchManifest(url)
        val rewritten = rewriteManifest(manifest)
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
        val stream = body.byteStream().withCloseAction(response::close)
        val contentLength = body.contentLength()
        return if (contentLength >= 0) {
            newFixedLengthResponse(status, contentType, stream, contentLength)
        } else {
            newChunkedResponse(status, contentType, stream)
        }
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
        session.headers.forEach { (key, value) ->
            if (key.lowercase() in skip) return@forEach
            builder.add(key, value)
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
