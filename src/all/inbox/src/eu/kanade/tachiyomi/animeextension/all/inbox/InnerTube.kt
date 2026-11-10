package eu.kanade.tachiyomi.animeextension.all.inbox

import android.util.Log
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

internal class InnerTube(
    private val client: OkHttpClient,
    private val apiHeaders: (ClientSpec) -> Headers,
) {
    companion object {
        private const val TAG = "INbox"

        const val BASE_URL = "https://www.youtube.com"
        private const val API_URL = "$BASE_URL/youtubei/v1"
        private const val OEMBED_URL = "$BASE_URL/oembed"
        private const val CONSENT_COOKIE = "SOCS=CAESEwgDEgk0ODE3Nzk3NTQaAmVuIAEaBgiA_K-fBg"

        /**
         * Language and country the device is set to.
         *
         * YouTube serves *translated* titles when the account is known to speak that
         * language, and `hl = en` was making channels with localized titles show them
         * in English. Asking for the device's language keeps the title as its uploader
         * wrote it (and, when the device language is the original one, exactly the
         * same text YouTube's own app shows).
         */
        val LANG: String = Locale.getDefault().language
            .lowercase(Locale.ROOT)
            .takeIf { it.matches(Regex("[a-z]{2,3}")) }
            ?: "en"

        val COUNTRY: String = Locale.getDefault().country
            .uppercase(Locale.ROOT)
            .takeIf { it.matches(Regex("[A-Z]{2}")) }
            ?: "US"

        /** `Accept-Language` that matches [LANG]/[COUNTRY], with English as last resort. */
        fun acceptLanguage(): String = if (LANG == "en") "en-US,en;q=0.9" else "$LANG-$COUNTRY,$LANG;q=0.9,en;q=0.5"

        const val HEADER_VIDEO_ID = "X-Inbox-Video-Id"
        const val HEADER_CLIENT = "X-Inbox-Client"
        const val HEADER_TARGET = "X-Inbox-Target"
        const val HEADER_TARGET_URL = "X-Inbox-Target-Url"
        const val HEADER_FEED = "X-Inbox-Feed"
        const val HEADER_PAGE = "X-Inbox-Page"

        /** InnerTube search params (base64 protobuf) for the result type. */
        const val PARAMS_VIDEOS = "EgIQAQ=="
        const val PARAMS_CHANNELS = "EgIQAg=="
        const val PARAMS_PLAYLISTS = "EgIQAw=="
        const val PARAMS_CHANNEL_VIDEOS = "EgZ2aWRlb3PyBgQKAjoA"

        /**
         * Search params for the "Recientes" catalogue: videos uploaded today, in
         * relevance order. The `sortBy` field of the search params is ignored by
         * YouTube now, but the upload-date filter still narrows the whole result set
         * to today's videos, which is what makes it a feed of what just came out.
         */
        const val PARAMS_RECENT = "EgQIAhAB"

        /** Same filter, but for the whole week: more results, still recent. */
        const val PARAMS_WEEK = "EgQIAxAB"

        /** How many pages a lost continuation chain may be walked again for. */
        private const val MAX_REBUILD_PAGES = 12

        /**
         * Type labels YouTube prints next to a title ("Playlist", "Lista de
         * reproducción"...). They are UI text, not data, so they are kept out of the
         * description. Listed in the languages the app is likely to run in.
         */
        private val CONTENT_LABELS = setOf(
            "playlist", "playlists",
            "lista de reproducción", "lista de reproduccion",
            "listas de reproducción", "listas de reproduccion",
            "liste de lecture", "wiedergabeliste", "afspeellijst", "playlist do youtube",
            "再生リスト", "プレイリスト", "재생목록", "قائمة تشغيل", "danh sách phát",
            "mix", "álbum", "album", "álbum de música", "album de musica",
            "curso", "course", "música", "music",
        )

        fun sortParams(sort: Int): String = when (sort) {
            1 -> "CAISAhAB"
            2 -> "CAMSAhAB"
            3 -> "CAESAhAB"
            else -> PARAMS_VIDEOS
        }

        fun watchUrl(videoId: String): String = "$BASE_URL/watch?v=$videoId"

        private val JSON = Json { ignoreUnknownKeys = true }
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        /** Bumped on every diagnostic change so the report says which build it came from. */
        private const val DIAG_VERSION = "v14 · 14.37"

        /**
         * The visitor the player requests announce when no token has been minted yet.
         *
         * The `VISIONOS` client gives the whole DASH file, but some videos answer
         * "Sign in to confirm you're not a bot" to a request that arrives without a
         * visitor. The watch page carries one (`ytcfg`), so it is read once and kept.
         */
        @Volatile
        private var pageVisitor: String? = null
        private val visitorLock = Any()

        /** User agent for the media requests, used by the relay too. */
        const val PLAYBACK_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"

        val WEB = ClientSpec(
            id = "1",
            name = "WEB",
            version = "2.20250312.04.00",
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0",
        )

        /**
         * The visionOS app. It is the only client measured to hand out the **whole**
         * adaptive file: direct `googlevideo` links with no `n` parameter (nothing to
         * descramble), no poToken, no `signatureTimestamp`, and range requests that
         * answer 206 all the way to the last byte. The Android/iOS clients answer
         * 403 after ~60 s of an adaptive stream, which is the freeze at the minute.
         */
        val VISIONOS = ClientSpec(
            id = "28",
            name = "VISIONOS",
            version = "1.02",
            userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15",
            extra = buildJsonObject {
                put("deviceMake", "Apple")
                put("deviceModel", "RealityDevice17,1")
                put("osName", "visionOS")
                put("osVersion", "26.0.0.23M")
            },
        )

        val ANDROID = ClientSpec(
            id = "3",
            name = "ANDROID",
            version = "20.10.38",
            userAgent = "com.google.android.youtube/20.10.38 (Linux; U; Android 11) gzip",
            extra = buildJsonObject {
                put("osName", "Android")
                put("osVersion", "11")
                put("androidSdkVersion", 30)
            },
        )

        val ANDROID_VR = ClientSpec(
            id = "28",
            name = "ANDROID_VR",
            version = "1.61.48",
            userAgent = "com.google.android.apps.youtube.vr.oculus/1.61.48 (Linux; U; Android 12; en_US; Oculus Quest 3; Build/SQ3A.220605.009.A1; Cronet/132.0.6808.3)",
            extra = buildJsonObject {
                put("osName", "Android")
                put("osVersion", "12")
                put("androidSdkVersion", 32)
                put("deviceMake", "Oculus")
                put("deviceModel", "Quest 3")
            },
        )

        val IOS = ClientSpec(
            id = "5",
            name = "IOS",
            version = "20.10.4",
            userAgent = "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
            extra = buildJsonObject {
                put("osName", "iPhone")
                put("osVersion", "18.3.2.22D82")
                put("deviceMake", "Apple")
                put("deviceModel", "iPhone16,2")
            },
        )

        val TVHTML5 = ClientSpec(
            id = "7",
            name = "TVHTML5",
            version = "7.20250312.16.00",
            userAgent = "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/25.lts.30.0-gold (unlike Gecko) v8/8.9.255.20-gold starboard/evergreen-amd64 JavaScript/1.0",
        )

        val TV_EMBEDDED = ClientSpec(
            id = "85",
            name = "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
            version = "2.0",
            userAgent = "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/25.lts.30.0-gold (unlike Gecko) v8/8.9.255.20-gold starboard/evergreen-amd64 JavaScript/1.0",
            embedUrl = "https://www.youtube.com/",
        )

        val WEB_EMBEDDED = ClientSpec(
            id = "56",
            name = "WEB_EMBEDDED_PLAYER",
            version = "1.20250312.01.00",
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
            embedUrl = "https://www.youtube.com/",
        )

        val MWEB = ClientSpec(
            id = "2",
            name = "MWEB",
            version = "2.20250312.04.00",
            userAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_3_2 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.3 Mobile/15E148 Safari/604.1",
        )

        val PLAYER_CLIENTS = listOf(
            VISIONOS,
            ANDROID,
            IOS,
            ANDROID_VR,
            TVHTML5,
            TV_EMBEDDED,
            WEB_EMBEDDED,
            MWEB,
            WEB,
        )

        /**
         * Order of clients to ask. `VISIONOS` always goes first: it is the one that
         * returns complete DASH links, so the rest are only a fallback for the odd
         * video it refuses. The chosen preference keeps its place right behind it.
         */
        fun playerOrder(preferred: String): List<ClientSpec> {
            val first = PLAYER_CLIENTS.firstOrNull { it.name == preferred && it !== VISIONOS } ?: ANDROID
            return listOf(VISIONOS, first) + PLAYER_CLIENTS.filter { it !== VISIONOS && it !== first }
        }

        /**
         * Version to declare for a client. The web ones announce the version of the page
         * that minted the token: with a stale one YouTube answers "the page needs to be
         * reloaded", which is its way of saying the client is too old.
         */
        fun versionFor(spec: ClientSpec): String = if (spec.web) webVersion() ?: spec.version else spec.version

        /** Visitor the cached token belongs to; null while there is no token. */
        fun visitor(): String? = runCatching { PotProvider.visitor() }.getOrNull()

        fun webVersion(): String? = runCatching { PotProvider.webVersion() }.getOrNull()
    }

    /** The piece of the app the player will call next: a single video, a playlist or a channel. */
    internal enum class Kind { VIDEO, PLAYLIST, CHANNEL }

    internal data class Target(val kind: Kind, val id: String)

    internal data class Entry(
        val id: String,
        val kind: Kind,
        val title: String,
        val author: String,
        val thumbnail: String,
        val description: String,
    )

    internal data class Listing(val entries: List<Entry>, val continuation: String?)

    internal data class Metadata(val title: String, val author: String, val thumbnail: String, val description: String)

    private val probeClient: OkHttpClient by lazy {
        client.newBuilder()
            .callTimeout(6, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Headers for the DASH links handed to the player.
     *
     * googlevideo answers 403 to these links when the request carries the Android
     * client's user agent (it then wants the poToken), but serves them with 206 for a
     * plain browser agent. Muxed formats work either way, so only DASH is affected.
     */
    private val playbackHeaders: Headers = Headers.Builder()
        .add("User-Agent", PLAYBACK_UA)
        .add("Accept", "*/*")
        .add("Accept-Language", "en-US,en;q=0.9")
        .add("Origin", "https://www.youtube.com")
        .add("Referer", "https://www.youtube.com/")
        .build()

    /** Cache of relay urls, so one entry per stream is created. */
    private val relayUrls = mutableMapOf<String, String>()

    /** Continuation handed out by each page of a scrolling feed, keyed `feed|page`. */
    private val feedTokens = HashMap<String, String>()

    /** Query and params of each feed, so a lost chain can be walked again. */
    private val feedBases = HashMap<String, Pair<String, String>>()

    private val feedLock = Any()

    init {
        // The relay asks for a new signed link whenever googlevideo starts refusing one.
        StreamRelay.resolver = { videoId, itag, xTags, clientName ->
            freshSource(videoId, itag, xTags, clientName)
        }
        // Mint the token while the person is still choosing what to watch, so the first
        // video already opens a web session instead of spending its first seconds here.
        runCatching { PotProvider.prewarm() }
        // The visitor the video requests announce is read from the page; doing it here,
        // in the background, keeps that request off the path that opens the video.
        runCatching { thread(isDaemon = true, name = "inbox-visitor") { pageVisitor() } }
    }

    /**
     * The order of clients to ask. `VISIONOS` comes first and needs no token, so there
     * is nothing to wait for here: the list opens as soon as the request answers.
     */
    fun orderFor(preferred: String): List<ClientSpec> = playerOrder(preferred)

    /**
     * Asks InnerTube for a fresh googlevideo link for one exact stream. Called by the
     * relay when the link it was using runs out of quota (HTTP 403/410).
     *
     * The match is on itag **and** xtags: the same itag carries the original audio, the
     * dubbed one and the volume-normalised one, and swapping between them mid-playback
     * is what made the sound disappear.
     */
    private fun freshSource(videoId: String, itag: String, xTags: String, clientName: String): RelaySource? {
        val primary = PLAYER_CLIENTS.firstOrNull { it.name == clientName } ?: ANDROID
        // The same itag exists in every client, so a couple of tries is enough. The
        // web clients are left out on purpose: their links and their SABR endpoint are
        // refused from here, and a fresh web link would replace a session that works.
        val candidates = listOf(primary, VISIONOS, IOS, ANDROID)
        for (spec in candidates.distinctBy { it.name }) {
            val source = runCatching { sourceFor(videoId, spec, itag, xTags) }.getOrNull()
            if (source != null) return source
        }
        return null
    }

    private fun sourceFor(videoId: String, spec: ClientSpec, itag: String, xTags: String): RelaySource? {
        val body = client.newCall(playerRequest(videoId, spec)).execute().use { it.body.string() }
        val json = JSON.parseToJsonElement(body).jsonObject
        if (json.obj("playabilityStatus")?.str("status") != "OK") return null
        val streamingData = json.obj("streamingData") ?: return null
        val formats = streamingData.arr("adaptiveFormats").orEmpty() + streamingData.arr("formats").orEmpty()
        val format = formats.mapNotNull { it as? JsonObject }
            .firstOrNull { parseInt(it.str("itag"))?.toString() == itag && xTagsOf(it.str("url")) == xTags }
            ?: return null
        val url = format.str("url") ?: return null
        return RelaySource(url, sabrSpec(json, streamingData, spec, format))
    }

    /**
     * The SABR session for one format, when the player response carries one.
     *
     * `serverAbrStreamingUrl` is what the YouTube apps stream from: it is not limited
     * to the first minute, and every segment it sends states the byte range it occupies
     * in the file, which is what lets the relay serve a normal mp4 the player can seek.
     */
    private fun sabrSpec(
        json: JsonObject,
        streamingData: JsonObject,
        spec: ClientSpec,
        format: JsonObject,
    ): SabrSpec? {
        // The `VISIONOS` links already serve the whole file, so there is nothing to
        // rebuild: plain ranges are faster and do not need a token.
        if (spec.direct) return null
        val abrUrl = streamingData.str("serverAbrStreamingUrl")?.takeIf { it.isNotBlank() } ?: return null
        val config = json.obj("playerConfig")
            ?.obj("mediaCommonConfig")
            ?.obj("mediaUstreamerRequestConfig")
            ?.str("videoPlaybackUstreamerConfig")
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val itag = parseInt(format.str("itag")) ?: return null
        val index = format.obj("indexRange")
        return SabrSpec(
            abrUrl = abrUrl,
            ustreamerConfig = config,
            itag = itag,
            lastModified = format.str("lastModified").orEmpty(),
            xTags = xTagsOf(format.str("url")),
            audioTrackId = format.obj("audioTrack")?.str("id"),
            isAudio = format.str("mimeType").orEmpty().startsWith("audio/"),
            height = parseInt(format.str("height")) ?: 0,
            clientId = spec.id.toIntOrNull() ?: 3,
            clientVersion = versionFor(spec),
            osName = spec.extra.str("osName"),
            osVersion = spec.extra.str("osVersion"),
            deviceMake = spec.extra.str("deviceMake"),
            deviceModel = spec.extra.str("deviceModel"),
            userAgent = spec.userAgent,
            initEnd = format.obj("initRange")?.str("end")?.toLongOrNull() ?: 0L,
            indexStart = index?.str("start")?.toLongOrNull() ?: 0L,
            indexEnd = index?.str("end")?.toLongOrNull() ?: 0L,
            length = format.str("contentLength")?.toLongOrNull() ?: 0L,
            durationMs = (json.obj("videoDetails")?.str("lengthSeconds")?.toLongOrNull() ?: 0L) * 1000L,
        )
    }

    /** Pubic url for the player: the local relay whenever it is available. */
    private fun relayed(
        videoId: String,
        spec: ClientSpec,
        itag: Int?,
        xTags: String,
        url: String,
        length: Long,
        contentType: String,
        sabr: SabrSpec? = null,
    ): String {
        if (itag == null) return url
        val key = "$videoId|$itag|$xTags|${spec.name}"
        synchronized(relayUrls) {
            relayUrls[key]?.let { return it }
            val relayed = runCatching {
                StreamRelay.register(videoId, itag.toString(), xTags, spec.name, url, length, contentType, sabr)
            }.getOrDefault(url)
            relayUrls[key] = relayed
            return relayed
        }
    }

    // ============================== Requests ==============================

    fun searchRequest(query: String, params: String = PARAMS_VIDEOS): Request {
        val body = buildJsonObject {
            put("context", context(WEB))
            put("query", query.ifBlank { "trending" })
            put("params", params)
        }
        return post("$API_URL/search?prettyPrint=false", body, apiHeaders(WEB))
    }

    /**
     * One page of a scrolling feed.
     *
     * Page 1 is the search itself; every later page reuses the continuation the
     * previous page handed out, because a search has no offset and that token is the
     * only way to keep going. The catalogue asks for the pages in order while the
     * person scrolls, so the token is normally already cached.
     */
    fun feedRequest(feed: String, page: Int, query: String, params: String): Request {
        val pageNo = page.coerceAtLeast(1)
        // The key rides in a header, and a header only takes printable ASCII: a search
        // for "canción" used to fail before the request was even sent. The payload
        // (the query itself) is untouched and still travels in the JSON body.
        val key = feed.headerSafe()
        synchronized(feedLock) {
            if (feedBases.size > 64) feedBases.clear()
            if (feedTokens.size > 256) feedTokens.clear()
            feedBases[key] = query to params
            // Asking for page 1 again means a refresh: the old chain does not apply.
            if (pageNo == 1) feedTokens.keys.removeAll { it.startsWith("$key|") }
        }
        val token = feedToken(key, pageNo)
        return if (token != null) {
            taggedPage(searchContinuationRequest(token), key, pageNo)
        } else {
            // The chain could not be rebuilt: page 1 is still a valid answer.
            taggedPage(searchRequest(query, params), key, 1)
        }
    }

    /** Stores the continuation a feed page returned, so the next page can be asked for. */
    fun rememberFeedPage(feed: String, page: Int, continuation: String?) {
        if (continuation.isNullOrBlank()) return
        synchronized(feedLock) { feedTokens["$feed|$page"] = continuation }
    }

    /**
     * Continuation that opens [page]. When it is not cached (the app was restarted
     * halfway down the list) the chain is walked again: one cheap request per skipped
     * page, which is what makes a jump straight to page 9 still work.
     */
    private fun feedToken(feed: String, page: Int): String? {
        if (page <= 1) return null
        // A jump to a very deep page is not worth a burst of requests: page 1 will do.
        if (page - 1 > MAX_REBUILD_PAGES) return null
        val base = synchronized(feedLock) { feedBases[feed] } ?: return null
        var token: String? = null
        var step = 0
        while (step < page - 1) {
            val cached = synchronized(feedLock) { feedTokens["$feed|$step"] }
            if (cached != null) {
                token = cached
                step++
                continue
            }
            val request = token?.let { searchContinuationRequest(it) }
                ?: searchRequest(base.first, base.second)
            val next = runCatching {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        null
                    } else {
                        parseListing(response.body.string().orEmpty()).continuation
                    }
                }
            }.getOrNull() ?: return null
            synchronized(feedLock) { feedTokens["$feed|$step"] = next }
            token = next
            step++
        }
        return token
    }

    /** Page header, so the parser knows which feed and page a body belongs to. */
    private fun taggedPage(request: Request, feed: String, page: Int): Request = request.newBuilder()
        .header(HEADER_FEED, feed.headerSafe())
        .header(HEADER_PAGE, page.toString())
        .build()

    /**
     * Next page of a search. Search continuations live on `/search`; posting the same
     * token to `/browse` answers with an empty body.
     */
    fun searchContinuationRequest(token: String): Request {
        val body = buildJsonObject {
            put("context", context(WEB))
            put("continuation", token)
        }
        return post("$API_URL/search?prettyPrint=false", body, apiHeaders(WEB))
    }

    fun browseRequest(browseId: String, params: String? = null): Request {
        val body = buildJsonObject {
            put("context", context(WEB))
            put("browseId", browseId)
            if (params != null) put("params", params)
        }
        return post("$API_URL/browse?prettyPrint=false", body, apiHeaders(WEB))
    }

    fun playlistRequest(playlistId: String): Request = browseRequest(if (playlistId.startsWith("VL")) playlistId else "VL$playlistId")

    fun channelVideosRequest(channelId: String): Request = browseRequest(channelId, PARAMS_CHANNEL_VIDEOS)

    fun continuationRequest(token: String): Request {
        val body = buildJsonObject {
            put("context", context(WEB))
            put("continuation", token)
        }
        return post("$API_URL/browse?prettyPrint=false", body, apiHeaders(WEB))
    }

    fun playerRequest(videoId: String, spec: ClientSpec): Request {
        // The web clients refuse to hand out anything without it, and the app ones
        // ignore it, so it only travels where it is understood.
        val pot = if (spec.web) runCatching { PotProvider.peek(videoId) }.getOrNull() else null
        val sts = if (spec.web) PotProvider.signatureTimestamp() else null
        val body = buildJsonObject {
            put("context", context(spec))
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
            put(
                "playbackContext",
                buildJsonObject {
                    put(
                        "contentPlaybackContext",
                        buildJsonObject {
                            put("html5Preference", "HTML5_PREF_WANTS")
                            // Says which player build the request speaks. Without it the
                            // web clients answer "The page needs to be reloaded." instead
                            // of the formats, which is what kept the session off-web.
                            sts?.let { put("signatureTimestamp", it) }
                        },
                    )
                },
            )
            if (!pot.isNullOrBlank()) {
                put(
                    "serviceIntegrityDimensions",
                    buildJsonObject {
                        put("poToken", pot)
                    },
                )
            }
        }
        return post("$API_URL/player?prettyPrint=false", body, apiHeaders(spec))
            .newBuilder()
            .header(HEADER_VIDEO_ID, videoId)
            .header(HEADER_CLIENT, spec.name)
            .build()
    }

    fun oembedRequest(videoId: String): Request {
        val url = "$OEMBED_URL?url=${"https://www.youtube.com/watch?v=$videoId".encodeUrl()}&format=json"
        return Request.Builder()
            .url(url)
            .headers(apiHeaders(WEB))
            .header("Accept-Language", acceptLanguage())
            .get()
            .build()
    }

    /** Real watch page, used for details and for the client's WebView. */
    fun watchPageRequest(videoId: String): Request = Request.Builder()
        .url(watchUrl(videoId))
        .header("User-Agent", WEB.userAgent)
        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .header("Accept-Language", acceptLanguage())
        .addHeader("Cookie", CONSENT_COOKIE)
        .get()
        .build()

    /** The video id of a player response, read back from the request it was built from. */
    fun videoIdOf(response: Response): String? = response.request.header(HEADER_VIDEO_ID)
        ?: extractVideoId(response.request.url.toString())

    private fun post(url: String, body: JsonObject, headers: Headers): Request = Request.Builder()
        .url(url)
        .post(body.toString().toRequestBody(JSON_MEDIA))
        .headers(headers)
        .build()

    // ============================== Parsing ===============================

    fun parseListing(body: String): Listing {
        if (body.isBlank()) return Listing(emptyList(), null)
        val root = try {
            JSON.parseToJsonElement(body)
        } catch (e: Exception) {
            Log.e(TAG, "Listing parse failed", e)
            return Listing(emptyList(), null)
        }
        val entries = mutableListOf<Entry>()
        walkRenderers(root, entries)
        return Listing(entries.distinctBy { it.kind to it.id }, findContinuation(root))
    }

    private val rendererKeys = setOf(
        "videoRenderer",
        "gridVideoRenderer",
        "reelItemRenderer",
        "playlistVideoRenderer",
        "playlistRenderer",
        "channelRenderer",
        "lockupViewModel",
        "shortsLockupViewModel",
    )

    private fun walkRenderers(element: JsonElement, out: MutableList<Entry>) {
        when (element) {
            is JsonObject -> {
                element.forEach { (key, value) ->
                    if (key in rendererKeys) {
                        (value as? JsonObject)?.let { parseRenderer(key, it)?.let(out::add) }
                    } else {
                        walkRenderers(value, out)
                    }
                }
            }
            is JsonArray -> element.forEach { walkRenderers(it, out) }
            else -> Unit
        }
    }

    private fun parseRenderer(key: String, renderer: JsonObject): Entry? = when (key) {
        "videoRenderer", "gridVideoRenderer", "reelItemRenderer" -> parseVideoRenderer(renderer)
        "playlistVideoRenderer" -> parsePlaylistVideoRenderer(renderer)
        "playlistRenderer" -> parsePlaylistRenderer(renderer)
        "channelRenderer" -> parseChannelRenderer(renderer)
        "lockupViewModel" -> parseLockup(renderer)
        "shortsLockupViewModel" -> parseShortsLockup(renderer)
        else -> null
    }

    private fun parseVideoRenderer(renderer: JsonObject): Entry? {
        val id = renderer.str("videoId") ?: renderer.obj("videoId")?.str("videoId") ?: return null
        val title = renderer.obj("title").text() ?: id
        val author = renderer.obj("ownerText").text()
            ?: renderer.obj("longBylineText").text()
            ?: renderer.obj("shortBylineText").text()
            ?: ""
        val thumbnail = renderer.obj("thumbnail").thumbUrl()
            ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg"
        val duration = renderer.obj("lengthText").text().orEmpty()
        val published = renderer.obj("publishedTimeText").text().orEmpty()
        return Entry(id, Kind.VIDEO, title, author, thumbnail, joinInfo(duration, published))
    }

    private fun parsePlaylistVideoRenderer(renderer: JsonObject): Entry? {
        val id = renderer.str("videoId") ?: return null
        val title = renderer.obj("title").text() ?: id
        val author = renderer.obj("shortBylineText").text().orEmpty()
        val thumbnail = renderer.obj("thumbnail").thumbUrl()
            ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg"
        val duration = renderer.obj("lengthText").text().orEmpty()
        return Entry(id, Kind.VIDEO, title, author, thumbnail, joinInfo(duration))
    }

    private fun parsePlaylistRenderer(renderer: JsonObject): Entry? {
        val id = renderer.str("playlistId") ?: return null
        val title = renderer.obj("title").text() ?: id
        val author = renderer.obj("shortBylineText").text()
            ?: renderer.obj("longBylineText").text()
            ?: ""
        val thumbnail = renderer.thumbUrl()
            ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg"
        val count = renderer.obj("videoCountText").text()
            ?: renderer.str("videoCount")
            ?: ""
        return Entry(id, Kind.PLAYLIST, title, author, thumbnail, joinInfo(count))
    }

    private fun parseChannelRenderer(renderer: JsonObject): Entry? {
        val id = renderer.str("channelId") ?: return null
        val title = renderer.obj("title").text() ?: id
        val thumbnail = renderer.obj("thumbnail").thumbUrl().orEmpty()
        val subscribers = renderer.obj("videoCountText").text()
            ?: renderer.obj("subscriberCountText").text()
            ?: ""
        val description = renderer.obj("descriptionSnippet").text().orEmpty()
        return Entry(id, Kind.CHANNEL, title, subscribers, thumbnail, description)
    }

    private fun parseLockup(lockup: JsonObject): Entry? {
        val id = lockup.str("contentId") ?: return null
        val contentType = lockup.str("contentType").orEmpty()
        val kind = when {
            contentType.contains("PLAYLIST") || contentType.contains("SHOW") ||
                contentType.contains("COURSE") || contentType.contains("ALBUM") -> Kind.PLAYLIST
            contentType.contains("CHANNEL") -> Kind.CHANNEL
            contentType.contains("VIDEO") -> Kind.VIDEO
            id.startsWith("UC") && id.length == 24 -> Kind.CHANNEL
            id.startsWith("PL") || id.startsWith("UU") || id.startsWith("OL") -> Kind.PLAYLIST
            else -> Kind.VIDEO
        }
        val metadata = lockup.obj("metadata")?.obj("lockupMetadataViewModel")
        val title = metadata?.obj("title").text() ?: id
        val rows = metadata?.obj("metadata")
            ?.obj("contentMetadataViewModel")
            ?.arr("metadataRows")
            .orEmpty()
        val parts = rows.flatMap(::partsOf)
        // The byline row is the one YouTube marks as such: its first part is the
        // author. Falling back to the first part keeps videos (whose only row is the
        // view count) as they were.
        val byline = rows.firstOrNull {
            (it as? JsonObject)?.obj("lockupContentMetadataRowExtension")?.str("contentType") ==
                "METADATA_ROW_CONTENT_TYPE_BYLINE"
        }
        val author = byline?.let { partsOf(it).firstOrNull() } ?: parts.firstOrNull().orEmpty()
        val info = parts
            .filterNot { it == author }
            .filterNot { it.lowercase(Locale.ROOT) in CONTENT_LABELS }
            .joinToString(" · ")
        val thumbnail = lockup.obj("contentImage").let { image ->
            image?.obj("thumbnailViewModel")?.thumbUrl()
                ?: image?.obj("collectionThumbnailViewModel")?.obj("primaryThumbnail")
                    ?.obj("thumbnailViewModel")?.thumbUrl()
                ?: image?.obj("backgroundImageViewModel")?.obj("thumbnailViewModel")?.thumbUrl()
        }.orEmpty()
        return Entry(id, kind, title, author, thumbnail, info)
    }

    private fun parseShortsLockup(shorts: JsonObject): Entry? {
        val reel = shorts.obj("onTap")?.obj("innertubeCommand")?.obj("reelWatchEndpoint")
        val id = shorts.str("entityId")?.removePrefix("shorts-shelf-item-")
            ?: reel?.str("videoId")
            ?: return null
        val overlay = shorts.obj("overlayMetadata")
        val title = overlay?.obj("primaryText").text()
            ?: shorts.str("accessibilityText")?.substringBefore(",")
            ?: id
        val views = overlay?.obj("secondaryText").text().orEmpty()
        val thumbnail = reel?.obj("thumbnail")?.thumbUrl()
            ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg"
        return Entry(id, Kind.VIDEO, title, "", thumbnail, views)
    }

    fun parseMetadata(body: String): Metadata? {
        if (body.isBlank()) return null
        val root = try {
            JSON.parseToJsonElement(body)
        } catch (e: Exception) {
            return null
        }
        val microformat = findFirst(root, "microformatDataRenderer")
        val playlist = findFirst(root, "playlistMetadataRenderer")
        if (playlist != null) {
            // The owner card of a playlist page ("videoOwnerRenderer") is the channel
            // the playlist belongs to: the playlist metadata itself carries no author,
            // which is why those series used to show up as unknown.
            val owner = findFirst(root, "videoOwnerRenderer")?.obj("title").text().orEmpty()
            return Metadata(
                title = playlist.str("title").orEmpty(),
                author = owner,
                thumbnail = microformat?.obj("thumbnail").thumbUrl().orEmpty(),
                description = playlist.str("description").orEmpty(),
            )
        }
        val channel = findFirst(root, "channelMetadataRenderer")
        if (channel != null) {
            return Metadata(
                title = channel.str("title").orEmpty(),
                author = channel.str("vanityChannelUrl").orEmpty(),
                thumbnail = channel.obj("avatar")?.thumbUrl().orEmpty(),
                description = channel.str("description").orEmpty(),
            )
        }
        return null
    }

    private fun findContinuation(root: JsonElement): String? {
        val item = findFirst(root, "continuationItemRenderer") ?: return null
        return item.obj("continuationEndpoint")?.obj("continuationCommand")?.str("token")
    }

    // ============================== Player ===============================

    fun resolvePlayer(
        response: Response,
        includeDash: Boolean,
        includeMuxed: Boolean,
        fetchSubtitles: Boolean,
        preferredAudioLang: String,
        preferredSubtitleLang: String,
        preferredQuality: String,
        preferredClient: String,
        verifyStreams: Boolean,
        pagePlayer: ((String) -> PageStreams?)? = null,
    ): List<Video> {
        val videoId = response.request.header(HEADER_VIDEO_ID)
            ?: extractVideoId(response.request.url.toString())
            ?: throw Exception("INbox: request without a video id")

        // The response that came in was signed for one exact client, and its links and
        // SABR session belong to it: that client has to be the one used first, whatever
        // the order says now. `VISIONOS` wins anyway: its links are the complete ones.
        val fromResponse = response.request.header(HEADER_CLIENT)
        val order = playerOrder(preferredClient)
            .sortedWith(
                compareBy<ClientSpec> {
                    when (it.name) {
                        VISIONOS.name -> 0
                        fromResponse -> 1
                        else -> 2
                    }
                },
            )
        val failures = mutableListOf<String>()

        val givenBody = response.use { it.body.string() }.orEmpty()
        // The body only serves the loop when it was signed for the client in front of
        // it; a body from another client would be read as if its links were complete.
        val reuseBody = fromResponse == order.first().name
        var body = if (reuseBody) givenBody else ""
        for ((index, spec) in order.withIndex()) {
            if (index > 0 || !reuseBody) {
                body = try {
                    client.newCall(playerRequest(videoId, spec)).execute()
                        .use { it.body.string() }.orEmpty()
                } catch (e: Exception) {
                    failures += "${spec.name}: ${e.message}"
                    continue
                }
            }
            if (body.isBlank()) {
                failures += "${spec.name}: empty response"
                continue
            }
            val json = try {
                JSON.parseToJsonElement(body).jsonObject
            } catch (e: Exception) {
                failures += "${spec.name}: invalid json"
                continue
            }
            val status = json.obj("playabilityStatus")
            val state = status?.str("status") ?: "UNKNOWN"
            if (state != "OK") {
                val reason = status?.str("reason").orEmpty()
                failures += "${spec.name}: $state${if (reason.isNotBlank()) " ($reason)" else ""}"
                continue
            }

            val videos = try {
                buildVideos(
                    json = json,
                    spec = spec,
                    videoId = videoId,
                    includeDash = includeDash,
                    includeMuxed = includeMuxed,
                    fetchSubtitles = fetchSubtitles,
                    preferredAudioLang = preferredAudioLang,
                    preferredSubtitleLang = preferredSubtitleLang,
                    preferredQuality = preferredQuality,
                )
            } catch (e: Exception) {
                failures += "${spec.name}: ${e.message}"
                continue
            }
            if (videos.isEmpty()) {
                failures += "${spec.name}: no formats"
                continue
            }

            val playable = if (verifyStreams) filterPlayable(videos) else videos
            if (playable.isNotEmpty()) {
                Log.d(TAG, "Resolved ${playable.size} streams with ${spec.name} (${videos.size} candidates)")
                return playable
            }
            failures += "${spec.name}: ${videos.size} format(s) rejected (HTTP 4xx, likely blocked)"
        }

        // Last resort: the same clients again, keeping every candidate without probing.
        val nativeDash = nativeDash(
            videoId = videoId,
            order = order,
            fetchSubtitles = fetchSubtitles,
            preferredAudioLang = preferredAudioLang,
            preferredSubtitleLang = preferredSubtitleLang,
            preferredQuality = preferredQuality,
            verifyStreams = verifyStreams,
            failures = failures,
        )
        if (nativeDash.isNotEmpty()) return nativeDash
        throw Exception("INbox: no playable stream. ${failures.joinToString(" | ")}")
    }

    /** Compact report for the in-app DASH test. */
    fun diagnose(
        videoId: String,
        includeDash: Boolean,
        includeMuxed: Boolean,
        preferredAudioLang: String,
        preferredQuality: String,
        preferredClient: String,
        fetchSubtitles: Boolean,
        preferredSubtitleLang: String,
        pagePlayer: ((String) -> PageStreams?)?,
    ): String = buildString {
        appendLine("ID: $videoId")
        appendLine("Prueba $DIAG_VERSION")
        val order = playerOrder(preferredClient)
        appendLine("Orden: ${order.joinToString(", ") { it.name }}")
        appendLine("Visitante: ${runCatching { visitorId(VISIONOS) }.getOrNull()?.take(24) ?: "no"}")

        val videos = runCatching {
            val response = client.newCall(playerRequest(videoId, order.first())).execute()
            resolvePlayer(
                response = response,
                includeDash = includeDash,
                includeMuxed = includeMuxed,
                fetchSubtitles = fetchSubtitles,
                preferredAudioLang = preferredAudioLang,
                preferredSubtitleLang = preferredSubtitleLang,
                preferredQuality = preferredQuality,
                preferredClient = preferredClient,
                // Show every candidate: the point is to see what the player would get.
                verifyStreams = false,
                pagePlayer = pagePlayer,
            )
        }.getOrElse {
            appendLine("Lista: error ${it.message}")
            emptyList()
        }

        appendLine("Lista que recibe el reproductor: ${videos.size}")
        val shown = if (videos.size > 5) videos.take(4) + videos.last() else videos
        shown.forEachIndexed { index, video ->
            val url = video.videoUrl ?: return@forEachIndexed
            val kind = if (StreamRelay.isRelay(url)) "relé" else flags(url)
            val audio = video.audioTracks.joinToString(" / ") { it.lang }.ifBlank { "ninguno" }
            val subs = video.subtitleTracks.joinToString(" / ") { it.lang }.ifBlank { "ninguno" }
            appendLine("${index + 1}. ${video.quality} ($kind)")
            appendLine("   audio: $audio")
            appendLine("   subtítulos: $subs")
        }

        // What matters now: reading through the relay exactly like the player does,
        // with one open ended range, well past the point where the link used to die.
        val dash = videos.firstOrNull { it.audioTracks.isNotEmpty() && it.videoUrl != null }
        if (dash != null) {
            appendLine("Por el relé, como el reproductor:")
            appendLine("  servicio: ${StreamRelay.describe(dash.videoUrl!!)}")
            appendLine("  video: ${relayRead(dash.videoUrl!!, 6 * 1024 * 1024)}")
            // The minute is exactly where the old link died, so the interesting read is
            // the one that starts past the middle of the file, not the first megabytes.
            val half = StreamRelay.lengthOf(dash.videoUrl!!) / 2
            if (half > 0) {
                appendLine("  video a mitad (byte $half): ${relayRead(dash.videoUrl!!, 2 * 1024 * 1024, half)}")
            }
            // And the last bytes: the file has to be readable all the way to its end.
            val size = StreamRelay.lengthOf(dash.videoUrl!!)
            if (size > 0) {
                appendLine("  video al final (byte ${size - 4096}): ${relayRead(dash.videoUrl!!, 4096, size - 4096)}")
            }
            appendLine("  estado SABR: ${StreamRelay.describe(dash.videoUrl!!)}")
            dash.audioTracks.firstOrNull()?.let {
                appendLine("  audio «${it.lang}»: ${relayRead(it.url, 1024 * 1024)}")
                appendLine("  servicio audio: ${StreamRelay.describe(it.url)}")
            }
        } else {
            appendLine("Sin DASH: no hay nada que releer")
        }

        val muxed = videos.firstOrNull { it.audioTracks.isEmpty() && it.videoUrl != null }
        if (muxed != null) {
            appendLine("Muxed directo: ${probe(muxed.videoUrl!!, withHeaders = true, range = "bytes=0-1")}")
        }

        appendLine("Mitad del archivo (enlace nuevo de ${order.first().name}): ${windowLine(videoId, order.first())}")

        val pot = runCatching { PotProvider.peek(videoId) }.getOrNull()
        appendLine(
            "poToken: " + if (pot == null) {
                "no (no hace falta con VISIONOS)" + runCatching { PotProvider.lastError() }.getOrNull()?.let { " ($it)" }.orEmpty()
            } else {
                "sí (${pot.length} caracteres, visitante ${visitor()?.take(12) ?: "?"}…, ${webVersion() ?: "?"}, sts ${PotProvider.signatureTimestamp() ?: "?"})"
            },
        )

        appendLine("Por cliente (estado y muxed):")
        for (spec in order) {
            val line = runCatching {
                val body = client.newCall(playerRequest(videoId, spec)).execute()
                    .use { it.body.string() }
                val json = JSON.parseToJsonElement(body).jsonObject
                val status = json.obj("playabilityStatus")
                val state = status?.str("status") ?: "?"
                if (state != "OK") {
                    val reason = status?.str("reason").orEmpty().take(60)
                    return@runCatching if (reason.isBlank()) state else "$state ($reason)"
                }
                val streamingData = json.obj("streamingData") ?: return@runCatching "sin streamingData"
                val muxedHeights = streamingData.arr("formats").orEmpty()
                    .mapNotNull { it as? JsonObject }
                    .mapNotNull { parseInt(it.str("height")) }
                    .distinct()
                    .sortedDescending()
                val adaptive = streamingData.arr("adaptiveFormats").orEmpty().size
                "OK, $adaptive adaptativos, muxed ${muxedHeights.joinToString("/") { "${it}p" }}"
            }.getOrElse { "error ${it.message}" }
            appendLine("  ${spec.name}: $line")
        }
    }

    /**
     * Proof, on the device itself, of the limit that kills DASH: a *freshly signed*
     * adaptive link also answers 403 when asked for the middle of the file. Measured
     * on several videos: one link only ever serves its first ~58 seconds, in every
     * client, with or without poToken. The muxed link is probed the same way to show
     * the difference (it answers 206).
     */
    private fun windowLine(videoId: String, spec: ClientSpec): String {
        fun deep(format: JsonObject?): String {
            val url = format?.str("url")
            val length = format?.str("contentLength")?.toLongOrNull() ?: 0L
            if (url.isNullOrBlank()) return "sin enlace"
            if (length <= 0) return "sin tamaño"
            val start = length / 2
            return probe(url, withHeaders = true, range = "bytes=$start-${start + 1}")
        }
        return try {
            val body = client.newCall(playerRequest(videoId, spec)).execute().use { it.body.string() }
            val streaming = JSON.parseToJsonElement(body).jsonObject.obj("streamingData")
            val formats = streaming?.arr("adaptiveFormats").orEmpty().mapNotNull { it as? JsonObject }
            val video = formats.filter { it.str("mimeType").orEmpty().startsWith("video/mp4") }
                .maxByOrNull { parseInt(it.str("height")) ?: 0 }
            val muxed = streaming?.arr("formats").orEmpty().mapNotNull { it as? JsonObject }.firstOrNull()
            "video ${deep(video)} · muxed ${deep(muxed)}"
        } catch (e: Exception) {
            "error ${e.message}"
        }
    }

    /**
     * Reads [maxBytes] from [url] with a single open ended range, which is what the
     * player sends when it starts a stream.
     */
    private fun relayRead(url: String, maxBytes: Long, from: Long = 0L): String = try {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", PLAYBACK_UA)
            .header("Range", "bytes=$from-")
            .get()
            .build()
        val started = System.currentTimeMillis()
        probeClient.newBuilder()
            .callTimeout(120, TimeUnit.SECONDS)
            .build()
            .newCall(request)
            .execute()
            .use { response ->
                if (response.code !in 200..299) {
                    "HTTP ${response.code}"
                } else {
                    val stream = response.body.byteStream()
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (total < maxBytes) {
                        val read = stream.read(buffer, 0, minOf(buffer.size.toLong(), maxBytes - total).toInt())
                        if (read == -1) break
                        total += read
                    }
                    val seconds = (System.currentTimeMillis() - started) / 1000.0
                    val speed = if (seconds > 0.05) total / 1024.0 / 1024.0 / seconds else 0.0
                    val time = String.format(Locale.US, "%.1f", seconds)
                    val rate = String.format(Locale.US, "%.2f", speed)
                    "HTTP ${response.code}, ${total / 1024}KB en ${time}s ($rate MB/s)"
                }
            }
    } catch (e: Exception) {
        "error ${e.message}"
    }

    private fun flags(url: String): String {
        val n = if (paramValue(url, "n") != null) "n" else "-"
        val pot = if (paramValue(url, "pot") != null) "pot" else "-"
        return "$n $pot"
    }

    private fun paramValue(url: String, name: String): String? = Regex("[?&]$name=([^&]*)").find(url)?.groupValues?.get(1)

    private fun probe(
        url: String,
        withHeaders: Boolean,
        range: String,
    ): String = probeWith(url, if (withHeaders) playbackHeaders else Headers.Builder().build(), range)

    private fun probeWith(
        url: String,
        headers: Headers,
        range: String = "bytes=0-1",
    ): String = try {
        val request = Request.Builder().url(url).headers(headers).header("Range", range).get().build()
        probeClient.newCall(request).execute().use { response ->
            "HTTP ${response.code}"
        }
    } catch (e: Exception) {
        "error ${e.message}"
    }

    /** Last resort when the watch page could not be read: DASH straight from InnerTube. */
    private fun nativeDash(
        videoId: String,
        order: List<ClientSpec>,
        fetchSubtitles: Boolean,
        preferredAudioLang: String,
        preferredSubtitleLang: String,
        preferredQuality: String,
        verifyStreams: Boolean,
        failures: MutableList<String>,
    ): List<Video> {
        var withHeadersOnly: List<Video> = emptyList()
        for (spec in order) {
            val body = try {
                client.newCall(playerRequest(videoId, spec)).execute()
                    .use { it.body.string() }
            } catch (e: Exception) {
                failures += "${spec.name} DASH: ${e.message}"
                continue
            }
            val json = runCatching { JSON.parseToJsonElement(body).jsonObject }.getOrNull() ?: continue
            if (json.obj("playabilityStatus")?.str("status") != "OK") continue
            val videos = runCatching {
                buildVideos(
                    json = json,
                    spec = spec,
                    videoId = videoId,
                    includeDash = true,
                    includeMuxed = false,
                    fetchSubtitles = fetchSubtitles,
                    preferredAudioLang = preferredAudioLang,
                    preferredSubtitleLang = preferredSubtitleLang,
                    preferredQuality = preferredQuality,
                )
            }.getOrNull().orEmpty()
            if (videos.isEmpty()) continue
            val playable = if (verifyStreams) filterPlayable(videos) else videos
            if (playable.isEmpty()) {
                failures += "${spec.name} DASH: ${videos.size} format(s) rejected"
                continue
            }
            // A URL that only answers when the app's own headers are sent is useless:
            // the player is the one doing the request, with its own user agent. Keep
            // looking for a client whose links work on their own.
            val firstUrl = playable.firstOrNull { it.videoUrl != null }?.videoUrl
            if (verifyStreams && firstUrl != null && !bareProbe(firstUrl)) {
                if (withHeadersOnly.isEmpty()) withHeadersOnly = playable
                failures += "${spec.name} DASH: only answers with the app headers"
                continue
            }
            return playable
        }
        return withHeadersOnly
    }

    /** Asks for one byte without the app's headers, the way a plain player would. */
    private fun bareProbe(url: String): Boolean = try {
        if (StreamRelay.isRelay(url)) return true
        val request = Request.Builder().url(url).header("Range", "bytes=0-1").get().build()
        probeClient.newCall(request).execute().use { it.code in 200..299 || it.code >= 500 }
    } catch (e: Exception) {
        true
    }

    private fun buildVideos(
        json: JsonObject,
        spec: ClientSpec,
        videoId: String,
        includeDash: Boolean,
        includeMuxed: Boolean,
        fetchSubtitles: Boolean,
        preferredAudioLang: String,
        preferredSubtitleLang: String,
        preferredQuality: String,
    ): List<Video> {
        val streamingData = json.obj("streamingData") ?: throw Exception("missing streamingData")
        val headers = streamHeaders(spec)
        val subtitles = if (fetchSubtitles) {
            filterSubtitles(extractSubtitles(json), preferredSubtitleLang)
        } else {
            emptyList()
        }
        val videos = mutableListOf<Video>()
        val wantMuxed = includeMuxed || !includeDash

        if (wantMuxed) {
            val muxed = streamingData.arr("formats").orEmpty().mapNotNull { element ->
                val format = element as? JsonObject ?: return@mapNotNull null
                val url = format.str("url") ?: return@mapNotNull null
                if (!format.str("mimeType").orEmpty().startsWith("video/")) return@mapNotNull null
                val height = parseInt(format.str("height")) ?: 0
                val bitrate = parseInt(format.str("bitrate")) ?: 0
                val label = format.str("qualityLabel") ?: if (height > 0) "${height}p" else "Muxed"
                MuxedRep(height, bitrate, label, url)
            }.sortedWith(compareByDescending<MuxedRep> { it.height }.thenByDescending { it.bitrate })

            val muxedOrder = if (preferredQuality == "lowest") muxed.sortedBy { it.height } else muxed
            for (rep in muxedOrder) {
                if (!passesQuality(rep.height, preferredQuality)) continue
                videos += Video(rep.url, "Muxed ${rep.label} · completo", rep.url, headers, subtitleTracks = subtitles)
            }
        }

        if (includeDash) {
            val adaptive = streamingData.arr("adaptiveFormats").orEmpty()
            val audios = adaptive.mapNotNull { element ->
                val format = element as? JsonObject ?: return@mapNotNull null
                val url = format.str("url") ?: return@mapNotNull null
                val mime = format.str("mimeType").orEmpty()
                if (!mime.startsWith("audio/mp4") && !mime.startsWith("audio/webm")) return@mapNotNull null
                val track = format.obj("audioTrack")
                val xTags = xTagsOf(url)
                val rawName = track?.str("displayName")?.takeIf { it.isNotBlank() }
                    ?: format.str("audioQuality")
                        ?.takeIf { it.isNotBlank() && !it.startsWith("AUDIO_QUALITY") }
                        .orEmpty()
                val original = xTags.contains("acont=original") || rawName.contains("original", ignoreCase = true)
                val drc = xTags.contains("drc=")
                val lang = audioLanguage(xTags, rawName)
                AudioRep(
                    url = url,
                    bitrate = parseInt(format.str("bitrate")) ?: 0,
                    lang = lang,
                    label = audioLabel(rawName, lang, original, drc, xTags),
                    mp4 = mime.startsWith("audio/mp4"),
                    itag = parseInt(format.str("itag")),
                    length = format.str("contentLength")?.toLongOrNull() ?: 0L,
                    xTags = xTags,
                    original = original,
                    drc = drc,
                    sabr = sabrSpec(json, streamingData, spec, format),
                )
            }
                // One itag is served in several languages and encodings, but the low
                // bitrate copies die after a single chunk, so for each language keep the
                // best copy (mp4 first, highest bitrate). The two original mixes YouTube
                // offers (normal and volume normalised) stay, they are different tracks.
                .sortedWith(
                    compareByDescending<AudioRep> { it.mp4 }
                        .thenByDescending { it.bitrate },
                )
                .distinctBy { "${it.lang}|${it.original}|${it.drc}" }
                .sortedWith(
                    compareByDescending<AudioRep> { it.original }
                        .thenBy { it.drc }
                        .thenByDescending { it.mp4 }
                        .thenByDescending { it.bitrate },
                )
            val filteredAudios = filterAudios(audios, preferredAudioLang)

            if (filteredAudios.isNotEmpty()) {
                val videoReps = adaptive.mapNotNull { element ->
                    val format = element as? JsonObject ?: return@mapNotNull null
                    val url = format.str("url") ?: return@mapNotNull null
                    val mime = format.str("mimeType").orEmpty()
                    if (!mime.startsWith("video/")) return@mapNotNull null
                    val container = if (mime.startsWith("video/webm")) "video/webm" else "video/mp4"
                    val codec = mime.substringAfter("codecs=\"", "").substringBefore("\"")
                    val height = parseInt(format.str("height")) ?: return@mapNotNull null
                    // H.264 is the codec every phone decodes, but YouTube stops at
                    // 1080p with it: only VP9 and AV1 carry 1440p and 2160p. Those are
                    // taken just for the extra resolutions, so nothing changes for the
                    // usual ones, and VP9 (profile 0) ranks ahead of AV1 and of the
                    // 10-bit profile 2, which are the least supported.
                    val rank = when {
                        codec.isBlank() || codec.startsWith("avc1") -> 0
                        codec.startsWith("vp9.2") || codec.startsWith("vp09.2") -> 3
                        codec.startsWith("vp9") || codec.startsWith("vp09") -> 1
                        codec.startsWith("av01") -> 2
                        else -> return@mapNotNull null
                    }
                    if (rank > 0 && height <= 1080) return@mapNotNull null
                    VideoRep(
                        url = url,
                        height = height,
                        bitrate = parseInt(format.str("bitrate")) ?: 0,
                        itag = parseInt(format.str("itag")),
                        length = format.str("contentLength")?.toLongOrNull() ?: 0L,
                        sabr = sabrSpec(json, streamingData, spec, format),
                        container = container,
                        rank = rank,
                    )
                }
                    .sortedWith(
                        compareByDescending<VideoRep> { it.height }
                            .thenBy { it.rank }
                            .thenByDescending { it.bitrate },
                    )
                    .distinctBy { it.height }

                // The relay turns the open ended ranges the player asks for into
                // bounded ones, which is the only way these links keep serving.
                // The relay rebuilds each of these streams from SABR (the endpoint the
                // YouTube apps use), so DASH is not limited to the first minute any more.
                val audioTracks = mutableListOf<Track>()
                val audioOnly = mutableListOf<Pair<String, String>>()
                for (audio in filteredAudios) {
                    val audioUrl = relayed(
                        videoId = videoId,
                        spec = spec,
                        itag = audio.itag,
                        xTags = audio.xTags,
                        url = audio.url,
                        length = audio.length,
                        contentType = if (audio.mp4) "audio/mp4" else "audio/webm",
                        sabr = audio.sabr,
                    )
                    audioTracks += Track(audioUrl, audio.label)
                    audioOnly += audioUrl to audio.label
                }

                // One entry per quality: the audio languages travel as tracks of the
                // same video, which is what the player maps. Before this there was one
                // entry per quality *and* language, so DASH repeated itself.
                val orderedReps = videoReps
                    .filter { passesQuality(it.height, preferredQuality) }
                    .let { reps ->
                        if (preferredQuality == "lowest") reps.sortedBy { it.height } else reps.sortedByDescending { it.height }
                    }
                for (rep in orderedReps) {
                    val videoUrl = relayed(
                        videoId = videoId,
                        spec = spec,
                        itag = rep.itag,
                        xTags = "",
                        url = rep.url,
                        length = rep.length,
                        contentType = rep.container,
                        sabr = rep.sabr,
                    )
                    val headers = if (StreamRelay.isRelay(videoUrl)) null else playbackHeaders
                    videos += Video(
                        videoUrl,
                        "DASH ${rep.height}p · completo",
                        videoUrl,
                        headers,
                        audioTracks = audioTracks,
                        subtitleTracks = subtitles,
                    )
                }

                // The same sound with no picture at all. The downloader maps `0:v` on
                // every file, so the entry carries a single black frame that lasts as
                // long as the song and is dropped again before writing: what is saved
                // is the song alone, and playing the entry shows a still screen. The
                // title of the track is the label the app puts on the audio.
                val lengthMs = (json.obj("videoDetails")?.str("lengthSeconds")?.toLongOrNull() ?: 0L) * 1000L
                val still = StreamRelay.registerStatic("video/mp4", StillVideo.mp4(lengthMs))
                for ((url, label) in audioOnly) {
                    val headers = if (StreamRelay.isRelay(url)) null else playbackHeaders
                    videos += if (still == null) {
                        Video(url, "DASH solo audio · $label", url, headers)
                    } else {
                        StillVideo.soundOnly(
                            Video(
                                still,
                                "DASH solo audio · $label",
                                still,
                                null,
                                audioTracks = listOf(Track(url, label)),
                            ),
                        )
                    }
                }
            }
        }

        return videos.distinctBy { it.videoUrl to it.quality }
    }

    /**
     * YouTube hands out googlevideo URLs that can be rejected (403) depending on the account,
     * region, IP family or the `n` throttling parameter. Probing a single byte tells us which
     * formats the device can actually play, so the player never receives a dead URL.
     */
    private fun filterPlayable(videos: List<Video>): List<Video> {
        val cache = mutableMapOf<String, Boolean>()

        // The relay answers locally: it is not the link that has to be checked, the
        // relay itself retries with a fresh one when googlevideo refuses.
        fun playable(url: String): Boolean = StreamRelay.isRelay(url) || cache.getOrPut(url) { probe(url) }
        return videos.filter { video ->
            val videoUrl = video.videoUrl
            videoUrl != null && playable(videoUrl) && video.audioTracks.all { playable(it.url) }
        }
    }

    private fun probe(url: String): Boolean {
        val request = try {
            Request.Builder()
                .url(url)
                .headers(playbackHeaders)
                .header("Range", "bytes=0-1")
                .get()
                .build()
        } catch (e: Exception) {
            return true
        }
        return try {
            probeClient.newCall(request).execute().use { response ->
                val code = response.code
                if (code !in 200..299) Log.w(TAG, "Probe HTTP $code (${url.substringBefore('?').takeLast(40)})")
                // 5xx / network hiccups are not proof that the stream is dead.
                code in 200..299 || code >= 500
            }
        } catch (e: Exception) {
            Log.w(TAG, "Probe failed: ${e.message}")
            true
        }
    }

    private fun extractSubtitles(json: JsonObject): List<SubtitleRep> {
        val tracks = json.obj("captions")
            ?.obj("playerCaptionsTracklistRenderer")
            ?.arr("captionTracks") ?: return emptyList()
        return tracks.mapNotNull { element ->
            val track = element as? JsonObject ?: return@mapNotNull null
            val baseUrl = track.str("baseUrl") ?: return@mapNotNull null
            val code = track.str("languageCode").orEmpty().lowercase(Locale.US)
            val kind = track.str("kind").orEmpty()
            val auto = kind.contains("asr", ignoreCase = true)
            // "English (auto-generated)" is long and repeats the language, the name of
            // the language plus the (auto) mark says the same in less space.
            val language = languageName(code).ifBlank { track.obj("name")?.text().orEmpty().ifBlank { code } }
            val label = if (auto) "$language (auto)" else language
            val url = if (baseUrl.contains("&fmt=") || baseUrl.contains("?fmt=")) baseUrl else "$baseUrl&fmt=vtt"
            SubtitleRep(url, code, label, auto)
        }
    }

    /**
     * The language picked in the settings is a code ("es", "en"...), and the caption
     * tracks carry one, so this maps properly instead of handing the player the first
     * track of whatever came back. "Auto" used to keep a single track, which is how the
     * auto generated subtitles ended up missing.
     */
    private fun filterSubtitles(tracks: List<SubtitleRep>, preferredSubtitleLang: String): List<Track> {
        if (tracks.isEmpty()) return emptyList()
        val chosen = when (preferredSubtitleLang) {
            "all", "auto", "" -> tracks
            else -> tracks.filter { it.code.startsWith(preferredSubtitleLang) }.ifEmpty { tracks }
        }
        return chosen.distinctBy { "${it.code}|${it.auto}" }.map { Track(it.url, it.name) }
    }

    private fun passesQuality(height: Int, preferredQuality: String): Boolean {
        if (height <= 0) return true
        // "auto", "highest" and "lowest" keep everything, in that order; a number is
        // the ceiling (1080p by default), which is what makes the list start there.
        val cap = preferredQuality.toIntOrNull() ?: return true
        return height <= cap
    }

    private fun filterAudios(audios: List<AudioRep>, preferredAudioLang: String): List<AudioRep> {
        // "original" is already the head of the list, so nothing to reorder.
        if (audios.isEmpty() || preferredAudioLang == "all" || preferredAudioLang == "original") return audios
        val preferred = audios.filter { it.lang.startsWith(preferredAudioLang) }
        // The chosen language goes first (that is the one the player starts with) and
        // the rest stay available as tracks instead of being thrown away.
        return if (preferred.isEmpty()) audios else preferred + audios.filterNot { it.lang.startsWith(preferredAudioLang) }
    }

    // ============================ Languages =============================

    private val languageNames = mapOf(
        "spanish" to ("es" to "Español"),
        "english" to ("en" to "Inglés"),
        "portuguese" to ("pt" to "Portugués"),
        "japanese" to ("ja" to "Japonés"),
        "french" to ("fr" to "Francés"),
        "german" to ("de" to "Alemán"),
        "italian" to ("it" to "Italiano"),
        "russian" to ("ru" to "Ruso"),
        "hindi" to ("hi" to "Hindi"),
        "korean" to ("ko" to "Coreano"),
        "chinese" to ("zh" to "Chino"),
        "arabic" to ("ar" to "Árabe"),
        "turkish" to ("tr" to "Turco"),
        "indonesian" to ("id" to "Indonesio"),
        "polish" to ("pl" to "Polaco"),
        "thai" to ("th" to "Tailandés"),
        "vietnamese" to ("vi" to "Vietnamita"),
        "ukrainian" to ("uk" to "Ucraniano"),
    )

    private fun languageEntry(text: String): Pair<String, String>? {
        val lower = text.lowercase(Locale.US)
        return languageNames.entries.firstOrNull { lower.contains(it.key) }?.value
    }

    private fun languageName(code: String): String {
        val prefix = code.take(2).lowercase(Locale.US)
        return languageNames.values.firstOrNull { it.first == prefix }?.second.orEmpty()
    }

    /**
     * The player response leaves `languageCode` empty and hides the language inside the
     * xtags of the url ("...acont=original:lang=es-US"), which is why the language
     * setting used to match nothing.
     */
    private fun audioLanguage(xTags: String, rawName: String): String {
        Regex("lang=([A-Za-z-]+)").find(xTags)?.groupValues?.get(1)?.let {
            return it.lowercase(Locale.US)
        }
        return languageEntry(rawName)?.first.orEmpty()
    }

    private fun audioLabel(rawName: String, lang: String, original: Boolean, drc: Boolean, xTags: String): String {
        val entry = languageEntry(rawName)
        val base = when {
            entry != null -> {
                val region = Regex("\\(([A-Za-z]{2})\\)").find(rawName)?.groupValues?.get(1)
                if (region != null) "${entry.second} ($region)" else entry.second
            }
            else -> rawName.substringBefore(" original", rawName).trim()
                .ifBlank { languageName(lang).ifBlank { "Audio" } }
        }
        val marked = xTags.contains("acont=") ||
            rawName.contains("original", ignoreCase = true) ||
            rawName.contains("dubbed", ignoreCase = true)
        if (!marked) return base
        val kind = when {
            original -> "original"
            xTags.contains("dubbed-auto") || rawName.contains("auto-dubbed", ignoreCase = true) -> "doblado auto"
            else -> "doblado"
        }
        return if (drc) "$base · $kind · volumen" else "$base · $kind"
    }

    /**
     * The url carries the audio identity in `xtags`, percent encoded
     * (`acont%3Doriginal%3Alang%3Des-US`), so it has to be decoded before it can be
     * read: that is where the language and the original/dubbed flag live.
     */
    private fun xTagsOf(url: String?): String {
        val raw = paramValue(url.orEmpty(), "xtags").orEmpty()
        if (raw.isEmpty() || !raw.contains("%")) return raw
        return runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
    }

    private fun context(spec: ClientSpec): JsonObject = buildJsonObject {
        put(
            "client",
            buildJsonObject {
                put("clientName", spec.name)
                put("clientVersion", versionFor(spec))
                put("hl", LANG)
                put("gl", COUNTRY)
                // The token was minted for the homepage's visitor: a player request
                // that announces another one gets it ignored (and the stream cut).
                visitorId(spec)?.let { put("visitorData", it) }
                spec.extra.forEach { (key, value) -> put(key, value) }
            },
        )
        spec.embedUrl?.let { url ->
            put("thirdParty", buildJsonObject { put("embedUrl", url) })
        }
        put("user", buildJsonObject { put("lockedSafetyMode", false) })
        put("request", buildJsonObject { put("useSsl", true) })
    }

    /**
     * Visitor announced to [spec]. The web clients need the one the token was minted
     * for; the app ones just need a valid visitor, so the page's is used while there
     * is no token (it costs one plain request, not a WebView round trip).
     */
    private fun visitorId(spec: ClientSpec): String? = visitor() ?: if (spec.web) null else pageVisitor()

    /**
     * Visitor the watch page hands out in its `ytcfg`.
     *
     * Some videos answer "Sign in to confirm you're not a bot" to a player request
     * without a visitor, so every app client announces the one from here. Read once and
     * kept for the whole session.
     */
    private fun pageVisitor(): String? {
        pageVisitor?.let { return it }
        synchronized(visitorLock) {
            pageVisitor?.let { return it }
            val value = runCatching {
                val request = Request.Builder()
                    .url("$BASE_URL/?hl=en&gl=US")
                    .header("User-Agent", VISIONOS.userAgent)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .addHeader("Cookie", CONSENT_COOKIE)
                    .get()
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    Regex("\"VISITOR_DATA\":\"([^\"]+)\"").find(response.body.string())
                        ?.groupValues?.get(1)
                }
            }.getOrNull()
            if (!value.isNullOrBlank()) pageVisitor = value
            return value
        }
    }

    private fun streamHeaders(spec: ClientSpec): Headers = Headers.Builder()
        .add("User-Agent", spec.userAgent)
        .add("Accept", "*/*")
        .add("Accept-Language", "en-US,en;q=0.9")
        .add("Origin", "https://www.youtube.com")
        .add("Referer", "https://www.youtube.com/")
        .build()

    // ============================== Helpers ==============================

    private fun findFirst(root: JsonElement, key: String): JsonObject? {
        when (root) {
            is JsonObject -> {
                (root[key] as? JsonObject)?.let { return it }
                for (value in root.values) {
                    findFirst(value, key)?.let { return it }
                }
            }
            is JsonArray -> {
                for (value in root) {
                    findFirst(value, key)?.let { return it }
                }
            }
            else -> Unit
        }
        return null
    }

    private fun joinInfo(vararg parts: String?): String = parts.filterNot { it.isNullOrBlank() }.joinToString(" · ")

    /** Text of the `metadataParts` of one lockup row. */
    private fun partsOf(row: JsonElement?): List<String> = (row as? JsonObject)?.arr("metadataParts").orEmpty()
        .mapNotNull { (it as? JsonObject)?.obj("text").text()?.takeIf(String::isNotBlank) }
}

