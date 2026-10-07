package eu.kanade.tachiyomi.animeextension.all.box

import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Retries idempotent GET requests when the Invidious instance answers with a
 * transient server error (5xx) or with 429.
 *
 * Public Invidious instances share one YouTube backend, so a burst of requests
 * (details + watch page + captions + manifests + itag probes) makes them answer
 * "500 Internal Server Error" for a few seconds. Without this interceptor the
 * failure is surfaced by the app as "HTTP 500" and the source looks broken even
 * though a single retry would have succeeded.
 */
class RetryServerErrorInterceptor(
    private val maxRetries: Int = 2,
    private val backoffMillis: Long = 800L,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var attempt = 0
        var response = chain.proceed(request)

        while (request.method == "GET" && attempt < maxRetries && isRetryable(response.code)) {
            response.close()
            attempt++
            Log.w(TAG, "HTTP ${response.code} for ${request.url}, retry $attempt/$maxRetries")
            try {
                Thread.sleep(backoffMillis * attempt)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                attempt = maxRetries
            }
            response = chain.proceed(request)
        }

        return response
    }

    private fun isRetryable(code: Int): Boolean = code in RETRY_CODES

    companion object {
        private const val TAG = "BoxRetry"
        private val RETRY_CODES = setOf(408, 425, 429, 500, 502, 503, 504)
    }
}
