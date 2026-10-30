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

        const val HEADER_VIDEO_ID = "X-Inbox-Video-Id"
        const val HEADER_CLIENT = "X-Inbox-Client"
        const val HEADER_TARGET = "X-Inbox-Target"
        const val HEADER_TARGET_URL = "X-Inbox-Target-Url"

        /** InnerTube search params (base64 protobuf) for the result type. */
        const val PARAMS_VIDEOS = "EgIQAQ=="
        const val PARAMS_CHANNELS = "EgIQAg=="
        const val PARAMS_PLAYLISTS = "EgIQAw=="
        const val PARAMS_CHANNEL_VIDEOS = "EgZ2aWRlb3PyBgQKAjoA"

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
        private const val DIAG_VERSION = "v8 · 14.22"

        /** User agent for the media requests, used by the relay too. */
        const val PLAYBACK_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"

        val WEB = ClientSpec(
            id = "1",
            name = "WEB",
            version = "2.20250312.04.00",
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0",
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
            ANDROID,
            IOS,
            ANDROID_VR,
            TVHTML5,
            TV_EMBEDDED,
            WEB_EMBEDDED,
            MWEB,
            WEB,
        )

        fun playerOrder(preferred: String): List<ClientSpec> {
            val first = PLAYER_CLIENTS.firstOrNull { it.name == preferred } ?: ANDROID
            return listOf(first) + PLAYER_CLIENTS.filter { it !== first }
        }
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

    init {
        // The relay asks for a new signed link whenever googlevideo starts refusing one.
        StreamRelay.resolver = { videoId, itag, clientName -> freshUrl(videoId, itag, clientName) }
    }

    // ============================== Relay ===============================

    /**
     * Asks InnerTube for a fresh googlevideo link for [itag]. Called by the relay when
     * the link it was using runs out of quota (HTTP 403/410).
     */
    fun freshUrl(videoId: String, itag: String, clientName: String): String? {
        val primary = PLAYER_CLIENTS.firstOrNull { it.name == clientName } ?: ANDROID
        // The same itag exists in every client, so a couple of tries is enough.
        val candidates = listOf(primary, IOS, ANDROID).distinctBy { it.name }
        for (spec in candidates) {
            val url = runCatching { urlFor(videoId, spec, itag) }.getOrNull()
            if (!url.isNullOrBlank()) return url
        }
        return null
    }

    private fun urlFor(videoId: String, spec: ClientSpec, itag: String): String? {
        val body = client.newCall(playerRequest(videoId, spec)).execute().use { it.body.string() }
        val json = JSON.parseToJsonElement(body).jsonObject
        if (json.obj("playabilityStatus")?.str("status") != "OK") return null
        val streamingData = json.obj("streamingData") ?: return null
        val formats = streamingData.arr("adaptiveFormats").orEmpty() + streamingData.arr("formats").orEmpty()
        return formats.mapNotNull { it as? JsonObject }
            .firstOrNull { parseInt(it.str("itag"))?.toString() == itag }
            ?.str("url")
    }

    /** Pubic url for the player: the local relay whenever it is available. */
    private fun relayed(
        videoId: String,
        spec: ClientSpec,
        itag: Int?,
        url: String,
        length: Long,
        contentType: String,
    ): String {
        if (itag == null) return url
        val key = "$videoId|$itag|${spec.name}"
        synchronized(relayUrls) {
            relayUrls[key]?.let { return it }
            val relayed = runCatching {
                StreamRelay.register(videoId, itag.toString(), spec.name, url, length, contentType)
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
                        },
                    )
                },
            )
        }
        return post("$API_URL/player?prettyPrint=false", body, apiHeaders(spec))
            .newBuilder()
            .header(HEADER_VIDEO_ID, videoId)
            .header(HEADER_CLIENT, spec.name)
            .build()
    }

    fun oembedRequest(videoId: String): Request {
        val url = "$OEMBED_URL?url=${"https://www.youtube.com/watch?v=$videoId".encodeUrl()}&format=json"
        return Request.Builder().url(url).headers(apiHeaders(WEB)).get().build()
    }

    /** Real watch page, used for details and for the client's WebView. */
    fun watchPageRequest(videoId: String): Request = Request.Builder()
        .url(watchUrl(videoId))
        .header("User-Agent", WEB.userAgent)
        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .header("Accept-Language", "en-US,en;q=0.9")
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
        val parts = metadata?.obj("metadata")
            ?.obj("contentMetadataViewModel")
            ?.arr("metadataRows")
            .orEmpty()
            .flatMap { row ->
                (row as? JsonObject)?.arr("metadataParts").orEmpty()
                    .mapNotNull { (it as? JsonObject)?.obj("text").text()?.takeIf(String::isNotBlank) }
            }
        val author = parts.firstOrNull().orEmpty()
        val info = parts.drop(1).filterNot { it.equals("Playlist", true) }.joinToString(" · ")
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
            return Metadata(
                title = playlist.str("title").orEmpty(),
                author = "",
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

        val order = playerOrder(preferredClient)
        val failures = mutableListOf<String>()
        // With the page route on, the app-side player is only asked for muxed
        // streams: its DASH links are the tokenless ones that answer 403.
        val usePageForDash = includeDash && pagePlayer != null
        var muxed: List<Video> = emptyList()

        var body = response.use { it.body.string() }.orEmpty()
        for ((index, spec) in order.withIndex()) {
            if (index > 0) {
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
                    includeDash = includeDash && !usePageForDash,
                    includeMuxed = includeMuxed || usePageForDash,
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
                if (!usePageForDash) {
                    Log.d(TAG, "Resolved ${playable.size} streams with ${spec.name} (${videos.size} candidates)")
                    return playable
                }
                Log.d(TAG, "Resolved ${playable.size} muxed stream(s) with ${spec.name}")
                muxed = playable
                break
            }
            failures += "${spec.name}: ${videos.size} format(s) rejected (HTTP 4xx, likely blocked)"
        }

        if (!usePageForDash) {
            throw Exception("INbox: no playable stream. ${failures.joinToString(" | ")}")
        }

        // The page route. First choice: the links the page's player requested itself,
        // which already carry `pot` and the descrambled `n` parameter.
        val page = runCatching { pagePlayer.invoke(videoId) }.getOrNull()
        val captured = page?.let { capturedDash(it, preferredQuality) }.orEmpty()
        if (captured.isNotEmpty()) {
            Log.d(TAG, "Resolved ${captured.size} DASH stream(s) from the page player")
            return captured + muxed
        }
        // Second choice: the page's player response.
        val pageResponse = page?.response
        if (pageResponse != null) {
            val dash = runCatching {
                buildVideos(
                    json = JSON.parseToJsonElement(pageResponse).jsonObject,
                    spec = WEB,
                    videoId = videoId,
                    includeDash = true,
                    includeMuxed = false,
                    fetchSubtitles = fetchSubtitles,
                    preferredAudioLang = preferredAudioLang,
                    preferredSubtitleLang = preferredSubtitleLang,
                    preferredQuality = preferredQuality,
                )
            }.getOrElse {
                failures += "page: ${it.message}"
                emptyList()
            }
            if (dash.isNotEmpty()) {
                Log.d(TAG, "Resolved ${dash.size} DASH stream(s) from the watch page")
                return dash + muxed
            }
            if (failures.none { it.startsWith("page:") }) failures += "page: no DASH formats"
        } else {
            failures += "page: no player response"
        }

        // Page route failed: fall back to the tokenless DASH links anyway.
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
        if (nativeDash.isNotEmpty()) return nativeDash + muxed
        if (muxed.isNotEmpty()) return muxed
        throw Exception("INbox: no playable stream. ${failures.joinToString(" | ")}")
    }

    /** Builds a DASH entry from the links the page player used. */
    private fun capturedDash(streams: PageStreams, preferredQuality: String): List<Video> {
        if (!streams.hasPlayableDash) return emptyList()
        val video = streams.videos
            .sortedByDescending { it.height }
            .firstOrNull { passesQuality(it.height, preferredQuality) }
            ?: return emptyList()
        val audio = streams.audios.first()
        return listOf(
            Video(
                video.url,
                "DASH ${video.label} • ${audio.label}",
                video.url,
                playbackHeaders,
                audioTracks = listOf(Track(audio.url, audio.label)),
            ),
        )
    }

    /** Compact report for the in-app DASH test. */
    fun diagnose(
        videoId: String,
        includeDash: Boolean,
        includeMuxed: Boolean,
        preferredAudioLang: String,
        preferredQuality: String,
        preferredClient: String,
        pagePlayer: ((String) -> PageStreams?)?,
    ): String = buildString {
        appendLine("ID: $videoId")
        appendLine("Prueba $DIAG_VERSION")
        val order = playerOrder(preferredClient)
        appendLine("Orden: ${order.joinToString(", ") { it.name }}")

        val videos = runCatching {
            val response = client.newCall(playerRequest(videoId, order.first())).execute()
            resolvePlayer(
                response = response,
                includeDash = includeDash,
                includeMuxed = includeMuxed,
                fetchSubtitles = false,
                preferredAudioLang = preferredAudioLang,
                preferredSubtitleLang = "",
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
            appendLine("${index + 1}. ${video.quality} ($kind)")
        }

        // What matters now: reading through the relay exactly like the player does,
        // with one open ended range, well past the point where the link used to die.
        val dash = videos.firstOrNull { it.audioTracks.isNotEmpty() && it.videoUrl != null }
        if (dash != null) {
            appendLine("Por el relé, como el reproductor:")
            appendLine("  video: ${relayRead(dash.videoUrl!!, 6 * 1024 * 1024)}")
            dash.audioTracks.firstOrNull()?.let {
                appendLine("  audio: ${relayRead(it.url, 1024 * 1024)}")
            }
        } else {
            appendLine("Sin DASH: no hay nada que releer")
        }

        val muxed = videos.firstOrNull { it.audioTracks.isEmpty() && it.videoUrl != null }
        if (muxed != null) {
            appendLine("Muxed directo: ${probe(muxed.videoUrl!!, withHeaders = true, range = "bytes=0-1")}")
        }

        appendLine("Por cliente (estado y muxed):")
        for (spec in order) {
            val line = runCatching {
                val body = client.newCall(playerRequest(videoId, spec)).execute()
                    .use { it.body.string() }
                val json = JSON.parseToJsonElement(body).jsonObject
                val state = json.obj("playabilityStatus")?.str("status") ?: "?"
                if (state != "OK") return@runCatching state
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
     * Reads [maxBytes] from [url] with a single open ended range, which is what the
     * player sends when it starts a stream.
     */
    private fun relayRead(url: String, maxBytes: Long): String = try {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", PLAYBACK_UA)
            .header("Range", "bytes=0-")
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

            for (rep in muxed) {
                if (!passesQuality(rep.height, preferredQuality)) continue
                videos += Video(rep.url, "Muxed ${rep.label}", rep.url, headers, subtitleTracks = subtitles)
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
                val lang = track?.str("languageCode").orEmpty().lowercase(Locale.US)
                val display = track?.str("displayName")?.takeIf { it.isNotBlank() }
                    ?: format.str("audioQuality")?.takeIf { it.isNotBlank() }
                    ?: if (lang.isNotBlank()) lang else "Audio"
                val bitrate = parseInt(format.str("bitrate")) ?: 0
                AudioRep(
                    url = url,
                    bitrate = bitrate,
                    lang = lang,
                    display = display,
                    mp4 = mime.startsWith("audio/mp4"),
                    itag = parseInt(format.str("itag")),
                    length = format.str("contentLength")?.toLongOrNull() ?: 0L,
                )
            }.sortedWith(compareByDescending<AudioRep> { it.bitrate })
                .distinctBy { it.lang to it.bitrate }
            val filteredAudios = filterAudios(audios, preferredAudioLang)

            if (filteredAudios.isNotEmpty()) {
                val videoReps = adaptive.mapNotNull { element ->
                    val format = element as? JsonObject ?: return@mapNotNull null
                    val url = format.str("url") ?: return@mapNotNull null
                    val mime = format.str("mimeType").orEmpty()
                    if (!mime.startsWith("video/mp4")) return@mapNotNull null
                    val codec = mime.substringAfter("codecs=\"", "").substringBefore("\"")
                    if (codec.isNotBlank() && !codec.startsWith("avc1")) return@mapNotNull null
                    val height = parseInt(format.str("height")) ?: return@mapNotNull null
                    if (height > 1080) return@mapNotNull null
                    VideoRep(
                        url = url,
                        height = height,
                        bitrate = parseInt(format.str("bitrate")) ?: 0,
                        itag = parseInt(format.str("itag")),
                        length = format.str("contentLength")?.toLongOrNull() ?: 0L,
                    )
                }.distinctBy { it.height }.sortedByDescending { it.height }

                // The relay turns the open ended ranges the player asks for into
                // bounded ones, which is the only way these links keep serving.
                val audiosRelayed = filteredAudios.associateWith { audio ->
                    relayed(
                        videoId = videoId,
                        spec = spec,
                        itag = audio.itag,
                        url = audio.url,
                        length = audio.length,
                        contentType = if (audio.mp4) "audio/mp4" else "audio/webm",
                    )
                }

                for (rep in videoReps) {
                    if (!passesQuality(rep.height, preferredQuality)) continue
                    val videoUrl = relayed(
                        videoId = videoId,
                        spec = spec,
                        itag = rep.itag,
                        url = rep.url,
                        length = rep.length,
                        contentType = "video/mp4",
                    )
                    val headers = if (StreamRelay.isRelay(videoUrl)) null else playbackHeaders
                    for (audio in filteredAudios) {
                        val audioUrl = audiosRelayed[audio] ?: audio.url
                        videos += Video(
                            videoUrl,
                            "DASH ${rep.height}p • ${audio.display}",
                            videoUrl,
                            headers,
                            audioTracks = listOf(Track(audioUrl, audio.display)),
                            subtitleTracks = subtitles,
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

    private fun extractSubtitles(json: JsonObject): List<Track> {
        val tracks = json.obj("captions")
            ?.obj("playerCaptionsTracklistRenderer")
            ?.arr("captionTracks") ?: return emptyList()
        return tracks.mapNotNull { element ->
            val track = element as? JsonObject ?: return@mapNotNull null
            val baseUrl = track.str("baseUrl") ?: return@mapNotNull null
            val lang = track.str("languageCode").orEmpty()
            val kind = track.str("kind").orEmpty()
            val name = track.obj("name").text()
                ?: if (lang.isNotBlank()) lang else "Subtitles"
            val label = if (kind.contains("asr", ignoreCase = true)) "$name (auto)" else name
            val url = if (baseUrl.contains("&fmt=") || baseUrl.contains("?fmt=")) baseUrl else "$baseUrl&fmt=vtt"
            Track(url, label)
        }
    }

    private fun filterSubtitles(tracks: List<Track>, preferredSubtitleLang: String): List<Track> {
        if (tracks.isEmpty()) return emptyList()
        if (preferredSubtitleLang == "all") return tracks.distinctBy { it.url }
        if (preferredSubtitleLang == "auto") return tracks.take(1)
        val filtered = tracks.filter { it.lang.lowercase(Locale.US).startsWith(preferredSubtitleLang) }
        return (if (filtered.isNotEmpty()) filtered else tracks.take(1)).distinctBy { it.url }
    }

    private fun passesQuality(height: Int, preferredQuality: String): Boolean {
        if (height <= 0) return true
        return when (preferredQuality) {
            "1080" -> height <= 1080
            "720" -> height <= 720
            "480" -> height <= 480
            "360" -> height <= 360
            else -> true
        }
    }

    private fun filterAudios(audios: List<AudioRep>, preferredAudioLang: String): List<AudioRep> {
        val ordered = audios.sortedWith(
            compareByDescending<AudioRep> { it.mp4 }.thenByDescending { it.bitrate },
        )
        if (preferredAudioLang == "all") return ordered
        val filtered = ordered.filter { audio ->
            when (preferredAudioLang) {
                "es" -> audio.lang.startsWith("es")
                "en" -> audio.lang.startsWith("en")
                "ja" -> audio.lang.startsWith("ja")
                else -> true
            }
        }
        return if (filtered.isNotEmpty()) filtered else ordered
    }

    private fun context(spec: ClientSpec): JsonObject = buildJsonObject {
        put(
            "client",
            buildJsonObject {
                put("clientName", spec.name)
                put("clientVersion", spec.version)
                put("hl", "en")
                put("gl", "US")
                spec.extra.forEach { (key, value) -> put(key, value) }
            },
        )
        spec.embedUrl?.let { url ->
            put("thirdParty", buildJsonObject { put("embedUrl", url) })
        }
        put("user", buildJsonObject { put("lockedSafetyMode", false) })
        put("request", buildJsonObject { put("useSsl", true) })
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
)

private data class VideoRep(val url: String, val height: Int, val bitrate: Int, val itag: Int?, val length: Long)
private data class MuxedRep(val height: Int, val bitrate: Int, val label: String, val url: String)
private data class AudioRep(
    val url: String,
    val bitrate: Int,
    val lang: String,
    val display: String,
    val mp4: Boolean,
    val itag: Int?,
    val length: Long,
)

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

private fun String.encodeUrl(): String = java.net.URLEncoder.encode(this, "UTF-8")