// ============================== Model ==============================

internal data class ClientSpec(
    val id: String,
    val name: String,
    val version: String,
    val userAgent: String,
    val extra: JsonObject = JsonObject(emptyMap()),
    /** Set for the embedded players: YouTube wants the page they are embedded in. */
    val embedUrl: String? = null,
) {
    /** The clients that share the page version and the visitor of the minted token. */
    val web: Boolean get() = name == "WEB" || name == "MWEB"

    /** Clients whose media links already serve the whole file (no SABR to rebuild). */
    val direct: Boolean get() = name == "VISIONOS"
}

private data class VideoRep(
    val url: String,
    val height: Int,
    val bitrate: Int,
    val itag: Int?,
    val length: Long,
    val sabr: SabrSpec?,
    /** `video/mp4` (H.264/AV1) or `video/webm` (VP9): the relay repeats it. */
    val container: String = "video/mp4",
    /** Codec compatibility: 0 H.264, 1 VP9, 2 AV1, 3 VP9 profile 2. */
    val rank: Int = 0,
)

private data class MuxedRep(val height: Int, val bitrate: Int, val label: String, val url: String)
private data class AudioRep(
    val url: String,
    val bitrate: Int,
    val lang: String,
    val label: String,
    val mp4: Boolean,
    val itag: Int?,
    val length: Long,
    val xTags: String,
    val original: Boolean,
    val drc: Boolean,
    val sabr: SabrSpec?,
)

