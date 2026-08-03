package eu.kanade.tachiyomi.animeextension.all.box

import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Network interceptor that follows redirects manually while preserving custom
 * pass headers (X-Box-GoAway-Pass, X-Box-Anubis-Pass) across each hop.
 *
 * OkHttp's default redirect follower strips application-level headers when it
 * follows a 3xx response, so a challenge interceptor's "pass" header is lost.
 * The next request then hits the challenge again, creating an infinite loop of
 * redirects and causing "ProtocolException: too many follow-up requests".
 *
 * By following redirects in a network interceptor we keep the same request
 * object (and its headers) for every hop, preventing the loop.
 */
class RedirectInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        var response = chain.proceed(request)
        var redirects = 0

        while (redirects < MAX_REDIRECTS) {
            val code = response.code
            if (code !in 300..399) break

            val location = response.header("Location")
                ?: break
            val newUrl = response.request.url.resolve(location)
                ?: break

            Log.d(
                TAG,
                "redirect #$redirects ${response.code} ${request.url} -> $newUrl",
            )
            response.close()

            request = request.newBuilder()
                .url(newUrl)
                .build()
            response = chain.proceed(request)
            redirects++
        }

        return response
    }

    companion object {
        private const val TAG = "BoxRedirect"
        private const val MAX_REDIRECTS = 20
    }
}
