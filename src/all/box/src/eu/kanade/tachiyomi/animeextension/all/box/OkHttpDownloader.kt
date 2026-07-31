package eu.kanade.tachiyomi.animeextension.all.box

import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException

/**
 * NewPipeExtractor [Downloader] implementation backed by OkHttp.
 */
class OkHttpDownloader(private val client: OkHttpClient) : Downloader() {

    @Throws(ReCaptchaException::class)
    override fun execute(request: Request): Response {
        val url = request.url()

        val headersBuilder = Headers.Builder()
        request.headers().forEach { (name, values) ->
            values.forEach { value ->
                headersBuilder.add(name, value)
            }
        }
        if (headersBuilder["User-Agent"] == null) {
            headersBuilder.add("User-Agent", USER_AGENT)
        }

        val body = request.dataToSend()?.let { data ->
            val contentType = request.headers()["Content-Type"]?.firstOrNull()
                ?.toMediaTypeOrNull()
            data.toRequestBody(contentType)
        }

        val okRequest = okhttp3.Request.Builder()
            .url(url)
            .headers(headersBuilder.build())
            .method(request.httpMethod(), body)
            .build()

        val response = client.newCall(okRequest).execute()
        val responseBody = response.body?.string() ?: ""

        // NewPipe expects ReCaptchaException when a recaptcha/challenge page is returned.
        if (response.code == 429 || responseBody.contains("recaptcha", ignoreCase = true)) {
            response.close()
            throw ReCaptchaException("reCaptcha or rate limit", url)
        }

        return Response(
            response.code,
            response.message,
            response.headers.toMultimap(),
            responseBody,
            response.request.url.toString(),
        )
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    }
}
