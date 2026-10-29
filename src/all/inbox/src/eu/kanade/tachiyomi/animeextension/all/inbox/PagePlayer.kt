package eu.kanade.tachiyomi.animeextension.all.inbox

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import uy.kohesive.injekt.injectLazy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Loads the real watch page in a hidden WebView and keeps the player response
 * that YouTube's own JavaScript built for it.
 *
 * That response is the important part: its `adaptiveFormats` links carry the
 * `pot` (proof of origin) parameter. Asking InnerTube directly from the app
 * returns the same formats without it, and on networks where YouTube enforces
 * the token googlevideo answers 403 to those links while it happily serves the
 * muxed one. Running the page once in the background gets signed links without
 * asking the user to solve or paste anything.
 */
internal class PagePlayer {

    private val context: Application by injectLazy()
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = ConcurrentHashMap<String, Pair<Long, String>>()
    private val failures = AtomicInteger(0)

    /** Returns the page's `ytInitialPlayerResponse`-shaped JSON, or null. */
    fun playerResponse(videoId: String): String? {
        // If the page route keeps coming back empty it will not fix itself: stop paying
        // the timeout on every episode and let the caller fall back to InnerTube.
        if (failures.get() >= MAX_CONSECUTIVE_FAILURES) return null
        cache[videoId]?.let { (at, payload) ->
            if (System.currentTimeMillis() - at < CACHE_MS) return payload
            cache.remove(videoId)
        }
        val latch = CountDownLatch(1)
        val result = AtomicReference<String?>(null)
        val webView = AtomicReference<WebView?>(null)

        handler.post {
            runCatching {
                CookieManager.getInstance()
                    .setCookie("https://www.youtube.com", "SOCS=$CONSENT_COOKIE")
                val view = WebView(context)
                webView.set(view)
                with(view.settings) {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    // Nothing here is seen by anyone, so skip the images.
                    loadsImagesAutomatically = false
                    blockNetworkImage = true
                    mediaPlaybackRequiresUserGesture = false
                    userAgentString = DESKTOP_UA
                }
                view.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        poll(view, 1, result, latch)
                    }
                }
                view.loadUrl("https://www.youtube.com/watch?v=$videoId&hl=en")
            }.onFailure {
                Log.w(TAG, "WebView setup failed: ${it.message}")
                latch.countDown()
            }
        }

        val finished = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        handler.post {
            webView.get()?.let {
                it.stopLoading()
                it.destroy()
            }
        }
        val payload = result.get()
        when {
            payload != null -> {
                failures.set(0)
                cache[videoId] = System.currentTimeMillis() to payload
            }
            !finished -> Log.d(TAG, "Watch page timed out for $videoId")
            else -> Log.d(TAG, "Watch page returned no player response for $videoId")
        }
        if (payload == null) {
            val count = failures.incrementAndGet()
            if (count >= MAX_CONSECUTIVE_FAILURES) Log.d(TAG, "Page route disabled after $count failures")
        }
        return payload
    }

    private fun poll(
        view: WebView,
        attempt: Int,
        result: AtomicReference<String?>,
        latch: CountDownLatch,
    ) {
        val callback = ValueCallback<String> { value ->
            val payload = runCatching {
                json.parseToJsonElement(value).jsonPrimitive.content
            }.getOrNull()
            when {
                !payload.isNullOrBlank() -> {
                    result.set(payload)
                    latch.countDown()
                }
                attempt < MAX_ATTEMPTS -> handler.postDelayed(
                    { poll(view, attempt + 1, result, latch) },
                    RETRY_DELAY_MS,
                )
                else -> latch.countDown()
            }
        }
        runCatching { view.evaluateJavascript(READ_PLAYER_RESPONSE_JS, callback) }
            .onFailure { latch.countDown() }
    }

    companion object {
        private const val TAG = "INbox"
        private const val TIMEOUT_SECONDS = 18L
        private const val RETRY_DELAY_MS = 700L
        private const val MAX_ATTEMPTS = 14
        private const val CACHE_MS = 10 * 60 * 1000L
        private const val MAX_CONSECUTIVE_FAILURES = 2
        private const val CONSENT_COOKIE = "CAESEwgDEgk0ODE3Nzk3NTQaAmVuIAEaBgiA_K-fBg"
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"

        /**
         * The page keeps its player response on `#movie_player`; `ytInitialPlayerResponse`
         * is the fallback for layout changes. Only a response that actually has stream
         * data is worth returning.
         */
        private val READ_PLAYER_RESPONSE_JS = """
            (function() {
                try {
                    var player = document.getElementById('movie_player');
                    var data = null;
                    if (player && typeof player.getPlayerResponse === 'function') {
                        data = player.getPlayerResponse();
                    }
                    if (!data) data = window.ytInitialPlayerResponse;
                    if (!data) return '';
                    var status = data.playabilityStatus && data.playabilityStatus.status;
                    if (status && status !== 'OK') return '';
                    var streams = data.streamingData;
                    if (!streams) return '';
                    var hasVideo = (streams.adaptiveFormats || []).length > 0;
                    var hasMuxed = (streams.formats || []).length > 0;
                    if (!hasVideo && !hasMuxed) return '';
                    return JSON.stringify(data);
                } catch (e) {
                    return '';
                }
            })()
        """.trimIndent()
    }
}
