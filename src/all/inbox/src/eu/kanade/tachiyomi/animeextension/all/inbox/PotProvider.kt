package eu.kanade.tachiyomi.animeextension.all.inbox

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import uy.kohesive.injekt.injectLazy
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Mints the `pot` (proof of origin) token that googlevideo wants on adaptive links.
 *
 * Without it a DASH link dies after one or two megabytes, so the relay had to ask
 * InnerTube for a new link every second. That burst is what ended up rate limited:
 * after a minute or two the player endpoint stops answering, the sound goes first
 * (its link is smaller) and then the picture stalls for good.
 *
 * The token is computed by YouTube's own BotGuard interpreter. The trick that makes
 * it usable here is [FRAME_URL]: the WebView is pointed at a youtube.com address and
 * the request is answered locally, so the page has the real origin (Google's CORS
 * allows it) but no content policy of its own, which `new Function(...)` needs.
 */
internal object PotProvider {
    private const val TAG = "INboxPot"

    /** Same key the web player uses when asking for a playback token. */
    private const val REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"

    /** Origin matters: it is what makes the calls to Google cross origin *allowed*. */
    private const val FRAME_URL = "https://www.youtube.com/inbox-pot"

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"

    private const val WAIT_SECONDS = 30L
    private const val CACHE_MS = 60 * 60 * 1000L
    private const val FAILURE_COOLDOWN_MS = 10 * 60 * 1000L

    private val context: Application by injectLazy()
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    @Volatile
    private var cached: String? = null

    @Volatile
    private var cachedFor: String? = null

    @Volatile
    private var cachedAt: Long = 0L

    /** When the last attempt failed: no point paying 30 s per chunk to fail again. */
    @Volatile
    private var failedAt: Long = 0L

    /** True while a WebView is being set up. Only one at a time. */
    private val minting = AtomicBoolean(false)

    /**
     * Asks for a token in the background. Nothing ever waits for it: setting up a
     * WebView takes a second or two, and the player must not pay that when it opens
     * a stream (that delay was visible as "it takes ages to start").
     */
    fun request(videoId: String) {
        if (valid(videoId)) return
        // A failed mint costs half a minute; when it fails the relay has to keep
        // working without a token instead of stalling every chunk.
        if (System.currentTimeMillis() - failedAt < FAILURE_COOLDOWN_MS) return
        if (!minting.compareAndSet(false, true)) return
        Thread(
            {
                try {
                    mint(videoId)
                } finally {
                    minting.set(false)
                }
            },
            "inbox-pot",
        ).apply { isDaemon = true }.start()
    }

    /** Whatever is cached, without asking for a new one (for the diagnostic screen). */
    fun peek(videoId: String): String? = if (valid(videoId)) cached else null

