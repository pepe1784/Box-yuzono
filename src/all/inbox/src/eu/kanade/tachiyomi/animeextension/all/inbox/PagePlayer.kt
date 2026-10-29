package eu.kanade.tachiyomi.animeextension.all.inbox

import android.app.Application
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
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

/** One googlevideo URL the page itself asked for. */
internal data class PageStream(val url: String, val label: String, val height: Int)

/** Everything the hidden page gave us for a video. */
internal data class PageStreams(
    val response: String?,
    val videos: List<PageStream>,
    val audios: List<PageStream>,
) {
    val hasPlayableDash: Boolean get() = videos.isNotEmpty() && audios.isNotEmpty()
}

/**
 * Loads the real watch page in a hidden WebView and keeps what YouTube's own
 * JavaScript produces for it.
 *
 * Two things come out of the page:
 *  - the player response, whose `adaptiveFormats` links are signed for that session;
 *  - the googlevideo requests the player itself makes once it starts, which are the
 *    links that already have everything applied (`pot` token and the descrambled `n`
 *    throttling parameter). Those are the ones that play, so they are preferred.
 *
 * Nothing is shown on screen: the WebView is created off-screen, kept for a few
 * seconds and destroyed.
 */
internal class PagePlayer {

    private val context: Application by injectLazy()
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = ConcurrentHashMap<String, Pair<Long, PageStreams>>()
    private val failures = AtomicInteger(0)

    fun streams(videoId: String): PageStreams? {
        // If the page route keeps coming back empty it will not fix itself: stop paying
        // the timeout on every episode and let the caller fall back to InnerTube.
        if (failures.get() >= MAX_CONSECUTIVE_FAILURES) return null
        cache[videoId]?.let { (at, value) ->
            if (System.currentTimeMillis() - at < CACHE_MS) return value
            cache.remove(videoId)
        }

        val latch = CountDownLatch(1)
        val captured = ConcurrentHashMap<Int, PageStream>()
        val response = AtomicReference<String?>(null)
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
                        poll(view, 1, captured, response, latch)
                    }

                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                    ): WebResourceResponse? {
                        runCatching { record(request.url, captured) }
                        return null
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

        val streams = PageStreams(
            response = response.get(),
            videos = captured.values.filter { it.height > 0 }.sortedByDescending { it.height },
            audios = captured.values.filter { it.height == 0 },
        )
        val usable = streams.hasPlayableDash || streams.response != null
        when {
            !finished -> Log.d(TAG, "Watch page timed out for $videoId")
            !usable -> Log.d(TAG, "Watch page gave nothing for $videoId")
        }
        if (usable) {
            failures.set(0)
            cache[videoId] = System.currentTimeMillis() to streams
        } else {
            val count = failures.incrementAndGet()
            if (count >= MAX_CONSECUTIVE_FAILURES) Log.d(TAG, "Page route disabled after $count failures")
        }
        return if (usable) streams else null
    }

    /** Keeps the request the player actually made, minus the chunk range it asked for. */
    private fun record(url: Uri, into: MutableMap<Int, PageStream>) {
        if (url.host?.endsWith("googlevideo.com") != true) return
        if (url.path?.contains("videoplayback") != true) return
        val itag = url.getQueryParameter("itag")?.toIntOrNull() ?: return
        val mime = url.getQueryParameter("mime").orEmpty()
        val height = VIDEO_ITAGS[itag]
        val label = when {
            height != null && mime.startsWith("video/") -> "${height}p"
            height == null && mime.startsWith("audio/") -> AUDIO_ITAGS[itag] ?: "Audio"
            else -> return
        }
        val clean = RANGE_PARAMS.replace(url.toString(), "")
        into.putIfAbsent(itag, PageStream(clean, label, height ?: 0))
    }

    private fun poll(
        view: WebView,
        attempt: Int,
        captured: ConcurrentHashMap<Int, PageStream>,
        response: AtomicReference<String?>,
        latch: CountDownLatch,
    ) {
        val hasVideo = captured.values.any { it.height > 0 }
        val hasAudio = captured.values.any { it.height == 0 }
        val pageDone = response.get() != null
        if (hasVideo && hasAudio) {
            latch.countDown()
            return
        }
        if ((pageDone && attempt > PAGE_GRACE_ATTEMPTS) || attempt > MAX_ATTEMPTS) {
            latch.countDown()
            return
        }

        val callback = ValueCallback<String> { value ->
            val payload = runCatching {
                json.parseToJsonElement(value).jsonPrimitive.content
            }.getOrNull()
            if (!payload.isNullOrBlank()) response.compareAndSet(null, payload)
            handler.postDelayed(
                { poll(view, attempt + 1, captured, response, latch) },
                RETRY_DELAY_MS,
            )
        }
        runCatching { view.evaluateJavascript(READ_PLAYER_RESPONSE_JS, callback) }
            .onFailure { latch.countDown() }
    }

    companion object {
        private const val TAG = "INbox"
        private const val TIMEOUT_SECONDS = 16L
        private const val RETRY_DELAY_MS = 700L
        private const val MAX_ATTEMPTS = 20
        private const val PAGE_GRACE_ATTEMPTS = 8
        private const val CACHE_MS = 10 * 60 * 1000L
        private const val MAX_CONSECUTIVE_FAILURES = 2
        private const val CONSENT_COOKIE = "CAESEwgDEgk0ODE3Nzk3NTQaAmVuIAEaBgiA_K-fBg"
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"

        private val RANGE_PARAMS = Regex("[&?](range|rn|rbuf)=[^&]*")

        private val VIDEO_ITAGS = mapOf(
            160 to 144,
            133 to 240,
            134 to 360,
            135 to 480,
            136 to 720,
            137 to 1080,
            298 to 720,
            299 to 1080,
            303 to 1080,
            264 to 1440,
            266 to 2160,
        )

        private val AUDIO_ITAGS = mapOf(
            139 to "Audio 48k",
            140 to "Audio 128k",
            141 to "Audio 256k",
            249 to "Audio Opus 50k",
            250 to "Audio Opus 70k",
            251 to "Audio Opus 160k",
        )

        /**
         * The page keeps its player response on `#movie_player`; `ytInitialPlayerResponse`
         * is the fallback for layout changes. Only a response that actually has stream
         * data is worth returning.
         */
        private val READ_PLAYER_RESPONSE_JS = """
            (function() {
                try {
                    // The WebView is off-screen, so the page thinks it is hidden and
                    // YouTube stops buffering. Pretend it is visible.
                    try {
                        Object.defineProperty(Document.prototype, 'hidden', {
                            configurable: true, get: function() { return false; }
                        });
                        Object.defineProperty(Document.prototype, 'visibilityState', {
                            configurable: true, get: function() { return 'visible'; }
                        });
                        document.dispatchEvent(new Event('visibilitychange'));
                    } catch (e) {}
                    var player = document.getElementById('movie_player');
                    var data = null;
                    if (player && typeof player.getPlayerResponse === 'function') {
                        data = player.getPlayerResponse();
                    }
                    if (!data) data = window.ytInitialPlayerResponse;
                    // Playing is what makes the player request the streams, and those
                    // requests are the links we want. The WebView allows autoplay.
                    try {
                        var media = document.querySelector('video');
                        if (media && media.paused) media.play();
                    } catch (e) {}
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
