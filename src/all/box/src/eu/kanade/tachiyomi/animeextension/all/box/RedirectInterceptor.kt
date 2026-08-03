package eu.kanade.tachiyomi.animeextension.all.box

import android.util.Log
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/**
 * Application interceptor that follows redirects manually.
 *
 * OkHttp's default redirect follower strips custom headers (e.g. the pass
 * headers used by GoAwayInterceptor/AnubisInterceptor) when it follows a 3xx
 * response. Those headers are needed to avoid re-entering the challenge flow on
 * the next hop, so losing them can create an infinite redirect loop that ends
 * with "ProtocolException: too many follow-up requests".
 *
 * Implemented as an application interceptor so it can call chain.proceed()
 * multiple times (network interceptors are not allowed to do that). Each
 * redirect hop re-uses the same request object, keeping its custom headers.
 */
class RedirectInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        return follow(chain, chain.request(), 0)
    }

    private fun follow(chain: Interceptor.Chain, request: Request, depth: Int): Response {
        if (depth > MAX_REDIRECTS) {
            throw java.net.ProtocolException(
                "Box: too many manual redirects ($depth) for ${request.url}",
            )
        }

        val response = chain.proceed(request)
        val code = response.code
        if (code !in 300..399) return response

        val location = response.header("Location") ?: return response
        val newUrl = request.url.resolve(location) ?: return response

        Log.d(TAG, "redirect #$depth ${request.url} -> $newUrl (code=$code)")
        response.close()

        val newRequest = request.newBuilder()
            .url(newUrl)
            .build()
        return follow(chain, newRequest, depth + 1)
    }

    companion object {
        private const val TAG = "BoxRedirect"
        private const val MAX_REDIRECTS = 20
    }
}
