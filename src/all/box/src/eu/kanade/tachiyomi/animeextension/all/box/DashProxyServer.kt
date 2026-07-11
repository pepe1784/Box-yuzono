package eu.kanade.tachiyomi.animeextension.all.box

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayInputStream
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
class DashProxyServer(private val client: OkHttpClient) {

    private var server: NanoHTTPD? = null

    val url: String
        get() = "http://127.0.0.1:${server?.listeningPort ?: 0}/manifest.mpd"

    fun start() {
        stop()
        server = object : NanoHTTPD(0) {
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
        }.apply {
            start(SOCKET_TIMEOUT, false)
            Log.d(TAG, "DASH proxy started on port $listeningPort")
        }
    }

    fun stop() {
        server?.stop()
        server = null
    }

    private fun serveManifest(session: IHTTPSession): Response {
        val manifestUrl = session.parameters["url"]?.firstOrNull()
            ?: return badRequest("Missing url")
        val manifest = fetchManifest(manifestUrl)
        val rewritten = rewriteManifest(manifest, manifestUrl)
        val bytes = rewritten.toByteArray(Charset.defaultCharset())
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/dash+xml",
            ByteArrayInputStream(bytes),
            bytes.size.toLong(),
        )
    }

    private fun serveSegment(session: IHTTPSession): Response {
        val segmentUrl = session.parameters["url"]?.firstOrNull()
            ?: return badRequest("Missing url")
        val request = Request.Builder()
            .url(segmentUrl)
            .headers(extractHeaders(session))
            .build()
        val response = client.newCall(request).execute()
        val body = response.body
        val bytes = body?.bytes() ?: ByteArray(0)
        return newFixedLengthResponse(
            Response.Status.lookup(response.code) ?: Response.Status.OK,
            body?.contentType()?.toString() ?: "video/mp4",
            ByteArrayInputStream(bytes),
            bytes.size.toLong(),
        )
    }

    private fun fetchManifest(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/dash+xml")
            .build()
        return client.newCall(request).execute().use { it.body.string() }
    }

    private fun rewriteManifest(manifest: String, manifestUrl: String): String {
        val port = server?.listeningPort ?: return manifest
        val base = manifestUrl.substringBeforeLast("/")
        return manifest.replace(BASE_URL_REGEX) { match ->
            val raw = match.groupValues[1].replace("&amp;", "&")
            val absolute = if (raw.startsWith("http")) raw else "$base$raw"
            val proxyUrl = "http://127.0.0.1:$port/segment?url=${URLEncoder.encode(absolute, "UTF-8")}"
            "<BaseURL>${proxyUrl.replace("&", "&amp;")}</BaseURL>"
        }
    }

    private fun extractHeaders(session: IHTTPSession): Headers {
        val builder = Headers.Builder()
        session.headers.forEach { (key, value) ->
            when (key.lowercase()) {
                "user-agent", "referer", "origin", "accept", "accept-language",
                "accept-encoding", "range", "connection", "cache-control", "pragma",
                -> builder.add(key, value)
            }
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
