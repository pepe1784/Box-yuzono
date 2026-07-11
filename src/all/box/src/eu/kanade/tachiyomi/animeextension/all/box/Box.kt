package eu.kanade.tachiyomi.animeextension.all.box

import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET

import keiyoushi.utils.getPreferencesLazy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.Response

import java.net.URLEncoder

class Box : AnimeHttpSource(), ConfigurableAnimeSource {

    override val name = "box"
    override val lang = "all"
    override val id: Long = 9134775860771942682L
    override val baseUrl = DEFAULT_INSTANCE
    override val supportsLatest = true

    private val preferences by getPreferencesLazy()
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val invidiousHost: String
        get() = preferences.getString(PREF_INSTANCE_KEY, DEFAULT_INSTANCE)!!
            .trim()
            .trimEnd('/')

    override fun headersBuilder() = super.headersBuilder()
        .add("Accept", "application/json")
        .add(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36",
        )

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request =
        GET("$invidiousHost/api/v1/popular?$FIELDS", headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parseSearchResults(response)

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$invidiousHost/api/v1/trending?$FIELDS", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseSearchResults(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        return GET(
            "$invidiousHost/api/v1/search?q=$encoded&type=video&page=$page&$FIELDS",
            headers,
        )
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseSearchResults(response)

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request {
        val id = extractVideoId(anime.url) ?: anime.url
        return GET("$invidiousHost/api/v1/videos/$id?$DETAIL_FIELDS", headers)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val video = json.decodeFromString<BoxVideo>(response.body.string())
        return video.toSAnime(response.host)
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

    override fun videoListRequest(episode: SEpisode): Request =
        GET("$invidiousHost/api/v1/videos/${episode.url}?$DETAIL_FIELDS", headers)

    override fun videoListParse(response: Response): List<Video> {
        val video = json.decodeFromString<BoxVideo>(response.body.string())
        val host = response.host
        val videos = mutableListOf<Video>()

        // DASH manifest served by Invidious. When the instance proxies video,
        // the device only talks to Invidious, never to YouTube directly.
        val dashUrl = "$host/api/manifest/dash/id/${video.videoId}"
        videos += Video(dashUrl, "DASH (adaptive)", dashUrl, headers)

        // With local=true, Invidious proxies these progressive streams too.
        video.formatStreams?.forEach { stream ->
            val url = stream.url ?: return@forEach
            val quality = stream.qualityLabel ?: "${stream.height}p"
            videos += Video(url, quality, url, headers)
        }

        return videos
    }

    override fun List<Video>.sort(): List<Video> {
        val pref = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)
            ?: PREF_QUALITY_DEFAULT
        return sortedByDescending { it.quality.contains(pref, ignoreCase = true) }
    }

    // ============================== Preferences ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val instancePref = EditTextPreference(screen.context).apply {
            title = "Invidious instance"
            setDefaultValue(DEFAULT_INSTANCE)
            text = preferences.getString(PREF_INSTANCE_KEY, DEFAULT_INSTANCE)
                ?.trim()
                ?.trimEnd('/')
            summary = "Current: $text"
            setOnPreferenceChangeListener { _, newValue ->
                val value = (newValue as String).trim().trimEnd('/')
                preferences.edit().putString(PREF_INSTANCE_KEY, value).apply()
                summary = "Current: $value"
                true
            }
        }

        val qualityPref = ListPreference(screen.context).apply {
            title = "Preferred quality"
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_VALUES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            value = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(PREF_QUALITY_KEY, newValue as String).apply()
                true
            }
        }

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

    private val Response.host: String
        get() = request.url.run { "$scheme://${host}" }

    companion object {
        private const val DEFAULT_INSTANCE = "https://iv.melmac.space"
        private const val PREF_INSTANCE_KEY = "invidious_instance"
        private const val PREF_QUALITY_KEY = "preferred_quality"

        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p", "DASH")
        private val PREF_QUALITY_VALUES = arrayOf("1080", "720", "480", "360", "DASH")
        private const val PREF_QUALITY_DEFAULT = "720"

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