    /**
     * Asks for a token and waits at most [timeoutMs] for it.
     *
     * Listing the streams is the moment where the token changes the answer: the
     * client that accepts it is a web one, and that is only worth a request when the
     * token is there. A couple of seconds here are cheaper than a stream that dies
     * at minute one, but the player still must not hang: after the timeout the list
     * is built exactly as before, without a token.
     */
    fun await(videoId: String, timeoutMs: Long): String? {
        peek(videoId)?.let { return it }
        request(videoId)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            peek(videoId)?.let { return it }
            runCatching { Thread.sleep(100) }
        }
        return peek(videoId)
    }

    private fun valid(videoId: String): Boolean {
        val value = cached ?: return false
        return cachedFor == videoId && System.currentTimeMillis() - cachedAt < CACHE_MS && value.length > 80
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun mint(videoId: String): String? {
        // Only one WebView at a time: two relays asking together would fight over the
        // same cached slot and both pay the setup cost.
        synchronized(this) {
            if (valid(videoId)) return cached
            val started = System.currentTimeMillis()
            val latch = CountDownLatch(1)
            val result = AtomicReference<String?>(null)
            val view = AtomicReference<WebView?>(null)

            handler.post {
                runCatching {
                    val page = WebView(context)
                    view.set(page)
                    with(page.settings) {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        userAgentString = USER_AGENT
                    }
                    page.addJavascriptInterface(
                        object {
                            @JavascriptInterface
                            fun onPot(value: String) {
                                if (result.compareAndSet(null, value)) latch.countDown()
                            }
                        },
                        "InboxPot",
                    )
                    page.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest,
                        ): WebResourceResponse? {
                            if (request.url.toString().startsWith(FRAME_URL)) {
                                return WebResourceResponse(
                                    "text/html",
                                    "utf-8",
                                    ByteArrayInputStream(FRAME_HTML.toByteArray()),
                                )
                            }
                            return null
                        }

                        override fun onPageFinished(view: WebView, url: String?) {
                            inject(view, videoId, latch)
                        }
                    }
                    page.loadUrl(FRAME_URL)
                    // onPageFinished is not guaranteed for a response we synthesised,
                    // so this second try covers that case; the script runs only once.
                    handler.postDelayed({ view.get()?.let { inject(it, videoId, latch) } }, 2500)
                }.onFailure {
                    Log.w(TAG, "WebView setup failed: ${it.message}")
                    latch.countDown()
                }
            }

            val finished = latch.await(WAIT_SECONDS, TimeUnit.SECONDS)
            handler.post {
                view.get()?.let {
                    runCatching {
                        it.stopLoading()
                        it.removeJavascriptInterface("InboxPot")
                        it.destroy()
                    }
                }
            }

            val value = result.get()?.takeIf { it.length in 80..400 }
            if (value != null) {
                cached = value
                cachedFor = videoId
                cachedAt = System.currentTimeMillis()
                Log.d(TAG, "pot ready in ${System.currentTimeMillis() - started}ms (${value.length} chars)")
            } else {
                failedAt = System.currentTimeMillis()
                Log.w(TAG, "no pot (finished=$finished): ${result.get()?.take(120) ?: "nothing"}")
            }
            return value
        }
    }

    private const val FRAME_HTML = "<!DOCTYPE html><html><head><meta charset='utf-8'>" +
        "<title>pot</title></head><body><script>window.__started=false;</script></body></html>"

    private fun inject(view: WebView, videoId: String, latch: CountDownLatch) {
        runCatching {
            view.evaluateJavascript(POT_SCRIPT.replace("__VIDEO_ID__", videoId), null)
        }.onFailure {
            Log.w(TAG, "could not inject: ${it.message}")
            latch.countDown()
        }
    }

    /**
     * Straight port of the flow the web player runs: ask for a challenge, load the
     * interpreter, take a snapshot, exchange it for an integrity token and mint the
     * proof for this video.
     */
    private val POT_SCRIPT = """
        (async function () {
            function done(value) { InboxPot.onPot(value); }
            if (window.__potStarted) return;
            window.__potStarted = true;
            try {
                var KEY = '$REQUEST_KEY';
                var BASE = 'https://jnn-pa.googleapis.com/${'$'}rpc/google.internal.waa.v1.Waa';
                var HEADERS = {
                    'content-type': 'application/json+protobuf',
                    'x-goog-api-key': 'AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw',
                    'x-user-agent': 'grpc-web-javascript/0.1'
                };
                var created = await fetch(BASE + '/Create', {
                    method: 'POST', headers: HEADERS, body: JSON.stringify([KEY])
                });
                var raw = await created.json();
                var challenge = raw[0];
                if (!challenge && typeof raw[1] === 'string') {
                    var binary = atob(raw[1]);
                    var bytes = new Uint8Array(binary.length);
                    for (var i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i) + 97;
                    challenge = JSON.parse(new TextDecoder().decode(bytes));
                }
                if (!challenge) throw new Error('sin reto');
                var script = (challenge[1] || []).filter(function (v) { return typeof v === 'string'; })[0];
                var scriptUrl = (challenge[2] || []).filter(function (v) { return typeof v === 'string'; })[0];
                var program = challenge[4];
                var globalName = challenge[5];
                if (script) {
                    new Function(script)();
                } else if (scriptUrl) {
                    await new Promise(function (resolve, reject) {
                        var tag = document.createElement('script');
                        tag.src = scriptUrl;
                        tag.onload = resolve;
                        tag.onerror = reject;
                        document.head.appendChild(tag);
                    });
                }
                var vm = window[globalName];
                if (!vm || !vm.a) throw new Error('sin interprete');
                var functions = null;
                await vm.a(program, function (snapshot, shutdown) {
                    functions = { snapshot: snapshot };
                }, true, undefined, function () {}, [[], []], undefined, false,
                    [function () {}, function () {}, function () {}, function () {}, function () {}]);
                if (!functions) throw new Error('sin VM');
                var signals = [];
                var botguard = await new Promise(function (resolve, reject) {
                    try {
                        functions.snapshot(function (value) { resolve(value); },
                            [undefined, undefined, signals, undefined]);
                    } catch (error) { reject(error); }
                });
                var generated = await fetch(BASE + '/GenerateIT', {
                    method: 'POST', headers: HEADERS, body: JSON.stringify([KEY, botguard])
                });
                var payload = await generated.json();
                var integrityToken = payload[0];
                if (!integrityToken) throw new Error('sin integrity token');
                var mint = await signals[0](toBytes(integrityToken));
                var minted = await mint(new TextEncoder().encode('__VIDEO_ID__'));
                var text = '';
                for (var i = 0; i < minted.length; i++) text += String.fromCharCode(minted[i]);
                done(btoa(text).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+${'$'}/, ''));
            } catch (error) {
                done('ERR:' + (error && error.message ? error.message : error));
            }
            function toBytes(value) {
                var clean = String(value).replace(/-/g, '+').replace(/_/g, '/');
                var binary = atob(clean);
                var bytes = new Uint8Array(binary.length);
                for (var i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
                return bytes;
            }
        })();
    """.trimIndent()
}
