package eu.kanade.tachiyomi.animeextension.all.box

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import keiyoushi.utils.applicationContext
import okhttp3.Cookie
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Interceptor that handles captcha / anti-bot pages served by captchaproxy
 * or similar services. It launches a WebView so the user can complete the
 * challenge, then copies the resulting cookies back into the OkHttp request.
 */
class CaptchaProxyInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        if (!response.isCaptchaChallenge()) {
            return response
        }

        response.close()
        Log.d(TAG, "captcha challenge detected for ${request.url}")

        val newRequest = resolveWithWebView(request)
            ?: throw Exception(
                "CaptchaProxy: no se pudo completar el desafío captcha. " +
                    "Abre la fuente en WebView, resuélvelo y vuelve a intentarlo.",
            )

        return chain.proceed(newRequest)
    }

    private fun Response.isCaptchaChallenge(): Boolean {
        if (code !in CAPTCHA_CODES) return false
        val contentType = header("Content-Type") ?: return false
        if (!contentType.contains("text/html", ignoreCase = true)) return false
        val body = try {
            peekBody(CAPTCHA_PEEK_BYTES).string()
        } catch (_: Exception) {
            return false
        }
        return CAPTCHA_MARKERS.any { body.contains(it, ignoreCase = true) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun resolveWithWebView(originalRequest: Request): Request? {
        val latch = CountDownLatch(1)
        val handler = Handler(Looper.getMainLooper())
        var webView: WebView? = null

        val headers = originalRequest.headers.toMultimap()
            .mapValues { it.value.firstOrNull() ?: "" }
            .toMutableMap()

        handler.post {
            val wv = WebView(applicationContext)
            webView = wv

            wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                useWideViewPort = true
                userAgentString = originalRequest.header("User-Agent") ?: USER_AGENT
            }

            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(wv, true)
            }

            wv.addJavascriptInterface(JsInterface(latch), JS_INTERFACE)
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    view?.evaluateJavascript(CHECK_SCRIPT) {}
                }
            }

            wv.loadUrl(originalRequest.url.toString(), headers)
        }

        val solved = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)

        handler.post {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }

        if (!solved) {
            Log.d(TAG, "captcha solve timed out")
            return null
        }

        val cookieHeader = CookieManager.getInstance().getCookie(originalRequest.url.toString()) ?: ""
        if (cookieHeader.isBlank()) {
            return originalRequest
        }

        val cookies = cookieHeader.split(";")
            .map { it.trim() }
            .mapNotNull { Cookie.parse(originalRequest.url, it) }

        val cookieValue = cookies.joinToString("; ") { "${it.name}=${it.value}" }
        Log.d(TAG, "captcha solved, cookies=${cookies.map { it.name }}")

        return originalRequest.newBuilder()
            .header("Cookie", cookieValue)
            .build()
    }

    private class JsInterface(private val latch: CountDownLatch) {
        @JavascriptInterface
        fun done() {
            latch.countDown()
        }
    }

    companion object {
        private const val TAG = "BoxCaptcha"
        private const val JS_INTERFACE = "BoxCaptchaJSI"
        private const val TIMEOUT_SECONDS = 120L
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
        private const val CAPTCHA_PEEK_BYTES = 64 * 1024L
        private val CAPTCHA_CODES = 403..503
        private val CAPTCHA_MARKERS = listOf(
            "captchaproxy",
            "cap-widget",
            "cf-turnstile",
            "g-recaptcha",
            "challenge-form",
            "challenge-stage",
        )

        private val CHECK_SCRIPT = """
            (function() {
                var tries = 0;
                var maxTries = ${TIMEOUT_SECONDS * 2};
                var timer = setInterval(function() {
                    tries++;
                    try {
                        var body = document.body ? document.body.innerHTML : '';
                        var stillChallenge = body.indexOf('captchaproxy') !== -1 ||
                            document.querySelector('.captchaproxy-widget') !== null ||
                            document.querySelector('#captchaproxy-return-to') !== null ||
                            document.querySelector('.cf-turnstile') !== null ||
                            document.querySelector('#challenge-form') !== null ||
                            document.querySelector('#challenge-stage') !== null ||
                            document.querySelector('.g-recaptcha') !== null;
                        if (!stillChallenge || tries >= maxTries) {
                            clearInterval(timer);
                            if (!stillChallenge) {
                                try { ${JS_INTERFACE}.done(); } catch (e) {}
                            }
                        }
                    } catch (e) {}
                }, 500);
            })();
        """.trimIndent()
    }
}
