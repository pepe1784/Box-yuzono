package eu.kanade.tachiyomi.animeextension.all.box

import android.text.InputType
import android.util.Log
import aniyomi.lib.playlistutils.PlaylistUtils
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import java.net.URLEncoder

class Box : AnimeHttpSource(), ConfigurableAnimeSource {

    override val name = "box"
    override val lang = "all"
    override val id: Long = 9134775860771942682L
    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    override var baseUrl: String by preferences.delegate(PREF_INSTANCE_KEY, DEFAULT_INSTANCE)

    override val client: OkHttpClient by lazy {
        network.cloudflareClient.newBuilder()
            .cookieJar(MemoryCookieJar())
            .addInterceptor(AnubisInterceptor())
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

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/api/v1/popular?$FIELDS", headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parseSearchResults(response)

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/api/v1/trending?$FIELDS", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseSearchResults(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        return GET(
            "$baseUrl/api/v1/search?q=$encoded&type=video&page=$page&$FIELDS",
            headers,
        )
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseSearchResults(response)

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request {
        val id = extractVideoId(anime.url) ?: anime.url
        return GET("$baseUrl/watch?v=$id", watchHeaders)
    }

    override fun animeDetailsParse(response: Response): SAnime {
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
        val id = extractVideoId(anime.url) ?: anime.url
        return listOf(
            SEpisode.create().apply {
                url = id
                name = "Video"
                episode_number = 1F
            },
        )
    }

    override fun episodeListParse(response: Response): List<SEpisode> =
        throw UnsupportedOperationException()

    // ============================ Video Links =============================

    override fun videoListRequest(episode: SEpisode): Request {
        val id = extractVideoId(episode.url) ?: episode.url
        return GET("$baseUrl/watch?v=$id", watchHeaders)
    }

    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()
        val host = response.host
        val videoId = extractVideoId(response.request.url.toString()) ?: return emptyList()
        val check = extractCheck(doc) ?: ""
        val videos = mutableListOf<Video>()
        val seenUrls = mutableSetOf<String>()