private data class SubtitleRep(val url: String, val code: String, val name: String, val auto: Boolean)

internal fun extractVideoId(text: String): String? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    Regex("[?&]v=([A-Za-z0-9_-]{11})").find(trimmed)?.let { return it.groupValues[1] }
    Regex("(?:youtube\\.com/(?:watch\\?(?:[^#]*&)?v=|shorts/|embed/|live/|v/)|youtu\\.be/)([A-Za-z0-9_-]{11})")
        .find(trimmed)?.let { return it.groupValues[1] }
    if (trimmed.matches(Regex("[A-Za-z0-9_-]{11}"))) return trimmed
    return null
}

internal fun extractPlaylistId(text: String): String? {
    val trimmed = text.trim()
    Regex("[?&]list=([A-Za-z0-9_-]+)").find(trimmed)?.let { return it.groupValues[1] }
    if (trimmed.startsWith("playlist:")) return trimmed.removePrefix("playlist:").trim().takeIf { it.isNotEmpty() }
    return null
}

internal fun extractChannelId(text: String): String? {
    val trimmed = text.trim()
    Regex("youtube\\.com/channel/(UC[A-Za-z0-9_-]{22})").find(trimmed)?.let { return it.groupValues[1] }
    if (trimmed.startsWith("channel:")) return trimmed.removePrefix("channel:").trim().takeIf { it.isNotEmpty() }
    return null
}

