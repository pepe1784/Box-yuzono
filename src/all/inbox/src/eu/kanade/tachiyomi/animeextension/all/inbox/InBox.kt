package eu.kanade.tachiyomi.animeextension.all.inbox

import android.content.Intent
import android.net.Uri
import android.util.Log
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
import keiyoushi.utils.getListPreference
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.getSwitchPreference
import keiyoushi.utils.getTextPreference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * INbox: YouTube as an Aniyomi source, talking to InnerTube directly.
 *
 * Slice 1 only handles videos with sound: one search hit (or a pasted URL) is
 * one series with a single episode, and the episode resolves to a muxed stream
 * (video + AAC audio) so playback never depends on a separate audio track.
 *
 * DASH (video-only + audioTracks) is available behind an experimental switch.
 */
class InBox :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "INbox"
    override val lang = "all"
    override val id: Long = 4883267520314558307L
    override val supportsLatest = false
    override var baseUrl: String = InnerTube.BASE_URL

    private val preferences by getPreferencesLazy()

    private val includeMuxed: Boolean
        get() = preferences.getBoolean(PREF_MUXED_KEY, PREF_MUXED_DEFAULT)

    private val includeDash: Boolean
        get() = preferences.getBoolean(PREF_DASH_KEY, PREF_DASH_DEFAULT)

    private val fetchSubtitles: Boolean
        get() = preferences.getBoolean(PREF_SUBTITLES_KEY, PREF_SUBTITLES_DEFAULT)

    private val preferredQuality: String
        get() = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT

    private val preferredAudioLang: String
        get() = preferences.getString(PREF_AUDIO_LANG_KEY, PREF_AUDIO_LANG_DEFAULT) ?: PREF_AUDIO_LANG_DEFAULT

    private val preferredSubtitleLang: String
        get() = preferences.getString(PREF_SUBTITLE_LANG_KEY, PREF_SUBTITLE_LANG_DEFAULT) ?: PREF_SUBTITLE_LANG_DEFAULT

    private val preferredClient: String
        get() = preferences.getString(PREF_CLIENT_KEY, PREF_CLIENT_DEFAULT) ?: PREF_CLIENT_DEFAULT

    private val poToken: String
        get() = preferences.getString(PREF_PO_TOKEN_KEY, "").orEmpty()

    override val client: OkHttpClient by lazy {
        network.client.newBuilder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val innerTube: InnerTube by lazy {
        InnerTube(client) { spec -> innertubeHeaders(spec) }
    }

    private val json = Json { ignoreUnknownKeys = true }

    private fun innertubeHeaders(spec: ClientSpec, videoId: String? = null): Headers =
        headersBuilder().apply {
            set("User-Agent", spec.userAgent)
            set("Content-Type", "application/json")
            set("Accept", "application/json")
            set("Accept-Language", "en-US,en;q=0.9")
            set("X-YouTube-Client-Name", spec.id)
            set("X-YouTube-Client-Version", spec.version)
            set("X-Goog-Api-Format-Version", "1")
            set("Origin", InnerTube.BASE_URL)
            set("Referer", "${InnerTube.BASE_URL}/")
            add("Cookie", CONSENT_COOKIE)
            videoId?.let { set(InnerTube.HEADER_VIDEO_ID, it) }
            set(InnerTube.HEADER_CLIENT, spec.name)
        }.build()

    // ============================ Catalog ============================

    override fun popularAnimeRequest(page: Int): Request = innerTube.searchRequest("trending")

    override fun popularAnimeParse(response: Response): AnimesPage = parseSearch(response)

    override fun latestUpdatesRequest(page: Int): Request = popularAnimeRequest(page)

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val videoId = extractVideoId(query)
        if (videoId != null) return innerTube.oembedRequest(videoId)

        val trimmed = query.trim()
        if (trimmed.isEmpty() && page == 1) return innerTube.searchRequest("trending")
        if (trimmed.startsWith("channel:")) {
            val id = trimmed.removePrefix("channel:").trim()
            return innerTube.channelRequest(id)
        }
        if (trimmed.startsWith("playlist:")) {
            val id = trimmed.removePrefix("playlist:").trim()
            return innerTube.playlistRequest(id)
        }

        val filterList = if (filters.isNotEmpty()) filters else getFilterList()
        val type = filterList.find { it is TypeFilter } as? TypeFilter
        val sort = filterList.find { it is SortFilter } as? SortFilter
        val typeState = type?.state ?: 0
        val sortState = sort?.state ?: 0
        if (typeState == 1 && trimmed.isNotEmpty()) return innerTube.searchChannelRequest(trimmed)
        if (typeState == 2 && trimmed.isNotEmpty()) return innerTube.searchPlaylistRequest(trimmed)

        return innerTube.searchRequest(trimmed.ifEmpty { "trending" }, sortState)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val path = response.request.url.encodedPath
        if (path.contains("/oembed")) {
            return AnimesPage(listOf(parseOembed(response)), false)
        }
        if (path.contains("/browse")) {
            return parseBrowse(response)
        }
        if (path.contains("/search")) {
            return parseSearch(response)
        }
        val body = try { response.use { it.body?.string() } ?: "" } catch (e: Exception) { "" }
        if (body.contains("\"videoRenderer\"")) {
            return parseSearch(response)
        }
        if (body.contains("\"channelRenderer\"") || body.contains("\"playlistRenderer\"")) {
            return parseBrowse(response)
        }
        return AnimesPage(emptyList(), false)
    }

    private fun parseSearch(response: Response): AnimesPage {
        val videos = try {
            innerTube.parseSearch(response)
        } catch (e: Exception) {
            Log.e(TAG, "Search parse failed", e)
            emptyList()
        }
        return AnimesPage(videos.map { it.toSAnime() }, false)
    }

    private fun parseBrowse(response: Response): AnimesPage {
        val entries = try {
            innerTube.parseBrowse(response)
        } catch (e: Exception) {
            Log.e(TAG, "Browse parse failed", e)
            emptyList()
        }
        return AnimesPage(entries.map { it.toSAnime() }, false)
    }

    // ============================= Details =============================

    override fun animeDetailsRequest(anime: SAnime): Request {
        val videoId = extractVideoId(anime.url) ?: anime.url
        return innerTube.oembedRequest(videoId)
    }

    override fun animeDetailsParse(response: Response): SAnime = parseOembed(response)

    private fun parseOembed(response: Response): SAnime {
        val watchUrl = response.request.url.queryParameter("url").orEmpty()
        val videoId = extractVideoId(watchUrl)
            ?: extractVideoId(response.request.url.toString())
            ?: response.request.url.toString()
        val body = response.use { it.body?.string() }.orEmpty()
        val obj = try {
            json.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            Log.e(TAG, "oEmbed parse failed for $videoId", e)
            JsonObject(emptyMap())
        }
        val title = obj.str("title") ?: videoId
        return SAnime.create().apply {
            url = videoId
            this.title = title
            author = obj.str("author_name").orEmpty()
            thumbnail_url = obj.str("thumbnail_url") ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
            description = "YouTube video"
            genre = "YouTube"
        }
    }

    // ============================ Episodes =============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val videoId = extractVideoId(anime.url) ?: anime.url
        return listOf(
            SEpisode.create().apply {
                url = videoId
                name = anime.title.ifBlank { "Video" }
                episode_number = 1F
            },
        )
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    // ============================== Video ==============================

    override fun videoListRequest(episode: SEpisode): Request {
        val videoId = extractVideoId(episode.url) ?: episode.url
        return innerTube.playerRequest(videoId, InnerTube.PLAYER_CLIENTS.first())
    }

    override fun videoListParse(response: Response): List<Video> {
        val videoId = extractVideoId(response.request.url.toString())
            ?: response.request.header(InnerTube.HEADER_VIDEO_ID)
        var token = poToken
        if (token.isBlank() && videoId != null && includeDash) {
            token = try {
                innerTube.fetchWatchPagePoToken(videoId)
            } catch (e: Exception) {
                ""
            }
            if (token.isNotBlank()) {
                preferences.edit().putString(PREF_PO_TOKEN_KEY, token).apply()
            }
        }
        val videos = innerTube.resolvePlayer(
            response,
            includeDash = includeDash,
            includeMuxed = includeMuxed,
            fetchSubtitles = fetchSubtitles,
            preferredAudioLang = preferredAudioLang,
            preferredSubtitleLang = preferredSubtitleLang,
            preferredQuality = preferredQuality,
            preferredClient = preferredClient,
            poToken = token,
        )
        Log.d(TAG, "Resolved ${videos.size} videos")
        return videos
    }

    // ============================ Settings =============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val context = screen.context

        val poTokenPref = androidx.preference.EditTextPreference(screen.context).apply {
            key = PREF_PO_TOKEN_KEY
            title = "PoToken (auto)"
            summary = "Auto-fetched from watch page if empty"
            setDefaultValue("")
        }
        screen.addPreference(poTokenPref)

        val clientPref = androidx.preference.ListPreference(screen.context).apply {
            key = PREF_CLIENT_KEY
            title = "Preferred server/client"
            entries = PREF_CLIENT_ENTRIES
            entryValues = PREF_CLIENT_VALUES
            setDefaultValue(PREF_CLIENT_DEFAULT)
            summary = "%s"
        }
        screen.addPreference(clientPref)

        val qualityPref = androidx.preference.ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_VALUES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }
        screen.addPreference(qualityPref)

        val muxedPref = androidx.preference.SwitchPreferenceCompat(screen.context).apply {
            key = PREF_MUXED_KEY
            title = "Show muxed streams (video + audio)"
            setDefaultValue(PREF_MUXED_DEFAULT)
        }
        screen.addPreference(muxedPref)

        val dashPref = androidx.preference.SwitchPreferenceCompat(screen.context).apply {
            key = PREF_DASH_KEY
            title = "Show DASH (separate video + audio)"
            setDefaultValue(PREF_DASH_DEFAULT)
        }
        screen.addPreference(dashPref)

        val subsPref = androidx.preference.SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SUBTITLES_KEY
            title = "Fetch subtitles/CC"
            setDefaultValue(PREF_SUBTITLES_DEFAULT)
        }
        screen.addPreference(subsPref)

        val subLangPref = androidx.preference.ListPreference(screen.context).apply {
            key = PREF_SUBTITLE_LANG_KEY
            title = "Subtitle language"
            entries = PREF_SUBTITLE_LANG_ENTRIES
            entryValues = PREF_SUBTITLE_LANG_VALUES
            setDefaultValue(PREF_SUBTITLE_LANG_DEFAULT)
            summary = "%s"
        }
        screen.addPreference(subLangPref)

        val audioLangPref = androidx.preference.ListPreference(screen.context).apply {
            key = PREF_AUDIO_LANG_KEY
            title = "Preferred audio language"
            entries = PREF_AUDIO_LANG_ENTRIES
            entryValues = PREF_AUDIO_LANG_VALUES
            setDefaultValue(PREF_AUDIO_LANG_DEFAULT)
            summary = "%s"
        }
        screen.addPreference(audioLangPref)
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        TypeFilter(),
        SortFilter(),
    )

    private class TypeFilter : AnimeFilter.Select<String>(
        "Search type",
        arrayOf("Videos", "Channels", "Playlists"),
    )

    private class SortFilter : AnimeFilter.Select<String>(
        "Sort by",
        arrayOf("Relevance", "Upload date", "View count", "Rating"),
    )

    private fun YouTubeVideo.toSAnime(): SAnime {
        val summary = buildString {
            append("YouTube video")
            if (duration.isNotBlank()) append(" · $duration")
            if (published.isNotBlank()) append(" · $published")
            if (author.isNotBlank()) append("\n$author")
        }
        return SAnime.create().apply {
            url = id
            title = this@toSAnime.title
            author = this@toSAnime.author
            thumbnail_url = this@toSAnime.thumbnail
            description = summary
            genre = "YouTube"
        }
    }

    companion object {
        private const val TAG = "INbox"

        private const val PREF_LOGIN_KEY = "pref_login_pot"
        private const val PREF_WATCH_URL_KEY = "pref_watch_url"
        private const val PREF_OPEN_WATCH_KEY = "pref_open_watch"
        private const val PREF_PO_TOKEN_KEY = "pref_potoken"

        private const val PREF_CLIENT_KEY = "pref_client"
        private const val PREF_CLIENT_DEFAULT = "auto"
        private val PREF_CLIENT_ENTRIES = arrayOf("Auto (ANDROID first)", "ANDROID", "ANDROID_VR", "IOS")
        private val PREF_CLIENT_VALUES = arrayOf("auto", "ANDROID", "ANDROID_VR", "IOS")

        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "auto"
        private val PREF_QUALITY_ENTRIES = arrayOf("Automatic", "1080p", "720p", "480p", "360p")
        private val PREF_QUALITY_VALUES = arrayOf("auto", "1080", "720", "480", "360")

        private const val PREF_MUXED_KEY = "pref_muxed"
        private const val PREF_MUXED_DEFAULT = true

        private const val PREF_DASH_KEY = "pref_dash"
        private const val PREF_DASH_DEFAULT = true

        private const val PREF_SUBTITLES_KEY = "pref_subs"
        private const val PREF_SUBTITLES_DEFAULT = true

        private const val PREF_SUBTITLE_LANG_KEY = "pref_sub_lang"
        private const val PREF_SUBTITLE_LANG_DEFAULT = "auto"
        private val PREF_SUBTITLE_LANG_ENTRIES = arrayOf("Auto", "English", "Spanish", "Japanese", "All")
        private val PREF_SUBTITLE_LANG_VALUES = arrayOf("auto", "en", "es", "ja", "all")

        private const val PREF_AUDIO_LANG_KEY = "pref_audio_lang"
        private const val PREF_AUDIO_LANG_DEFAULT = "original"
        private val PREF_AUDIO_LANG_ENTRIES = arrayOf("Original", "Spanish", "English", "Japanese", "All")
        private val PREF_AUDIO_LANG_VALUES = arrayOf("original", "es", "en", "ja", "all")

        private const val CONSENT_COOKIE = "SOCS=CAESEwgDEgk0ODE3Nzk3NTQaAmVuIAEaBgiA_K-fBg"
    }
}
