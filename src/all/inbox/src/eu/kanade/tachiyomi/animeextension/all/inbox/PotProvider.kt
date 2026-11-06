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

    /** The portada is asked as a desktop Chrome: that is the page that carries `ytAtN`. */
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

    private const val WAIT_SECONDS = 45L
    private const val CACHE_MS = 60 * 60 * 1000L
    private const val FAILURE_COOLDOWN_MS = 10 * 60 * 1000L

    private val context: Application by injectLazy()
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    @Volatile
    private var cached: String? = null

    /**
     * The visitor the token is bound to. The web token is not per video: it is a proof
     * for *this* visitor, and YouTube only honours it when the request carries the same
     * `visitorData`. Both travel together, hence both are cached.
     */
    @Volatile
    private var cachedVisitor: String? = null

    /** The `INNERTUBE_CLIENT_VERSION` of the page that minted the token. */
    @Volatile
    private var cachedVersion: String? = null

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
        if (valid()) return
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
    @Suppress("UNUSED_PARAMETER")
    fun peek(videoId: String): String? = if (valid()) cached else null

    /** The visitor the cached token belongs to, or null when there is no usable token. */
    fun visitor(): String? = if (valid()) cachedVisitor else null

    /** Client version of the page that minted the token, so the request looks the same. */
    fun webVersion(): String? = if (valid()) cachedVersion else null

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

    private fun valid(): Boolean {
        val value = cached ?: return false
        return System.currentTimeMillis() - cachedAt < CACHE_MS && value.length > 80
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun mint(videoId: String): String? {
        // Only one WebView at a time: two relays asking together would fight over the
        // same cached slot and both pay the setup cost.
        synchronized(this) {
            if (valid()) return cached
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
                            inject(view, latch)
                        }
                    }
                    page.loadUrl(FRAME_URL)
                    // onPageFinished is not guaranteed for a response we synthesised,
                    // so this second try covers that case; the script runs only once.
                    handler.postDelayed({ view.get()?.let { inject(it, latch) } }, 2500)
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

            // The script answers "visitante|versiÃ³n|token". The token is long: the
            // visitor data travels inside it, so a few hundred characters are normal.
            val parts = result.get()?.split('|')?.takeIf { it.size == 3 }
            val visitor = parts?.get(0)?.takeIf { it.length > 20 }
            val version = parts?.get(1)?.takeIf { it.startsWith("2.") }
            val value = parts?.get(2)?.takeIf { it.length in 80..4000 }
            if (visitor != null && version != null && value != null) {
                cachedVisitor = visitor
                cachedVersion = version
                cached = value
                cachedAt = System.currentTimeMillis()
                Log.d(
                    TAG,
                    "pot ready in ${System.currentTimeMillis() - started}ms " +
                        "(${value.length} chars, visitor ${visitor.take(12)}â€¦, $version)",
                )
            } else {
                failedAt = System.currentTimeMillis()
                Log.w(TAG, "no pot (finished=$finished): ${result.get()?.take(160) ?: "nothing"}")
            }
            return cached?.takeIf { valid() }
        }
    }

    private const val FRAME_HTML = "<!DOCTYPE html><html><head><meta charset='utf-8'>" +
        "<title>pot</title></head><body><script>window.__started=false;</script></body></html>"

    private fun inject(view: WebView, latch: CountDownLatch) {
        runCatching {
            view.evaluateJavascript(POT_SCRIPT, null)
        }.onFailure {
            Log.w(TAG, "could not inject: ${it.message}")
            latch.countDown()
        }
    }

    /**
     * The flow the web player runs (2026). The challenge is no longer asked to the
     * attestation service: tokens minted from it are rejected. It is taken from the
     * portada's own `ytAtN` payload, with its `ytcfg` injected so BotGuard sees the
     * page's EVENT_ID, and the proof is minted for that page's visitor, which is what
     * makes the token valid for the player request that carries the same visitor.
     *
     * Answers `visitante|versión|token`.
     */
    private val POT_SCRIPT = """
        (async function () {
            function done(value) { InboxPot.onPot(value); }
            if (window.__potStarted) return;
            window.__potStarted = true;
            var BASE = 'https://jnn-pa.googleapis.com/${'$'}rpc/google.internal.waa.v1.Waa';
            var KEY = '$REQUEST_KEY';
            var HEADERS = {
                'content-type': 'application/json+protobuf',
                'x-goog-api-key': 'AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw',
                'x-user-agent': 'grpc-web-javascript/0.1'
            };
            try {
                var html = await (await fetch('https://www.youtube.com/?hl=en&gl=US',
                    { credentials: 'include' })).text();
                var config = configOf(html);
                var visitor = config && config.VISITOR_DATA;
                var version = config && config.INNERTUBE_CLIENT_VERSION;
                if (!visitor) throw new Error('sin visitante');
                if (!version) throw new Error('sin version');
                window.yt = { config_: config };
                var challenge = challengeOf(html);
                if (challenge) {
                    await loadInterpreter(challenge);
                } else {
                    challenge = await createChallenge();
                    if (!challenge.script) throw new Error('sin interprete');
                    new Function(challenge.script)();
                }
                var vm = window[challenge.globalName];
                if (!vm || !vm.a) throw new Error('sin VM');
                var functions = null;
                await vm.a(challenge.program, function (snapshot) {
                    functions = { snapshot: snapshot };
                }, true, undefined, function () {}, [[], []], undefined, false,
                    [function () {}, function () {}, function () {}, function () {}, function () {}]);
                if (!functions) throw new Error('sin snapshot');
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
                var minted = await mint(new TextEncoder().encode(visitor));
                done(visitor + '|' + version + '|' + base64url(minted));
            } catch (error) {
                done('ERR:' + (error && error.message ? error.message : error));
            }

            function configOf(text) {
                var re = /ytcfg\.set\((\{[\s\S]+?\})\);/g;
                var merged = null;
                var match;
                while ((match = re.exec(text)) !== null) {
                    try {
                        var part = JSON.parse(match[1]);
                        if (!merged) merged = {};
                        for (var key in part) merged[key] = part[key];
                    } catch (ignored) { }
                }
                return merged;
            }

            function challengeOf(text) {
                var match = /window\.ytAtN\(\s*(\{[\s\S]*?\})\s*\)/.exec(text);
                if (!match) return null;
                try {
                    var outer = loose(match[1]);
                    var inner = typeof outer.R === 'string' ? JSON.parse(outer.R) : outer.R;
                    return inner && inner.bgChallenge ? inner.bgChallenge : null;
                } catch (ignored) {
                    return null;
                }
            }

            function loose(text) {
                var step = text.replace(/\\x([0-9A-Fa-f]{2})/g, function (all, hex) {
                    return String.fromCharCode(parseInt(hex, 16));
                });
                step = step.replace(/,\s*([\]}])/g, '${'$'}1');
                step = step.replace(/'((?:[^'\\]|\\[\s\S])*)'/g, function (all, inner) {
                    return JSON.stringify(inner.replace(/\\'/g, "'"));
                });
                step = step.replace(/([{,]\s*)([A-Za-z_][A-Za-z0-9_]*)\s*:/g, '${'$'}1"${'$'}2":');
                return JSON.parse(step);
            }

            async function loadInterpreter(challenge) {
                var url = challenge.interpreterUrl &&
                    challenge.interpreterUrl.privateDoNotAccessOrElseTrustedResourceUrlWrappedValue;
                if (!url) throw new Error('sin url de interprete');
                await new Promise(function (resolve, reject) {
                    var tag = document.createElement('script');
                    tag.src = url.indexOf('//') === 0 ? 'https:' + url : url;
                    tag.onload = function () { resolve(); };
                    tag.onerror = function () { reject(new Error('interprete no carga')); };
                    document.head.appendChild(tag);
                });
            }

            async function createChallenge() {
                var created = await fetch(BASE + '/Create', {
                    method: 'POST', headers: HEADERS, body: JSON.stringify([KEY])
                });
                var raw = await created.json();
                var list = raw[0];
                if (!list) throw new Error('Create ' + created.status);
                return {
                    script: (list[1] || []).filter(function (v) { return typeof v === 'string'; })[0],
                    program: list[4],
                    globalName: list[5]
                };
            }

            function base64url(bytes) {
                var text = '';
                for (var i = 0; i < bytes.length; i++) text += String.fromCharCode(bytes[i]);
                text = btoa(text).replace(/\+/g, '-').replace(/\//g, '_');
                while (text.charAt(text.length - 1) === '=') text = text.slice(0, -1);
                return text;
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