        // DASH manifest: parse it directly and expose each video Representation
        // as a Video with its matching audio track(s). Other Yuzono extensions
        // (e.g. VVVVID, AllAnime) do exactly this instead of proxying the MPD.
        val dashSrc = doc.selectFirst("video#player source[type*=dash]")?.attr("src") ?: ""
        val dashUrl = when {
            dashSrc.isNotBlank() -> buildDashManifestUrl(dashSrc, host)
            check.isNotBlank() -> "$host/api/manifest/dash/id/$videoId?local=true&unique_res=1&check=$check"
            else -> ""
        }
        Log.d(TAG, "dashSrc=$dashSrc dashUrl=$dashUrl")
        if (dashUrl.isNotBlank()) {
            try {
                val dashVideos = parseDashManifest(dashUrl)
                Log.d(TAG, "Parsed ${dashVideos.size} DASH videos")
                if (dashVideos.isEmpty()) {
                    videos += Video("", "DASH DEBUG: 0 videos parsed", "", headers)
                }
                dashVideos.forEach { video ->
                    val videoUrl = video.videoUrl ?: run {
                        videos += Video("", "DASH DEBUG: null videoUrl", "", headers)
                        return@forEach
                    }
                    if (seenUrls.add(videoUrl)) {
                        Log.d(TAG, "Adding DASH source: ${video.quality}")
                        videos += video
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse DASH manifest", e)
                videos += Video("", "DASH DEBUG: ${e.message}", "", headers)
            }
        } else {
            videos += Video("", "DASH DEBUG: empty dashUrl", "", headers)
        }

        // HLS fallback: Invidious also exposes an HLS master playlist.
        if (check.isNotBlank()) {
            val hlsUrl = "$host/api/manifest/hls_playlist/id/$videoId?local=true&check=$check"
            try {
                val playlistUtils = PlaylistUtils(client, watchHeaders)
                val hlsVideos = playlistUtils.extractFromHls(
                    hlsUrl,
                    masterHeaders = watchHeaders,
                    videoHeaders = watchHeaders,
                    videoNameGen = { quality -> "HLS $quality" },
                )
                hlsVideos.forEach { video ->
                    val videoUrl = video.videoUrl ?: return@forEach
                    if (seenUrls.add(videoUrl)) {
                        Log.d(TAG, "Adding HLS source: ${video.quality}")
                        videos += video
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse HLS playlist", e)
            }
        }

        // Progressive streams exposed by the player page (HD720, medium, small).
        doc.select("video#player source").forEach { source ->
            if (source.hasAttr("hidequalityoption")) return@forEach
            if (source.attr("type").contains("dash", ignoreCase = true)) return@forEach
            val src = source.attr("src").takeIf { it.isNotBlank() } ?: return@forEach
            val absolute = if (src.startsWith("http")) src else "$host$src"
            val label = source.attr("label").ifBlank { "Video" }
            val finalUrl = resolveVideoUrl(absolute)
            if (!seenUrls.add(finalUrl)) return@forEach
            Log.d(TAG, "Adding progressive source: $label")
            videos += Video(finalUrl, label, finalUrl, headers)
        }

        // Only probe itags as a fallback when the page exposes no progressive
        // sources. This avoids blocking the quality list with multiple requests.
        if (videos.none { it.quality != "DASH" } && check.isNotBlank()) {
            ITAG_LABELS.forEach { (itag, label) ->
                val url = probeItag(host, videoId, check, itag) ?: return@forEach
                if (!seenUrls.add(url)) return@forEach
                Log.d(TAG, "Adding fallback itag $itag -> $label")
                videos += Video(url, label, url, headers)
            }
        }

        return videos
    }

    private fun buildDashManifestUrl(src: String, host: String): String {
        return if (src.startsWith("http")) src else "$host$src"
    }

    private fun parseDashManifest(manifestUrl: String): List<Video> {
        Log.d(TAG, "parseDashManifest: $manifestUrl")
        val request = Request.Builder()
            .url(manifestUrl)
            .headers(watchHeaders)
            .header("Accept", "application/dash+xml")
            .build()
        val manifest = client.newCall(request).execute().use { it.body?.string() ?: "" }
        Log.d(TAG, "Manifest length: ${manifest.length}")

        val debugInfo = mutableListOf<String>()
        debugInfo += "len=${manifest.length}"
        debugInfo += "start=${manifest.take(200).replace("\n", " ")}"

        val audioUrls = mutableListOf<String>()
        val videoReps = mutableListOf<DashRep>()

        val adaptationSets = ADAPTATION_SET_REGEX.findAll(manifest).toList()
        debugInfo += "adaptationSets=${adaptationSets.size}"

        adaptationSets.forEach { asMatch ->
            val asAttrs = parseAttributes(asMatch.groupValues[1])
            val contentType = asAttrs["contentType"]?.lowercase()
                ?: asAttrs["mimeType"]?.lowercase()
                ?: ""
            val asBlock = asMatch.groupValues[2]
            val hasSegmentBase = asBlock.contains("<SegmentBase", ignoreCase = true)
            val repCount = REPRESENTATION_REGEX.findAll(asBlock).count()
            debugInfo += "AS type=$contentType segBase=$hasSegmentBase reps=$repCount"

            // Skip streams without index/init ranges (OTF) since ExoPlayer can't
            // play them as separate DASH representations.
            if (!hasSegmentBase) return@forEach

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

                val resolvedUrl = resolveVideoUrl(baseUrl)

                when {
                    contentType.contains("audio") -> audioUrls += resolvedUrl
                    contentType.contains("video") -> {
                        videoReps += DashRep(
                            url = resolvedUrl,
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
        if (videoReps.isEmpty()) {
            return debugInfo.mapIndexed { index, info ->
                Video("dash-debug-$index", "DASH DEBUG $index: $info", "dash-debug-$index", headers)
            }
        }

        val audioTracks = audioUrls.map { Track(it, "Audio") }

        // Prefer H.264, then highest resolution up to 1080p.
        val h264 = videoReps.filter { it.codecs.startsWith("avc1") }
        val candidates = if (h264.isNotEmpty()) h264 else videoReps
        val capped = candidates.filter { it.height <= 1080 }
        val ordered = (if (capped.isNotEmpty()) capped else candidates)
            .sortedByDescending { it.height }

        return ordered.map { rep ->
            val label = "DASH ${rep.height}p"
            // Do not attach the source headers (Referer/Origin from Invidious) to
            // the DASH streams; other extensions pass the direct URLs without
            // extra headers and let ExoPlayer handle googlevideo requests.
            Video(rep.url, label, rep.url, audioTracks = audioTracks)
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

        screen.addPreference(instancePref)
        screen.addPreference(qualityPref)
    }

    // ============================== Helpers ==============================

    private fun parseSearchResults(response: Response): AnimesPage {
        val host = response.host
        val items = json.decodeFromString<List<BoxSearchItem>>(response.body.string())
        val videos = items.mapNotNull { item ->
            if (item.videoId == null) return@mapNotNull null
            item.toSAnime(host)
        }
        return AnimesPage(videos, videos.isNotEmpty())
    }

    private fun extractVideoId(url: String): String? {
        val patterns = listOf(
            Regex("""(?:v=|/v/|/embed/|youtu\.be/)([a-zA-Z0-9_-]{11})"""),
            Regex("""^([a-zA-Z0-9_-]{11})$"""),
        )
        patterns.forEach { regex ->
            regex.find(url)?.groupValues?.getOrNull(1)?.let { return it }
        }
        return null
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

    companion object {
        private const val DEFAULT_INSTANCE = "https://inv.zoomerville.com"

        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
        private const val PREF_INSTANCE_KEY = "invidious_instance"
        private const val PREF_QUALITY_KEY = "preferred_quality"

        private val PREF_QUALITY_ENTRIES = arrayOf("DASH", "HD720", "medium", "small")
        private val PREF_QUALITY_VALUES = arrayOf("DASH", "HD720", "medium", "small")
        private const val PREF_QUALITY_DEFAULT = "DASH"

        private val ITAG_LABELS = linkedMapOf(
            "22" to "HD720",
            "18" to "medium",
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

/**
 * In-memory cookie jar so the Anubis auth cookie is reused across the
 * extension's requests to the Invidious instance.
 */
private class MemoryCookieJar : CookieJar {
    private val store = mutableMapOf<String, MutableList<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        store.getOrPut(url.host) { mutableListOf() }.addAll(cookies)
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        return store[url.host]?.filter { it.expiresAt >= System.currentTimeMillis() } ?: emptyList()
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
data class BoxWatchData(
    val id: String? = null,
    val title: String? = null,
    @SerialName("length_seconds")
    val lengthSeconds: Double? = null,
)