internal fun InnerTube.Target.encode(): String = when (kind) {
    InnerTube.Kind.PLAYLIST -> "playlist:$id"
    InnerTube.Kind.CHANNEL -> "channel:$id"
    InnerTube.Kind.VIDEO -> "video:$id"
}

internal fun decodeTarget(url: String): InnerTube.Target {
    val trimmed = url.trim()
    return when {
        trimmed.startsWith("playlist:") ->
            InnerTube.Target(InnerTube.Kind.PLAYLIST, trimmed.removePrefix("playlist:").trim())
        trimmed.startsWith("channel:") ->
            InnerTube.Target(InnerTube.Kind.CHANNEL, trimmed.removePrefix("channel:").trim())
        trimmed.startsWith("video:") ->
            InnerTube.Target(InnerTube.Kind.VIDEO, trimmed.removePrefix("video:").trim())
        else -> InnerTube.Target(
            InnerTube.Kind.VIDEO,
            extractVideoId(trimmed) ?: trimmed,
        )
    }
}

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content?.takeIf { it != "null" }

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

internal fun JsonElement?.text(): String? {
    val element = this ?: return null
    if (element is JsonPrimitive) return element.content.takeIf { it != "null" && it.isNotBlank() }
    val obj = element as? JsonObject ?: return null
    obj.str("simpleText")?.let { return it }
    obj.str("content")?.let { return it }
    val runs = obj.arr("runs") ?: return null
    return runs.mapNotNull { (it as? JsonObject)?.str("text") }
        .joinToString("")
        .takeIf { it.isNotBlank() }
}

