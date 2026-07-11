package eu.kanade.tachiyomi.animeextension.all.box

import android.text.InputType
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
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
import okhttp3.Headers
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
            .set("Accept", "text/html")
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
        val check = extractCheck(doc)
        val videos = mutableListOf<Video>()

        // DASH manifest: proxy it through a local HTTP server so Aniyomi/MPV
        // sees a .mpd URL and the proxy can add the Anubis cookie to every
        // segment request.
        val dashSrc = doc.selectFirst("video#player source[type*=dash]")?.attr("src")
        if (!dashSrc.isNullOrBlank()) {
            val absoluteDash = if (dashSrc.startsWith("http")) dashSrc else "$host$dashSrc"
            dashProxy?.stop()
            dashProxy = DashProxyServer(client).apply { start() }
            val proxyUrl = "${dashProxy!!.url}?url=${URLEncoder.encode(absoluteDash, "UTF-8")}"
            videos += Video(proxyUrl, "DASH (adaptive)", proxyUrl, headers)
        }

        // Probe known combined (video+audio) YouTube itags as fallback.
        if (!check.isNullOrBlank()) {
            ITAG_LABELS.forEach { (itag, label) ->
                val url = probeItag(host, videoId, check, itag) ?: return@forEach
                videos += Video(url, label, url, headers)
            }
        }

        // Fallback to the progressive stream listed in the player page.
        if (videos.isEmpty()) {
            doc.select("video#player source").forEach { source ->
                if (source.hasAttr("hidequalityoption")) return@forEach
                if (source.attr("type").contains("dash", ignoreCase = true)) return@forEach
                val src = source.attr("src").takeIf { it.isNotBlank() } ?: return@forEach
                val absolute = if (src.startsWith("http")) src else "$host$src"
                val label = source.attr("label").ifBlank { "Video" }
                val finalUrl = resolveVideoUrl(absolute)
                videos += Video(finalUrl, label, finalUrl, headers)
            }
        }

        return videos
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
        val src = doc.selectFirst("video#player source")?.attr("src") ?: return null
        return CHECK_REGEX.find(src)?.groupValues?.getOrNull(1)
    }

    override fun List<Video>.sort(): List<Video> {
        val pref = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)
            ?: PREF_QUALITY_DEFAULT
        return sortedByDescending { it.quality.contains(pref, ignoreCase = true) }
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

        private val PREF_QUALITY_ENTRIES = arrayOf("DASH", "720p", "360p", "240p", "144p")
        private val PREF_QUALITY_VALUES = arrayOf("DASH", "720", "360", "240", "144")
        private const val PREF_QUALITY_DEFAULT = "DASH"

        private val ITAG_LABELS = linkedMapOf(
            "22" to "720p",
            "18" to "360p",
            "36" to "240p",
            "17" to "144p",
        )

        private val CHECK_REGEX = Regex("""check=([A-Za-z0-9_-]+)""")

        private var dashProxy: DashProxyServer? = null

        private const val FIELDS = "fields=videoId,title,author,lengthSeconds,viewCount,publishedText"
        private const val DETAIL_FIELDS =
            "fields=videoId,title,description,author,lengthSeconds,viewCount,publishedText,formatStreams,recommendedVideos&local=true"
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
