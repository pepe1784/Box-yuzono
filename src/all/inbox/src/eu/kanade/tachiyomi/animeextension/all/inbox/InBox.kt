package eu.kanade.tachiyomi.animeextension.all.inbox

import android.util.Log
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import keiyoushi.utils.getListPreference
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.getSwitchPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * INbox: YouTube as an Aniyomi source, talking to the InnerTube API directly.
 *
 * A search hit can be a video, a playlist or a channel:
 * - videos are one series with a single episode;
 * - playlists and channels expand into one episode per video;
 * - every episode exposes muxed formats plus DASH (separate video + audio).
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

    private val verifyStreams: Boolean
        get() = preferences.getBoolean(PREF_VERIFY_KEY, PREF_VERIFY_DEFAULT)

    private val preferredQuality: String
        get() = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT

    private val preferredAudioLang: String
        get() = preferences.getString(PREF_AUDIO_LANG_KEY, PREF_AUDIO_LANG_DEFAULT) ?: PREF_AUDIO_LANG_DEFAULT

    private val preferredSubtitleLang: String
        get() = preferences.getString(PREF_SUBTITLE_LANG_KEY, PREF_SUBTITLE_LANG_DEFAULT)
            ?: PREF_SUBTITLE_LANG_DEFAULT

    private val preferredClient: String
        get() = preferences.getString(PREF_CLIENT_KEY, PREF_CLIENT_DEFAULT) ?: PREF_CLIENT_DEFAULT

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

    private fun innertubeHeaders(spec: ClientSpec): Headers = headersBuilder().apply {
        set("User-Agent", spec.userAgent)
        set("Content-Type", "application/json")
        set("Accept", "application/json")
        set("Accept-Language", "en-US,en;q=0.9")
        set("X-YouTube-Client-Name", spec.id)
        set("X-YouTube-Client-Version", spec.version)
        set("Origin", InnerTube.BASE_URL)
        set("Referer", "${InnerTube.BASE_URL}/")
        add("Cookie", CONSENT_COOKIE)
    }.build()

    // ============================ Catalog ============================

    override fun popularAnimeRequest(page: Int): Request = innerTube.searchRequest("trending", InnerTube.sortParams(2))

    override fun popularAnimeParse(response: Response): AnimesPage = parseListing(response)

    override fun latestUpdatesRequest(page: Int): Request = popularAnimeRequest(page)

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val trimmed = query.trim()

        // Pasted links resolve straight to the matching series/video.
        extractPlaylistId(trimmed)?.let {
            return tagged(innerTube.playlistRequest(it), InnerTube.Kind.PLAYLIST, it)
        }
        extractChannelId(trimmed)?.let {
            return tagged(innerTube.channelVideosRequest(it), InnerTube.Kind.CHANNEL, it)
        }
        extractVideoId(trimmed)?.let { return innerTube.oembedRequest(it) }

        val filterList = if (filters.isNotEmpty()) filters else getFilterList()
        val typeState = (filterList.find { it is TypeFilter } as? TypeFilter)?.state ?: 0
        val sortState = (filterList.find { it is SortFilter } as? SortFilter)?.state ?: 0

        val params = when (typeState) {
            1 -> InnerTube.PARAMS_CHANNELS
            2 -> InnerTube.PARAMS_PLAYLISTS
            else -> InnerTube.sortParams(sortState)
        }
        return innerTube.searchRequest(trimmed.ifEmpty { "trending" }, params)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        // A pasted playlist/channel becomes a single series instead of a grid of its videos.
        val kind = response.request.header(InnerTube.HEADER_TARGET)
        if (kind != null) {
            val targetUrl = response.request.header(InnerTube.HEADER_TARGET_URL).orEmpty()
            val body = response.use { runCatching { it.body.string() }.getOrNull() }.orEmpty()
            return AnimesPage(listOf(animeFromBody(kind, targetUrl, body)), false)
        }
        return parseListing(response)
    }

    /** Marks a request as belonging to one series so the parsers know what they are reading. */
    private fun tagged(request: Request, kind: InnerTube.Kind, id: String): Request = request.newBuilder()
        .header(InnerTube.HEADER_TARGET, kind.name)
        .header(InnerTube.HEADER_TARGET_URL, InnerTube.Target(kind, id).encode())
        .build()

    private fun parseListing(response: Response): AnimesPage {
        if (response.request.url.encodedPath.contains("/oembed")) {
            return AnimesPage(listOf(parseOembed(response)), false)
        }
        val body = try {
            response.use { it.body.string() }.orEmpty()
        } catch (e: Exception) {
            Log.e(TAG, "Listing body failed", e)
            ""
        }
        val listing = innerTube.parseListing(body)
        if (listing.entries.isEmpty()) Log.d(TAG, "No entries in ${response.request.url.encodedPath}")
        // Single page: returning hasNextPage=true would re-request page 1 forever.
        return AnimesPage(listing.entries.map { it.toSAnime() }, false)
    }

    // ============================= Details =============================

    override fun animeDetailsRequest(anime: SAnime): Request {
        val target = decodeTarget(anime.url)
        val request = when (target.kind) {
            InnerTube.Kind.PLAYLIST -> innerTube.playlistRequest(target.id)
            InnerTube.Kind.CHANNEL -> innerTube.channelVideosRequest(target.id)
            InnerTube.Kind.VIDEO -> innerTube.oembedRequest(target.id)
        }
        return tagged(request, target.kind, target.id)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val kind = response.request.header(InnerTube.HEADER_TARGET)
        if (kind == null || kind == InnerTube.Kind.VIDEO.name) return parseOembed(response)

        val targetUrl = response.request.header(InnerTube.HEADER_TARGET_URL).orEmpty()
        val body = response.use { runCatching { it.body.string() }.getOrNull() }.orEmpty()
        return animeFromBody(kind, targetUrl, body)
    }

    private fun animeFromBody(kind: String, targetUrl: String, body: String): SAnime {
        val metadata = innerTube.parseMetadata(body)
        val fallback = decodeTarget(targetUrl).id
        return SAnime.create().apply {
            url = targetUrl
            title = metadata?.title?.takeIf { it.isNotBlank() } ?: fallback
            author = metadata?.author.orEmpty()
            thumbnail_url = metadata?.thumbnail.asAbsoluteUrl()
            description = metadata?.description.orEmpty()
            genre = if (kind == InnerTube.Kind.CHANNEL.name) "YouTube channel" else "YouTube playlist"
        }
    }

    private fun parseOembed(response: Response): SAnime {
        val watchUrl = response.request.url.queryParameter("url").orEmpty()
        val videoId = extractVideoId(watchUrl)
            ?: extractVideoId(response.request.url.toString())
            ?: response.request.url.toString()
        val body = response.use { it.body.string() }.orEmpty()
        val obj = try {
            json.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            Log.e(TAG, "oEmbed parse failed for $videoId", e)
            JsonObject(emptyMap())
        }
        val title = obj.str("title") ?: videoId
        return SAnime.create().apply {
            url = "video:$videoId"
            this.title = title
            author = obj.str("author_name").orEmpty()
            thumbnail_url = obj.str("thumbnail_url").asAbsoluteUrl().ifBlank { "https://i.ytimg.com/vi/$videoId/hqdefault.jpg" }
            description = "YouTube video"
            genre = "YouTube"
        }
    }

    // ============================ Episodes =============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val target = decodeTarget(anime.url)
        return withContext(Dispatchers.IO) {
            when (target.kind) {
                InnerTube.Kind.VIDEO -> listOf(singleEpisode(anime, target.id))
                InnerTube.Kind.PLAYLIST -> collectEpisodes(innerTube.playlistRequest(target.id))
                InnerTube.Kind.CHANNEL -> collectEpisodes(innerTube.channelVideosRequest(target.id))
            }
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    private fun singleEpisode(anime: SAnime, videoId: String): SEpisode = SEpisode.create().apply {
        url = videoId
        name = anime.title.ifBlank { "Video" }
        episode_number = 1F
    }

    /** Walks the browse continuations so playlists and channels expose their full listing. */
    private fun collectEpisodes(firstRequest: Request): List<SEpisode> {
        val seen = mutableSetOf<String>()
        val episodes = mutableListOf<SEpisode>()
        var request: Request? = firstRequest
        var pages = 0

        while (request != null && pages < MAX_PAGES && episodes.size < MAX_EPISODES) {
            val listing = try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e(TAG, "Episode page HTTP ${response.code}")
                        null
                    } else {
                        innerTube.parseListing(response.body.string().orEmpty())
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Episode page failed", e)
                null
            } ?: break

            for (entry in listing.entries) {
                if (entry.kind != InnerTube.Kind.VIDEO) continue
                if (!seen.add(entry.id)) continue
                episodes += SEpisode.create().apply {
                    url = entry.id
                    name = entry.title
                    episode_number = episodes.size + 1F
                }
                if (episodes.size >= MAX_EPISODES) break
            }
            request = listing.continuation?.let { innerTube.continuationRequest(it) }
            pages++
        }
        Log.d(TAG, "Loaded ${episodes.size} episodes from $pages page(s)")
        return episodes
    }

    // ============================== Video ==============================

    override fun videoListRequest(episode: SEpisode): Request {
        val videoId = extractVideoId(episode.url) ?: episode.url
        return innerTube.playerRequest(videoId, InnerTube.playerOrder(preferredClient).first())
    }

    override fun videoListParse(response: Response): List<Video> {
        val videos = innerTube.resolvePlayer(
            response = response,
            includeDash = includeDash,
            includeMuxed = includeMuxed,
            fetchSubtitles = fetchSubtitles,
            preferredAudioLang = preferredAudioLang,
            preferredSubtitleLang = preferredSubtitleLang,
            preferredQuality = preferredQuality,
            preferredClient = preferredClient,
            verifyStreams = verifyStreams,
        )
        Log.d(TAG, "Resolved ${videos.size} videos")
        return videos
    }

    // ============================ Settings =============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.getListPreference(
            key = PREF_CLIENT_KEY,
            default = PREF_CLIENT_DEFAULT,
            title = "Preferred server/client",
            summary = "%s",
            entries = PREF_CLIENT_ENTRIES.toList(),
            entryValues = PREF_CLIENT_VALUES.toList(),
        )
        screen.getListPreference(
            key = PREF_QUALITY_KEY,
            default = PREF_QUALITY_DEFAULT,
            title = "Preferred quality",
            summary = "%s",
            entries = PREF_QUALITY_ENTRIES.toList(),
            entryValues = PREF_QUALITY_VALUES.toList(),
        )
        screen.getSwitchPreference(
            key = PREF_MUXED_KEY,
            default = PREF_MUXED_DEFAULT,
            title = "Show muxed streams (video + audio)",
            summary = "Already combined. Lower resolution, but the most compatible option.",
        )
        screen.getSwitchPreference(
            key = PREF_DASH_KEY,
            default = PREF_DASH_DEFAULT,
            title = "Show DASH (separate video + audio)",
            summary = "Up to 1080p with selectable audio. Needs a player that accepts external audio.",
        )
        screen.getSwitchPreference(
            key = PREF_VERIFY_KEY,
            default = PREF_VERIFY_DEFAULT,
            title = "Check streams before playing",
            summary = "Sends a 2-byte request per format and hides links YouTube answers with 403. " +
                "Adds about a second when loading an episode.",
        )
        screen.getSwitchPreference(
            key = PREF_SUBTITLES_KEY,
            default = PREF_SUBTITLES_DEFAULT,
            title = "Fetch subtitles/CC",
            summary = "Load YouTube caption tracks when available.",
        )
        screen.getListPreference(
            key = PREF_SUBTITLE_LANG_KEY,
            default = PREF_SUBTITLE_LANG_DEFAULT,
            title = "Subtitle language",
            summary = "%s",
            entries = PREF_SUBTITLE_LANG_ENTRIES.toList(),
            entryValues = PREF_SUBTITLE_LANG_VALUES.toList(),
        )
        screen.getListPreference(
            key = PREF_AUDIO_LANG_KEY,
            default = PREF_AUDIO_LANG_DEFAULT,
            title = "Preferred audio language",
            summary = "%s",
            entries = PREF_AUDIO_LANG_ENTRIES.toList(),
            entryValues = PREF_AUDIO_LANG_VALUES.toList(),
        )
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        TypeFilter(),
        SortFilter(),
    )

    private class TypeFilter :
        AnimeFilter.Select<String>(
            "Search type",
            arrayOf("Videos", "Channels", "Playlists"),
        )

    private class SortFilter :
        AnimeFilter.Select<String>(
            "Sort by",
            arrayOf("Relevance", "Upload date", "View count", "Rating"),
        )

    /** Some YouTube payloads use protocol-relative URLs, which Coil will not load. */
    private fun String?.asAbsoluteUrl(): String = when {
        this == null -> ""
        startsWith("//") -> "https:$this"
        else -> this
    }

    private fun InnerTube.Entry.toSAnime(): SAnime {
        val prefix = when (kind) {
            InnerTube.Kind.PLAYLIST -> "YouTube playlist"
            InnerTube.Kind.CHANNEL -> "YouTube channel"
            InnerTube.Kind.VIDEO -> "YouTube video"
        }
        val summary = buildString {
            append(prefix)
            if (description.isNotBlank()) append(" · ").append(description)
            if (author.isNotBlank()) append("\n").append(author)
        }
        return SAnime.create().apply {
            url = InnerTube.Target(kind, id).encode()
            title = this@toSAnime.title
            author = this@toSAnime.author
            thumbnail_url = this@toSAnime.thumbnail.asAbsoluteUrl()
            description = summary
            genre = "YouTube"
        }
    }

    companion object {
        private const val TAG = "INbox"

        private const val MAX_PAGES = 5
        private const val MAX_EPISODES = 200

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

        private const val PREF_VERIFY_KEY = "pref_verify"
        private const val PREF_VERIFY_DEFAULT = true

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
