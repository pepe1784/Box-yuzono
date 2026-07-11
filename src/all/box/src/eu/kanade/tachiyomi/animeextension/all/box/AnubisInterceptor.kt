package eu.kanade.tachiyomi.animeextension.all.box

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import uy.kohesive.injekt.injectLazy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Some Invidious instances (e.g. behind Anubis) return an HTML bot challenge for API
 * requests made by OkHttp. This interceptor detects an HTML response, opens a WebView
 * on the instance root so the browser can solve the JS challenge, then retries the
 * request with the obtained cookies.
 */
class AnubisInterceptor(private val client: OkHttpClient) : Interceptor {

    private val context: Application by injectLazy()
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        // If the body is JSON, leave it alone.
        if (!isHtmlResponse(response)) {
            return response
        }

        response.close()

        // Resolve the challenge once per host and store cookies in OkHttp's jar.
        resolveWithWebView(request.url)

        // Retry with cookies (CookieJar will attach them automatically).
        return chain.proceed(request)
    }

    private fun isHtmlResponse(response: Response): Boolean {
        val contentType = response.header("Content-Type") ?: return false
        if (!contentType.contains("text/html", ignoreCase = true)) return false

        return try {
            val peek = response.peekBody(64).string().trimStart()
            peek.startsWith("<") || peek.startsWith("<!doctype", ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun resolveWithWebView(url: HttpUrl) {
        val latch = CountDownLatch(1)
        var webView: WebView? = null
        val rootUrl = "${url.scheme}://${url.host}"

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

            webview.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    // Give Anubis / JS challenge time to solve itself.
                    view?.postDelayed({ latch.countDown() }, CHALLENGE_DELAY_MS)
                }
            }

            webview.loadUrl(rootUrl)
        }

        latch.await(TIMEOUT_SEC, TimeUnit.SECONDS)

        handler.post {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }

        val cookieString = CookieManager.getInstance().getCookie(rootUrl) ?: return
        val cookies = cookieString.split(";").mapNotNull { Cookie.parse(url, it) }
        if (cookies.isNotEmpty()) {
            client.cookieJar.saveFromResponse(url, cookies)
        }
    }

    companion object {
        private const val TIMEOUT_SEC = 25L
        private const val CHALLENGE_DELAY_MS = 12_000L
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    }
}
