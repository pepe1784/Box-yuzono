package eu.kanade.tachiyomi.animeextension.all.box

import android.text.InputType
import android.util.Base64
import android.util.Log
import aniyomi.lib.playlistutils.PlaylistUtils
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.delegate
import keiyoushi.utils.getEditTextPreference
import keiyoushi.utils.getListPreference
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.getSwitchPreference
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import java.util.concurrent.TimeUnit

class Box : AnimeHttpSource(), ConfigurableAnimeSource {

    override val name = "box"
    override val lang = "all"
    override val id: Long = 9134775860771942682L
    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    override var baseUrl: String by preferences.delegate(PREF_INSTANCE_KEY, DEFAULT_INSTANCE)

    private val useHtmlCatalog: Boolean
        get() = preferences.getBoolean(PREF_HTML_CATALOG_KEY, PREF_HTML_CATALOG_DEFAULT)

    override val client: OkHttpClient by lazy {
        network.client.newBuilder()
            .addInterceptor(CaptchaProxyInterceptor())
            .addInterceptor(GoAwayInterceptor())
            .addInterceptor(AnubisInterceptor())
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override fun headersBuilder() = super.headersBuilder()
        .add("Accept", "application/json")
        .add("Referer", "$baseUrl/")
        .add("User-Agent", USER_AGENT)

    private val watchHeaders: Headers
        get() = headersBuilder()
            .set(
                "Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            )
            .add("Accept-Language", "en-US,en;q=0.5")
            .add("Upgrade-Insecure-Requests", "1")
            .add("Sec-Fetch-Dest", "document")
            .add("Sec-Fetch-Mode", "navigate")
            .add("Sec-Fetch-Site", "same-origin")
            .build()

    private val htmlHeaders: Headers
        get() = Headers.Builder()
            .add("User-Agent", USER_AGENT)
            .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .add("Accept-Language", "en-US,en;q=0.5")
            .add("Referer", "$baseUrl/")
            .build()

    private fun dashHeaders(videoId: String): Headers = headersBuilder()
        .set("Accept", "application/dash+xml")
        .set("Referer", "$baseUrl/watch?v=$videoId")
        .build()

    private fun hlsHeaders(videoId: String): Headers = headersBuilder()
        .set("Accept", "application/vnd.apple.mpegurl,*/*")
        .set("Referer", "$baseUrl/watch?v=$videoId")
        .build()

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request =
        if (useHtmlCatalog) {
            GET("$baseUrl/feed/trending", htmlHeaders)
        } else {
            GET("$baseUrl/api/v1/trending?$FIELDS", headers)
        }

    override fun popularAnimeParse(response: Response): AnimesPage = parseSearchResults(response)

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request =
        if (useHtmlCatalog) {
            GET("$baseUrl/feed/trending", htmlHeaders)
        } else {
            GET("$baseUrl/api/v1/trending?$FIELDS", headers)
        }

    override fun latestUpdatesParse(response: Response): AnimesPage = parseSearchResults(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val trimmed = query.trim()

        // Allow pasting a YouTube/Invidious URL directly into Aniyomi search.
        val directVideoId = extractVideoId(trimmed)
        if (directVideoId != null && page == 1) {
            return GET("$baseUrl/watch?v=$directVideoId", htmlHeaders)
        }

        if (useHtmlCatalog) {
            val urlBuilder = "$baseUrl/search".toHttpUrl().newBuilder()
                .addQueryParameter("q", trimmed)
                .addQueryParameter("page", page.toString())
            return GET(urlBuilder.build().toString(), htmlHeaders)
        }

        val urlBuilder = "$baseUrl/api/v1/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", trimmed)
            .addQueryParameter("page", page.toString())

        val typeFilter = filters.find { it is TypeFilter } as? TypeFilter
        val sortFilter = filters.find { it is SortFilter } as? SortFilter
        val dateFilter = filters.find { it is DateFilter } as? DateFilter

        urlBuilder.addQueryParameter("type", typeFilter?.toValue() ?: "video")
        sortFilter?.toValue()?.let { urlBuilder.addQueryParameter("sort", it) }
        dateFilter?.toValue()?.let { urlBuilder.addQueryParameter("date", it) }

        return GET(urlBuilder.build().toString(), headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val requestUrl = response.request.url
        val isDirectWatch = requestUrl.encodedPath.contains("/watch") &&
            extractVideoId(requestUrl.toString()) != null
        if (isDirectWatch) {
            return try {
                val anime = animeDetailsParse(response)
                AnimesPage(listOf(anime), false)
            } catch (e: Exception) {
                AnimesPage(emptyList(), false)
            }
        }
        return parseSearchResults(response)
    }

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request {
        val channelId = anime.url.extractChannelId()
        if (channelId != null) {
            return GET("$baseUrl/api/v1/channels/$channelId", headers)
        }
        val id = extractVideoId(anime.url) ?: anime.url
        return GET("$baseUrl/watch?v=$id", watchHeaders)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val requestUrl = response.request.url
        if (requestUrl.encodedPath.contains("/api/v1/channels/")) {
            val channel = json.parseToJsonElement(response.body.string()).jsonObject
            val author = channel["author"]?.jsonPrimitive?.content ?: "Channel"
            val authorId = channel["authorId"]?.jsonPrimitive?.content
                ?: requestUrl.pathSegments.lastOrNull { it.isNotBlank() }
                ?: ""
            val thumbnail = channel["authorThumbnails"]?.jsonArray
                ?.lastOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.content
                ?: ""
            return SAnime.create().apply {
                title = "📺 $author"
                url = "channel:$authorId"
                thumbnail_url = thumbnail
                this.author = author
                description = buildString {
                    channel["description"]?.jsonPrimitive?.content?.let {
                        appendLine(it)
                        appendLine()
                    }
                    channel["subCount"]?.jsonPrimitive?.content?.let {
                        appendLine("Subscribers: $it")
                    }
                    channel["videoCount"]?.jsonPrimitive?.content?.let {
                        appendLine("Videos: $it")
                    }
                }.trim()
                status = SAnime.COMPLETED
            }
        }

        val doc = response.asJsoup()
        val host = response.host
        val watchData = doc.selectFirst("script#video_data")?.data()?.let {
            json.decodeFromString<BoxWatchData>(it)
        }

        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: watchData?.title ?: "Unknown"
        val description = doc.selectFirst("meta[property=og:description]")?.attr("content")
        val author = doc.selectFirst("a[href^=/channel/]")?.text()
            ?: doc.selectFirst("meta[name=author]")?.attr("content")
        val thumbnail = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?.let { fixThumbnail(it, host) }

        return SAnime.create().apply {
            this.title = title
            url = response.request.url.toString()
            this.thumbnail_url = thumbnail
            this.author = author
            this.description = buildString {
                description?.let {
                    appendLine(it)
                    appendLine()
                }
                author?.let { appendLine("Author: $it") }
                watchData?.lengthSeconds?.let { appendLine("Duration: ${it}s") }
            }.trim()
            status = SAnime.COMPLETED
        }
    }

    // ============================== Episodes ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val channelId = anime.url.extractChannelId()
        if (channelId != null) {
            val videos = fetchChannelVideos(channelId)
            return videos.mapIndexedNotNull { videoIndex, element ->
                val video = element.jsonObject
                val videoId = video["videoId"]?.jsonPrimitive?.content ?: return@mapIndexedNotNull null
                val episodeTitle = video["title"]?.jsonPrimitive?.content ?: videoId
                val hasSubtitles = video["hasCaptions"]?.jsonPrimitive?.booleanOrNull ?: false
                SEpisode.create().also { episode ->
                    episode.url = "video:$videoId"
                    episode.name = "$episodeTitle${if (hasSubtitles) " [CC]" else ""}"
                    episode.episode_number = (videos.size - videoIndex).toFloat()
                    video["published"]?.jsonPrimitive?.content?.toLongOrNull()?.let {
                        episode.date_upload = it * 1000L
                    }
                }
            }
        }

        val id = extractVideoId(anime.url) ?: anime.url
        return listOf(
            SEpisode.create().also { episode ->
                episode.url = id
                episode.name = "Video"
                episode.episode_number = 1F
            },
        )
    }

    private fun fetchChannelVideos(channelId: String): List<JsonElement> {
        val allVideos = mutableListOf<JsonElement>()
        var continuation: String? = null
        var page = 0
        do {
            val urlBuilder = "$baseUrl/api/v1/channels/$channelId/videos"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("sort_by", "newest")
            continuation?.let { urlBuilder.addQueryParameter("continuation", it) }
            val response = client.newCall(GET(urlBuilder.build().toString(), headers)).execute()
            val body = response.use { it.body?.string() } ?: break
            val obj = json.parseToJsonElement(body).jsonObject
            val videos = obj["videos"]?.jsonArray ?: break
            allVideos.addAll(videos)
            continuation = obj["continuation"]?.jsonPrimitive?.content
            page++
        } while (continuation != null && page < 10 && allVideos.size < 500)
        return allVideos
    }

    override fun episodeListParse(response: Response): List<SEpisode> =
        throw UnsupportedOperationException()

    // ============================ Video Links =============================

    override fun videoListRequest(episode: SEpisode): Request {
        val id = extractVideoId(episode.url) ?: episode.url
        return GET("$baseUrl/watch?v=$id", watchHeaders)
    }

    /**
     * Fetch available caption/subtitle tracks from Invidious.
     * If the captions API is blocked by the instance, return an empty list.
     */
    private fun fetchCaptions(videoId: String, host: String): List<Track> {
        return try {
            val url = "$host/api/v1/captions/$videoId"
            val resp = client.newCall(GET(url, headers)).execute()
            val body = resp.use { it.body?.string() } ?: return emptyList()
            if (resp.code != 200 || body.isBlank()) return emptyList()
            val parsed = json.decodeFromString<BoxCaptionsResponse>(body)
            parsed.captions.map { caption ->
                val absoluteUrl = when {
                    caption.url.startsWith("http://") || caption.url.startsWith("https://") -> caption.url
                    caption.url.startsWith("/") -> "$host${caption.url}"
                    else -> "$host/${caption.url}"
                }
                Track(absoluteUrl, caption.label)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch captions for $videoId", e)
            emptyList()
        }
    }

    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()
        val host = response.host
        val videoId = extractVideoId(response.request.url.toString()) ?: return emptyList()
        val check = extractCheck(doc) ?: ""
        val videos = mutableListOf<Video>()
        val seenUrls = mutableSetOf<String>()
        val subtitleTracks = fetchCaptions(videoId, host)

        // DASH manifest: parse it directly and expose each video Representation
        // as a Video with its matching audio track(s). Other Yuzono extensions
        // (e.g. VVVVID, AllAnime) do exactly this instead of proxying the MPD.
        val dashSrc = doc.selectFirst("video#player source[type*=dash]")?.attr("src") ?: ""
        val dashUrlLocal = when {
            dashSrc.isNotBlank() -> buildDashManifestUrl(dashSrc, host)
            check.isNotBlank() -> "$host/api/manifest/dash/id/$videoId?local=true&unique_res=1&check=$check"
            else -> ""
        }
        val dashUrlRemote = if (check.isNotBlank()) {
            "$host/api/manifest/dash/id/$videoId?unique_res=1&check=$check"
        } else {
            ""
        }
        Log.d(TAG, "dashSrc=$dashSrc dashUrlLocal=$dashUrlLocal dashUrlRemote=$dashUrlRemote")

        fun addDashVideosFrom(url: String, labelPrefix: String = "DASH"): Boolean {
            if (url.isBlank()) return false
            return try {
                val resp = client.newCall(GET(url, dashHeaders(videoId))).execute()
                val body = resp.use { it.body?.string() } ?: ""
                if (resp.code == 200 && body.contains("<MPD", ignoreCase = true)) {
                    val parsed = parseDashManifestBody(body, url, subtitleTracks)
                    if (parsed.isNotEmpty()) {
                        parsed.forEach { video ->
                            val videoUrl = video.videoUrl ?: return@forEach
                            if (seenUrls.add(videoUrl)) {
                                Log.d(TAG, "Adding DASH source: ${video.quality}")
                                videos += video
                            }
                        }
                        true
                    } else {
                        false
                    }
                } else {
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch DASH manifest $labelPrefix", e)
                false
            }
        }

        if (dashUrlLocal.isNotBlank() || dashUrlRemote.isNotBlank()) {
            // Prefer the remote (googlevideo) manifest so ExoPlayer talks directly to
            // YouTube for segments and does not need the Anubis cookie.
            val ok = addDashVideosFrom(dashUrlRemote, "remote")
            if (!ok && dashUrlLocal.isNotBlank()) {
                addDashVideosFrom(dashUrlLocal, "local")
            }
        } else {
            videos += Video("", "DASH DEBUG: empty dashUrl", "", headers)
        }

        // HLS fallback: Invidious also exposes an HLS master playlist.
        if (check.isNotBlank()) {
            val hlsUrlLocal = "$host/api/manifest/hls_playlist/id/$videoId?local=true&check=$check"
            val hlsUrlRemote = "$host/api/manifest/hls_playlist/id/$videoId?check=$check"

            fun processHls(url: String, labelPrefix: String): Boolean {
                return try {
                    val playlistResponse = client.newCall(GET(url, hlsHeaders(videoId)))
                        .execute()
                    val playlistBody = playlistResponse.use { it.body?.string() } ?: ""

                    val isValidHls = playlistBody.trimStart().startsWith("#EXTM3U", ignoreCase = true)
                    if (!isValidHls) return false

                    if ("#EXT-X-STREAM-INF" !in playlistBody) {
                        // Single-variant media playlist: pass it directly to ExoPlayer.
                        if (seenUrls.add(url)) {
                            videos += Video(url, "HLS master ($labelPrefix)", url, headers = hlsHeaders(videoId), subtitleTracks = subtitleTracks)
                        }
                        return true
                    }

                    val playlistUtils = PlaylistUtils(client, hlsHeaders(videoId))
                    val hlsVideos = playlistUtils.extractFromHls(
                        url,
                        masterHeaders = hlsHeaders(videoId),
                        videoHeaders = hlsHeaders(videoId),
                        videoNameGen = { quality -> "HLS $quality" },
                    )
                    if (hlsVideos.isEmpty()) {
                        videos += Video("", "HLS DEBUG: $labelPrefix extractFromHls empty", "", headers)
                        if (seenUrls.add(url)) {
                            videos += Video(url, "HLS master ($labelPrefix)", url, headers = hlsHeaders(videoId), subtitleTracks = subtitleTracks)
                        }
                        return true
                    }
                    hlsVideos.forEach { video ->
                        val videoUrl = video.videoUrl ?: return@forEach
                        if (seenUrls.add(videoUrl)) {
                            Log.d(TAG, "Adding HLS source: ${video.quality}")
                            videos += video
                        }
                    }
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse HLS playlist $labelPrefix", e)
                    videos += Video("", "HLS DEBUG: $labelPrefix ${e.javaClass.simpleName}: ${e.message}", "", headers)
                    false
                }
            }

            val ok = processHls(hlsUrlLocal, "local")
            if (!ok) processHls(hlsUrlRemote, "remote")
        }

        // Progressive streams exposed by the player page (HD720, medium, small).
        // Leave the Invidious redirect URL so Aniyomi/ffmpeg follows it with source headers.
        doc.select("video#player source").forEach { source ->
            if (source.hasAttr("hidequalityoption")) return@forEach
            if (source.attr("type").contains("dash", ignoreCase = true)) return@forEach
            val src = source.attr("src").takeIf { it.isNotBlank() } ?: return@forEach
            val absolute = if (src.startsWith("http")) src else "$host$src"
            val label = source.attr("label").ifBlank { "Video" }
            if (!seenUrls.add(absolute)) return@forEach
            Log.d(TAG, "Adding progressive source: $label")
            videos += Video(absolute, label, absolute, headers, subtitleTracks = subtitleTracks)
        }

        // Always probe progressive itags so downloads have a direct video URL.
        // Keep the Invidious /latest_version URL; let the downloader follow the redirect.
        if (check.isNotBlank()) {
            ITAG_LABELS.forEach { (itag, label) ->
                val url = "$host/latest_version?id=$videoId&itag=$itag&check=$check"
                if (!seenUrls.add(url)) return@forEach
                val head = try {
                    client.newCall(GET(url, watchHeaders)).execute().use { it.code }
                } catch (_: Exception) {
                    -1
                }
                if (head !in 200..399) return@forEach
                Log.d(TAG, "Adding progressive itag $itag -> $label")
                videos += Video(url, label, url, headers, subtitleTracks = subtitleTracks)
            }
        }

        return videos
    }

    private fun buildDashManifestUrl(src: String, host: String): String {
        return if (src.startsWith("http")) src else "$host$src"
    }

    private fun parseDashManifestBody(manifest: String, manifestUrl: String, subtitleTracks: List<Track>): List<Video> {
        Log.d(TAG, "parseDashManifestBody: len=${manifest.length}")

        val audioUrls = mutableListOf<String>()
        val videoReps = mutableListOf<DashRep>()

        ADAPTATION_SET_REGEX.findAll(manifest).forEach { asMatch ->
            val asAttrs = parseAttributes(asMatch.groupValues[1])
            val contentType = asAttrs["contentType"]?.lowercase()
                ?: asAttrs["mimeType"]?.lowercase()
                ?: ""
            val asBlock = asMatch.groupValues[2]

            if (!asBlock.contains("<SegmentBase", ignoreCase = true)) return@forEach

            val asBaseUrl = BASE_URL_REGEX.find(asBlock)?.groupValues?.get(1)
                ?.replace("&amp;", "&")
                ?.let { resolveManifestUrl(it, manifestUrl) }

            REPRESENTATION_REGEX.findAll(asBlock).forEach { repMatch ->
                val repAttrs = parseAttributes(repMatch.groupValues[1])
                val repBlock = repMatch.groupValues[2]

                if (!repBlock.contains("<SegmentBase", ignoreCase = true)) return@forEach

                val baseUrl = BASE_URL_REGEX.find(repBlock)?.groupValues?.get(1)
                    ?.replace("&amp;", "&")
                    ?.let { resolveManifestUrl(it, manifestUrl) }
                    ?: asBaseUrl
                    ?: return@forEach

                when {
                    contentType.contains("audio") -> audioUrls += baseUrl
                    contentType.contains("video") -> {
                        videoReps += DashRep(
                            url = baseUrl,
                            height = repAttrs["height"]?.toIntOrNull() ?: 0,
                            width = repAttrs["width"]?.toIntOrNull() ?: 0,
                            codecs = repAttrs["codecs"] ?: "",
                            bandwidth = repAttrs["bandwidth"]?.toLongOrNull() ?: 0,
                        )
                    }
                }
            }
        }

        Log.d(TAG, "DASH reps: audio=${audioUrls.size}, video=${videoReps.size}")
        if (videoReps.isEmpty()) return emptyList()

        val audioTracks = audioUrls.map { Track(it, "Audio") }

        val h264 = videoReps.filter { it.codecs.startsWith("avc1") }
        val candidates = if (h264.isNotEmpty()) h264 else videoReps
        val capped = candidates.filter { it.height <= 1080 }
        val ordered = (if (capped.isNotEmpty()) capped else candidates)
            .sortedByDescending { it.height }

        return ordered.map { rep ->
            val label = "DASH ${rep.height}p"
            // Use source headers so Aniyomi/ffmpeg sends Referer/User-Agent when downloading.
            Video(rep.url, label, rep.url, headers, audioTracks = audioTracks, subtitleTracks = subtitleTracks)
        }
    }

    private data class DashRep(
        val url: String,
        val height: Int,
        val width: Int,
        val codecs: String,
        val bandwidth: Long,
    )

    private fun resolveManifestUrl(raw: String, manifestUrl: String): String {
        return when {
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            raw.startsWith("/") -> {
                val url = manifestUrl.toHttpUrl()
                "${url.scheme}://${url.host}$raw"
            }
            else -> {
                val base = manifestUrl.substringBeforeLast("/")
                "$base/$raw"
            }
        }
    }

    private fun extractFirstDashBaseUrl(manifest: String, manifestUrl: String): String {
        ADAPTATION_SET_REGEX.findAll(manifest).forEach { asMatch ->
            val asAttrs = parseAttributes(asMatch.groupValues[1])
            val contentType = asAttrs["contentType"]?.lowercase()
                ?: asAttrs["mimeType"]?.lowercase()
                ?: ""
            if (!contentType.contains("video")) return@forEach
            val baseUrl = BASE_URL_REGEX.find(asMatch.groupValues[2])?.groupValues?.get(1)
                ?.replace("&amp;", "&")
                ?.let { resolveManifestUrl(it, manifestUrl) }
                ?: return@forEach
            return baseUrl.take(150)
        }
        return ""
    }

    private fun parseAttributes(tag: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val regex = Regex("""(\w+)="([^"]*)"""")
        regex.findAll(tag).forEach { match ->
            map[match.groupValues[1]] = match.groupValues[2]
        }
        return map
    }

    private fun probeItag(host: String, videoId: String, check: String, itag: String): String? {
        val url = "$host/latest_version?id=$videoId&itag=$itag&check=$check"
        return try {
            resolveVideoUrl(url).takeIf {
                it.contains("googlevideo") || it.contains("videoplayback")
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveVideoUrl(url: String): String {
        return client.newCall(GET(url, watchHeaders)).execute().use {
            it.request.url.toString()
        }
    }

    private fun extractCheck(doc: Document): String? {
        return doc.select("video#player source").mapNotNull { source ->
            CHECK_REGEX.find(source.attr("src"))?.groupValues?.getOrNull(1)
        }.firstOrNull()
    }

    override fun List<Video>.sort(): List<Video> {
        val pref = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)
            ?: PREF_QUALITY_DEFAULT
        return sortedWith(
            compareByDescending<Video> { it.quality.contains(pref, ignoreCase = true) }
                .thenByDescending { extractHeight(it.quality) },
        )
    }

    private fun extractHeight(quality: String): Int {
        return Regex("""(\d+)p""").find(quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    // ============================== Preferences ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val instancePref = screen.getEditTextPreference(
            key = PREF_INSTANCE_KEY,
            default = DEFAULT_INSTANCE,
            title = "Invidious instance",
            summary = "Base URL of the Invidious instance",
            getSummary = { "Current: ${it.trim().trimEnd('/')}" },
            inputType = InputType.TYPE_TEXT_VARIATION_URI,
            validate = { value ->
                val trimmed = value.trim().trimEnd('/')
                trimmed.startsWith("http://") || trimmed.startsWith("https://")
            },
            validationMessage = { "URL must start with http:// or https://" },
            onComplete = { value ->
                preferences.edit().putString(PREF_INSTANCE_KEY, value.trim().trimEnd('/')).apply()
            },
        )

        val qualityPref = screen.getListPreference(
            key = PREF_QUALITY_KEY,
            default = PREF_QUALITY_DEFAULT,
            title = "Preferred quality",
            summary = "%s",
            entries = PREF_QUALITY_ENTRIES.toList(),
            entryValues = PREF_QUALITY_VALUES.toList(),
            onComplete = { value ->
                preferences.edit().putString(PREF_QUALITY_KEY, value).apply()
            },
        )

        val htmlCatalogPref = screen.getSwitchPreference(
            key = PREF_HTML_CATALOG_KEY,
            default = PREF_HTML_CATALOG_DEFAULT,
            title = "Usar catálogo HTML",
            summary = "Fuerza tendencias/búsqueda por HTML. Útil para instancias que bloquean la API (por ejemplo choco).",
            onComplete = { value ->
                preferences.edit().putBoolean(PREF_HTML_CATALOG_KEY, value).apply()
            },
        )

        screen.addPreference(instancePref)
        screen.addPreference(qualityPref)
        screen.addPreference(htmlCatalogPref)
    }

    // ============================== Helpers ==============================

    private fun parseSearchResults(response: Response): AnimesPage {
        val host = response.host
        return if (useHtmlCatalog) {
            parseSearchResultsHtml(response.asJsoup(), host)
        } else {
            val body = response.body.string()
            try {
                parseSearchResultsJson(body, host)
            } catch (e: Exception) {
                Log.d(TAG, "API search/trending failed, falling back to HTML", e)
                val fallbackUrl = buildFallbackSearchUrl(response.request.url)
                val htmlResponse = client.newCall(GET(fallbackUrl, htmlHeaders)).execute()
                htmlResponse.use { parseSearchResultsHtml(it.asJsoup(), host) }
            }
        }
    }

    private fun parseSearchResultsJson(body: String, host: String): AnimesPage {
        val items = json.parseToJsonElement(body).jsonArray
        val entries = items.mapNotNull { element ->
            val obj = element.jsonObject
            when (obj["type"]?.jsonPrimitive?.content) {
                "channel" -> obj.toChannelSAnime(host)
                "video" -> obj.toVideoSAnime(host)
                else -> null
            }
        }
        return AnimesPage(entries, entries.isNotEmpty())
    }

    private fun parseSearchResultsHtml(doc: Document, host: String): AnimesPage {
        val entries = doc.select("div.pure-u-1.pure-u-md-1-4").mapNotNull { card ->
            val link = card.selectFirst("div.thumbnail > a[href^=/watch]")
                ?: return@mapNotNull null
            val href = link.attr("href")
            val videoId = extractVideoId(href) ?: return@mapNotNull null
            val title = card.selectFirst("div.video-card-row p")?.text()?.trim() ?: videoId
            val thumbnail = link.selectFirst("img.thumbnail")?.attr("src")
                ?.let { fixThumbnail(it, host) } ?: ""
            val author = card.selectFirst("p.channel-name")?.text()?.trim()
            SAnime.create().apply {
                this.title = title
                url = "$host/watch?v=$videoId"
                thumbnail_url = thumbnail
                this.author = author
                description = author?.let { "Author: $it" } ?: ""
                status = SAnime.COMPLETED
            }
        }
        return AnimesPage(entries, entries.isNotEmpty())
    }

    private fun buildFallbackSearchUrl(url: HttpUrl): String {
        val path = url.encodedPath
        return when {
            path.contains("/api/v1/trending") -> url.newBuilder()
                .encodedPath("/feed/trending")
                .encodedQuery(null)
                .build()
                .toString()
            path.contains("/api/v1/search") -> {
                val q = url.queryParameter("q") ?: ""
                val page = url.queryParameter("page") ?: "1"
                url.newBuilder()
                    .encodedPath("/search")
                    .setQueryParameter("q", q)
                    .setQueryParameter("page", page)
                    .build()
                    .toString()
            }
            else -> url.toString()
        }
    }

    private fun JsonObject.toChannelSAnime(host: String): SAnime? {
        val authorId = this["authorId"]?.jsonPrimitive?.content ?: return null
        val author = this["author"]?.jsonPrimitive?.content ?: authorId
        val thumbnail = this["authorThumbnails"]?.jsonArray
            ?.lastOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.content
            ?: ""
        return SAnime.create().apply {
            title = "📺 $author"
            url = "channel:$authorId"
            thumbnail_url = thumbnail
            this.author = author
            description = buildString {
                this@toChannelSAnime["description"]?.jsonPrimitive?.content?.let {
                    appendLine(it)
                    appendLine()
                }
                this@toChannelSAnime["subCount"]?.jsonPrimitive?.content?.let {
                    appendLine("Subscribers: $it")
                }
                this@toChannelSAnime["videoCount"]?.jsonPrimitive?.content?.let {
                    appendLine("Videos: $it")
                }
            }.trim()
            status = SAnime.COMPLETED
        }
    }

    private fun JsonObject.toVideoSAnime(host: String): SAnime? {
        val videoId = this["videoId"]?.jsonPrimitive?.content ?: return null
        val title = this["title"]?.jsonPrimitive?.content ?: videoId
        val author = this["author"]?.jsonPrimitive?.content
        val hasCaptions = this["hasCaptions"]?.jsonPrimitive?.booleanOrNull ?: false
        return SAnime.create().apply {
            this.title = title + if (hasCaptions) " [CC]" else ""
            url = "$host/watch?v=$videoId"
            thumbnail_url = "$host/vi/$videoId/mqdefault.jpg"
            this.author = author
            description = buildString {
                this@toVideoSAnime["description"]?.jsonPrimitive?.content?.let {
                    appendLine(it)
                    appendLine()
                }
                author?.let { appendLine("Author: $it") }
                this@toVideoSAnime["lengthSeconds"]?.jsonPrimitive?.content?.let {
                    appendLine("Duration: ${it}s")
                }
                this@toVideoSAnime["viewCount"]?.jsonPrimitive?.content?.let {
                    appendLine("Views: $it")
                }
            }.trim()
            status = SAnime.COMPLETED
        }
    }

    private fun extractVideoId(url: String): String? {
        if (url.startsWith("video:")) return url.substringAfter("video:")
        val patterns = listOf(
            Regex("""(?:v=|/v/|/embed/|youtu\\.be/)([a-zA-Z0-9_-]{11})"""),
            Regex("""^([a-zA-Z0-9_-]{11})$"""),
        )
        patterns.forEach { regex ->
            regex.find(url)?.groupValues?.getOrNull(1)?.let { return it }
        }
        return null
    }

    private fun String.extractChannelId(): String? {
        return if (startsWith("channel:")) substringAfter("channel:") else null
    }

    private fun fixThumbnail(url: String, host: String): String {
        return when {
            url.startsWith("http://inv.") -> url.replace(Regex("""^http://inv\.[^/]+(:3000)?"""), host)
            url.startsWith("https://inv.") -> url.replace(Regex("""^https://inv\.[^/]+(:3000)?"""), host)
            url.startsWith("/") -> "$host$url"
            else -> url
        }
    }

    private val Response.host: String
        get() = request.url.run { "$scheme://$host" }

    override fun getFilterList() = AnimeFilterList(
        TypeFilter(),
        SortFilter(),
        DateFilter(),
    )

    companion object {
        private const val DEFAULT_INSTANCE = "https://inv.zoomerville.com"

        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
        private const val PREF_INSTANCE_KEY = "invidious_instance"
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_HTML_CATALOG_KEY = "use_html_catalog"
        private const val PREF_HTML_CATALOG_DEFAULT = false

        private val PREF_QUALITY_ENTRIES = arrayOf("DASH", "HD1080", "HD720", "medium", "small")
        private val PREF_QUALITY_VALUES = arrayOf("DASH", "HD1080", "HD720", "medium", "small")
        private const val PREF_QUALITY_DEFAULT = "DASH"

        private val ITAG_LABELS = linkedMapOf(
            "37" to "HD1080",
            "46" to "HD1080",
            "22" to "HD720",
            "45" to "HD720",
            "18" to "medium",
            "43" to "medium",
            "36" to "small",
            "17" to "small",
        )

        private val CHECK_REGEX = Regex("""check=([A-Za-z0-9_-]+)""")

        private val ADAPTATION_SET_REGEX = Regex(
            """<AdaptationSet([^>]*)>(.*?)</AdaptationSet>""",
            RegexOption.DOT_MATCHES_ALL,
        )
        private val REPRESENTATION_REGEX = Regex(
            """<Representation([^>]*)>(.*?)</Representation>""",
            RegexOption.DOT_MATCHES_ALL,
        )
        private val BASE_URL_REGEX = Regex("""<BaseURL>([^<]+)</BaseURL>""")

        private const val FIELDS = "fields=videoId,title,author,lengthSeconds,viewCount,publishedText"
        private const val DETAIL_FIELDS =
            "fields=videoId,title,description,author,lengthSeconds,viewCount,publishedText,formatStreams,recommendedVideos&local=true"

        private const val TAG = "Box"
    }
}

@Serializable
data class BoxSearchItem(
    val title: String? = null,
    val videoId: String? = null,
    val author: String? = null,
    @SerialName("lengthSeconds")
    val lengthSeconds: Int? = null,
    @SerialName("viewCount")
    val viewCount: Long? = null,
    val type: String? = null,
) {
    fun toSAnime(host: String): SAnime = SAnime.create().apply {
        title = this@BoxSearchItem.title ?: videoId ?: "Unknown"
        url = "$host/watch?v=$videoId"
        thumbnail_url = "$host/vi/$videoId/mqdefault.jpg"
        author = this@BoxSearchItem.author
        description = buildString {
            this@BoxSearchItem.author?.let { appendLine("Author: $it") }
            this@BoxSearchItem.viewCount?.let { appendLine("Views: $it") }
            this@BoxSearchItem.lengthSeconds?.let { appendLine("Duration: ${it}s") }
        }.trim()
        status = SAnime.COMPLETED
    }
}

@Serializable
data class BoxVideo(
    val videoId: String,
    val title: String,
    val description: String? = null,
    val author: String? = null,
    @SerialName("lengthSeconds")
    val lengthSeconds: Int? = null,
    @SerialName("viewCount")
    val viewCount: Long? = null,
    @SerialName("publishedText")
    val publishedText: String? = null,
    @SerialName("formatStreams")
    val formatStreams: List<BoxFormatStream>? = null,
    @SerialName("recommendedVideos")
    val recommendedVideos: List<BoxSearchItem>? = null,
) {
    fun toSAnime(host: String): SAnime = SAnime.create().apply {
        title = this@BoxVideo.title
        url = "$host/watch?v=$videoId"
        thumbnail_url = "$host/vi/$videoId/hqdefault.jpg"
        author = this@BoxVideo.author
        description = buildString {
            this@BoxVideo.description?.let {
                appendLine(it.replace(Regex("<br\\s*/?>"), "\n").take(800))
                appendLine()
            }
            this@BoxVideo.author?.let { appendLine("Author: $it") }
            this@BoxVideo.viewCount?.let { appendLine("Views: $it") }
            this@BoxVideo.publishedText?.let { appendLine("Published: $it") }
            this@BoxVideo.lengthSeconds?.let { appendLine("Duration: ${it}s") }
        }.trim()
        status = SAnime.COMPLETED
    }
}

@Serializable
data class BoxFormatStream(
    val itag: String,
    val url: String? = null,
    @SerialName("qualityLabel")
    val qualityLabel: String? = null,
    val height: Int? = null,
)

@Serializable
data class BoxCaptionsResponse(
    val captions: List<BoxCaption> = emptyList(),
)

@Serializable
data class BoxCaption(
    val label: String,
    val languageCode: String,
    val url: String,
)

@Serializable
data class BoxWatchData(
    val id: String? = null,
    val title: String? = null,
    @SerialName("length_seconds")
    val lengthSeconds: Double? = null,
)

private class TypeFilter : AnimeFilter.Select<String>(
    "Tipo",
    arrayOf("Video", "Channel"),
) {
    fun toValue() = if (state == 1) "channel" else "video"
}

private class SortFilter : AnimeFilter.Select<String>(
    "Ordenar por",
    arrayOf("Date (newest)", "Relevance", "Views"),
) {
    fun toValue() = when (state) {
        1 -> "relevance"
        2 -> "views"
        else -> "date"
    }
}

private class DateFilter : AnimeFilter.Select<String>(
    "Fecha",
    arrayOf("Any", "Hour", "Today", "Week", "Month", "Year"),
) {
    fun toValue() = when (state) {
        1 -> "hour"
        2 -> "today"
        3 -> "week"
        4 -> "month"
        5 -> "year"
        else -> null
    }
}

