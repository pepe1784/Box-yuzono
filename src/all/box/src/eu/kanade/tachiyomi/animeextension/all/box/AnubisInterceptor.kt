package eu.kanade.tachiyomi.animeextension.all.box

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import uy.kohesive.injekt.injectLazy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Some Invidious instances (e.g. behind Anubis) return an HTML bot challenge for API
 * requests made by OkHttp. This interceptor loads the API URL in a WebView so the
 * browser can solve the JS challenge, then returns the resulting JSON directly.
 */
class AnubisInterceptor(private val client: OkHttpClient) : Interceptor {

    private val context: Application by injectLazy()
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        // If the response looks like JSON, leave it alone.
        if (!needsChallenge(response)) {
            return response
        }

        val protocol = response.protocol
        response.close()

        // Load the same URL in a WebView, let Anubis solve itself, and grab the body.
        val (cookies, body) = resolveWithWebView(request.url)

        if (!body.isNullOrBlank() && looksLikeJson(body)) {
            return Response.Builder()
                .request(request)
                .protocol(protocol)
                .code(200)
                .message("OK")
                .header("Content-Type", "application/json")
                .body(body.toResponseBody("application/json".toMediaTypeOrNull()))
                .build()
        }

        // Fallback: retry with any cookies we managed to collect.
        if (cookies.isNotEmpty()) {
            client.cookieJar.saveFromResponse(request.url, cookies)
        }
        return chain.proceed(request)
    }

    private fun needsChallenge(response: Response): Boolean {
        if (response.code == 403) return true

        val contentType = response.header("Content-Type") ?: return false
        if (!contentType.contains("text/html", ignoreCase = true)) return false

        return try {
            val peek = response.peekBody(64).string().trimStart()
            peek.startsWith("<") || peek.startsWith("<!doctype", ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }

    private fun looksLikeJson(body: String): Boolean {
        val trimmed = body.trimStart()
        return trimmed.startsWith("{") || trimmed.startsWith("[")
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private fun resolveWithWebView(url: HttpUrl): Pair<List<Cookie>, String?> {
        val latch = CountDownLatch(1)
        var webView: WebView? = null
        val jsBridge = JsBridge(latch)
        val targetUrl = url.toString()

        handler.post {
            val webview = WebView(context)
            webView = webview
            with(webview.settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                useWideViewPort = false
                loadWithOverviewMode = false
                userAgentString = USER_AGENT
            }

            webview.addJavascriptInterface(jsBridge, "AndroidBridge")
            webview.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    // Give Anubis / JS challenge time to solve itself, then grab body text.
                    view?.postDelayed({
                        view.evaluateJavascript("AndroidBridge.pass(document.body.innerText);", null)
                    }, CHALLENGE_DELAY_MS)
                }
            }

            webview.loadUrl(targetUrl)
        }

        latch.await(TIMEOUT_SEC, TimeUnit.SECONDS)

        handler.post {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }

        val cookieString = CookieManager.getInstance().getCookie(targetUrl)
            ?: CookieManager.getInstance().getCookie("${url.scheme}://${url.host}")
        val cookies = cookieString?.split(";")?.mapNotNull { Cookie.parse(url, it) } ?: emptyList()

        return cookies to jsBridge.result
    }

    private class JsBridge(private val latch: CountDownLatch) {
        var result: String? = null

        @JavascriptInterface
        fun pass(result: String) {
            this.result = result
            latch.countDown()
        }
    }

    companion object {
        private const val TIMEOUT_SEC = 25L
        private const val CHALLENGE_DELAY_MS = 12_000L
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    }
}