internal fun JsonElement?.thumbUrl(): String? {
    val obj = this as? JsonObject ?: return null
    obj.arr("thumbnails")?.lastOrNull()?.let { (it as? JsonObject)?.str("url")?.let { url -> return url } }
    obj.obj("thumbnail")?.arr("thumbnails")?.lastOrNull()
        ?.let { (it as? JsonObject)?.str("url")?.let { url -> return url } }
    obj.obj("image")?.arr("sources")?.lastOrNull()
        ?.let { (it as? JsonObject)?.str("url")?.let { url -> return url } }
    return null
}

private fun parseInt(s: String?): Int? = s?.toLongOrNull()?.toInt()

/**
 * Printable-ASCII form of a string that has to travel in an HTTP header.
 *
 * OkHttp refuses anything above `~` in a header value ("Unexpected char 0x00f3"), so
 * the feeds are keyed by this instead of the raw search text, which may be full of
 * accents, `ñ`, `·` or emoji.
 */
internal fun String.headerSafe(): String {
    var ascii = true
    for (ch in this) {
        if (ch.code !in 0x20..0x7E) {
            ascii = false
            break
        }
    }
    if (ascii) return this
    return buildString(length + 8) {
        for (ch in this@headerSafe) {
            if (ch.code in 0x20..0x7E) {
                append(ch)
            } else {
                append("%u").append(ch.code.toString(16).padStart(4, '0'))
            }
        }
    }
}

private fun String.encodeUrl(): String = java.net.URLEncoder.encode(this, "UTF-8")
