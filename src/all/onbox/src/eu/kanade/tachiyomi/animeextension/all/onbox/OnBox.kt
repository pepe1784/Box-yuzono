package eu.kanade.tachiyomi.animeextension.all.onbox

import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.preference.ListPreference
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
import keiyoushi.utils.getPreferencesLazy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * ONbox: Facebook videos as an Aniyomi source.
 *
 * Facebook has no public API worth using, so this reads the JSON that the video page
 * already ships inside its HTML:
 * - `browser_native_hd_url` / `browser_native_sd_url` are plain MP4 files on the CDN
 *   (206 + `accept-ranges: bytes`, no cookie needed) and are what the player and the
 *   downloader get;
 * - `dash_manifest` (base64 XML) only shows up sometimes; when it does it can carry
 *   higher resolutions with the audio in a separate track.
 *
 * There is no catalogue without a session: search, a page's video tab and the watch
 * feed answer 400 or render on the client. So the search box is the entry point: paste
 * a video URL, a reel URL, an `fb.watch` short link or just the numeric id.
 *
 * Cookies are not handled here on purpose. `network.client` already carries the app's
 * cookie jar, which is shared with the WebView, so whatever session the WebView has
 * (guest or logged in) travels with these requests automatically.
 */
class OnBox :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "ONbox"
    override val lang = "all"
    override val id: Long = 4491778213406318901L
    override val supportsLatest = false
    override var baseUrl: String = "https://www.facebook.com"

    private val preferences by getPreferencesLazy()

    private val qualityMode: String
        get() = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT

    override val client: OkHttpClient by lazy {
        network.client.newBuilder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val pageHeaders = Headers.Builder()
        .add("User-Agent", UA)
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .add("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
        .add("Upgrade-Insecure-Requests", "1")
        // Facebook's edge answers 400 to share links without these; with them the
        // /share/r/<code>/ URL redirects to the reel and serves the whole page.
        .add("Sec-Fetch-Dest", "document")
        .add("Sec-Fetch-Mode", "navigate")
        .add("Sec-Fetch-Site", "none")
        .add("Sec-Fetch-User", "?1")
        .build()

    // The CDN links are signed for the IP that resolved them; the UA and referer keep
    // mpv and ffmpeg looking like the page that produced them.
    private val videoHeaders = Headers.Builder()
        .add("User-Agent", UA)
        .add("Referer", "$baseUrl/")
        .add("Accept", "*/*")
        .build()

    // ============================ Catalogue ============================

    // Without a session Facebook does not hand out a catalogue, so both tabs stay empty
    // instead of showing a list that breaks on the second page.
    override fun popularAnimeRequest(page: Int): Request = GET(baseUrl, pageHeaders)

    override fun popularAnimeParse(response: Response): AnimesPage = AnimesPage(emptyList<SAnime>(), false)

    override fun latestUpdatesRequest(page: Int): Request = GET(baseUrl, pageHeaders)

    override fun latestUpdatesParse(response: Response): AnimesPage = AnimesPage(emptyList<SAnime>(), false)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val text = query.trim()
        val id = extractVideoId(text)
        val url = when {
            text.startsWith("http", ignoreCase = true) -> text
            text.contains("facebook.com/") || text.contains("fb.watch/") -> "https://$text"
            id != null -> watchUrl(id)
            // Last resort: the search page needs a session, but the shared cookie jar may
            // already have one from the WebView. If it does not, the parse below says so.
            else -> "$baseUrl/search/videos/?q=" + Uri.encode(text)
        }
        return GET(url, pageHeaders)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val requested = response.request.url.toString()
        var finalUrl = requested
        var body = response.use { it.body.string() }.orEmpty()
        var id = extractVideoId(finalUrl) ?: videoIdIn(body)

        if (id == null) {
            // Some share links still come back as a bare 400; the mobile host usually
            // answers with the redirect instead of asking the user to try again.
            val alt = mobileHost(requested)
            if (alt != null) {
                val retried = fetchRaw(alt)
                if (retried != null) {
                    finalUrl = retried.first
                    body = retried.second
                    id = extractVideoId(finalUrl) ?: videoIdIn(body)
                }
            }
        }

        if (id == null) {
            throw Exception(
                "Facebook respondió ${response.code} y no devolvió ningún vídeo. " +
                    "Abre el enlace en el navegador y comprueba que el vídeo se ve sin iniciar sesión.",
            )
        }

        Log.d(TAG, "search ${response.code} -> $finalUrl")
        return AnimesPage(listOf(animeFromPage(parsePage(body), id)), false)
    }

    private fun videoIdIn(body: String): String? = Regex("\"video_id\":\"(\\d{6,})\"").find(body)?.groupValues?.get(1)

    private fun mobileHost(url: String): String? = when {
        url.contains("://www.facebook.com/") -> url.replace("://www.facebook.com/", "://m.facebook.com/")
        url.contains("://m.facebook.com/") -> null
        else -> null
    }

    /** Requests a page and returns the final URL with its body, or null on an error. */
    private fun fetchRaw(url: String): Pair<String, String>? = runCatching {
        client.newCall(GET(url, pageHeaders)).execute().use { response ->
            val body = response.body.string().orEmpty()
            if (!response.isSuccessful) {
                Log.w(TAG, "HTTP ${response.code} para $url")
                null
            } else {
                response.request.url.toString() to body
            }
        }
    }.onFailure { Log.e(TAG, "fallo pidiendo $url", it) }.getOrNull()

    // ============================= Details =============================

    override fun animeDetailsRequest(anime: SAnime): Request {
        val id = extractVideoId(anime.url) ?: anime.url
        return GET(watchUrl(id), pageHeaders)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val body = response.use { it.body.string() }.orEmpty()
        val id = extractVideoId(response.request.url.toString())
            ?: videoIdIn(body)
            ?: response.request.url.toString()
        Log.d(TAG, "details ${response.code} ${body.length}b")
        return animeFromPage(parsePage(body), id)
    }

    private fun animeFromPage(page: PageData, id: String): SAnime = SAnime.create().apply {
        url = watchUrl(id)
        title = page.title?.takeIf { it.isNotBlank() } ?: "Facebook video $id"
        author = page.author.orEmpty()
        thumbnail_url = page.thumbnail
        description = buildString {
            page.durationMs?.let { append("Duración: ").append(it / 1000).append(" s\n") }
            append("Facebook · ").append(watchUrl(id))
        }
        genre = "Facebook"
    }

    // ============================ Episodes =============================

    // A video is a series with a single episode. The name has to differ from the series
    // title: the app strips the title from the episode name on every sync, and a name
    // left empty ends up as "(invalid)" in every download file.
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = withContext(Dispatchers.IO) {
        val id = extractVideoId(anime.url) ?: anime.url
        val title = anime.title.orEmpty()
        val author = anime.author.orEmpty().trim()
        val name = when {
            author.isEmpty() -> id
            author.equals(title, ignoreCase = true) -> id
            else -> author
        }
        listOf(
            SEpisode.create().apply {
                url = watchUrl(id)
                this.name = name
                episode_number = 1F
            },
        )
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    // ============================== Video ==============================

    override fun videoListRequest(episode: SEpisode): Request {
        val id = extractVideoId(episode.url) ?: episode.url
        return GET(watchUrl(id), pageHeaders)
    }

    override fun videoListParse(response: Response): List<Video> {
        val id = extractVideoId(response.request.url.toString())
        val body = response.use { it.body.string() }.orEmpty()
        var page = parsePage(body)

        // Some pages only serve the MP4 pair on the mobile host.
        if (page.hd == null && page.sd == null && id != null) {
            val mobile = mobileWatchUrl(id)
            val alt = fetchRaw(mobile)?.second
            if (alt != null) page = parsePage(alt)
        }

        val hd = page.hd
        val sd = page.sd?.takeIf { it != hd }
        val mode = qualityMode
        val main = when (mode) {
            "hd" -> listOfNotNull(hd ?: sd)
            "sd" -> listOfNotNull(sd ?: hd)
            else -> listOfNotNull(hd, sd)
        }

        val videos = mutableListOf<Video>()
        main.forEach { url ->
            videos += Video(url, if (url == hd) "HD" else "SD", url, videoHeaders)
        }

        // Only reachable sometimes; kept last so a DASH entry never becomes the default.
        if (mode != "sd") {
            page.dash?.let { videos += dashVideos(it) }
        }

        if (videos.isEmpty()) {
            throw Exception(
                "Facebook no dio ninguna URL para este vídeo (privado, con restricción de " +
                    "derechos o la IP está limitada). Prueba a abrirlo en el WebView.",
            )
        }
        Log.d(TAG, "video $id -> ${videos.size} entrada(s)")
        return videos
    }

    /** Decodes the base64 DASH manifest when the page has one. */
    private fun dashVideos(manifest: String): List<Video> {
        val xml = runCatching { String(Base64.decode(manifest, Base64.DEFAULT)) }.getOrNull() ?: return emptyList()
        if (!xml.contains("<Representation")) return emptyList()

        var audioUrl: String? = null
        val byHeight = linkedMapOf<Int, String>()

        Regex("<AdaptationSet([^>]*)>(.*?)</AdaptationSet>", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml)
            .forEach setLoop@{ set ->
                val setAttrs = set.groupValues[1]
                val content = set.groupValues[2]
                val setMime = attr(setAttrs, "mimeType").orEmpty()
                Regex("<Representation([^>]*)>(.*?)</Representation>", RegexOption.DOT_MATCHES_ALL)
                    .findAll(content)
                    .forEach repLoop@{ rep ->
                        val attrs = rep.groupValues[1]
                        val inner = rep.groupValues[2]
                        val base = Regex("<BaseURL>([^<]+)</BaseURL>").find(inner)?.groupValues?.get(1)
                            ?: Regex("<BaseURL>([^<]+)</BaseURL>").find(content)?.groupValues?.get(1)
                            ?: return@repLoop
                        val url = base.replace("&amp;", "&")
                        if (!url.startsWith("http")) return@repLoop

                        val codecs = attr(attrs, "codecs").orEmpty()
                        val height = attr(attrs, "height")?.toIntOrNull() ?: 0
                        val isAudio = setMime.contains("audio") ||
                            codecs.startsWith("mp4a") || codecs.startsWith("opus") || codecs.startsWith("ac-3")
                        if (isAudio) {
                            if (audioUrl == null) audioUrl = url
                        } else if (height > 0) {
                            // Higher representation wins for the same height.
                            byHeight[height] = url
                        }
                    }
            }

        if (byHeight.isEmpty()) return emptyList()
        val audio = audioUrl
        return byHeight.entries.sortedByDescending { it.key }.map { (height, url) ->
            Video(
                url,
                "${height}p (DASH)",
                url,
                videoHeaders,
                audioTracks = audio?.let { listOf(Track(it, "Original")) }.orEmpty(),
            )
        }
    }

    // ============================== Parser =============================

    private class PageData(
        val title: String?,
        val author: String?,
        val thumbnail: String?,
        val durationMs: Long?,
        val hd: String?,
        val sd: String?,
        val dash: String?,
    )

    private fun parsePage(body: String): PageData {
        val rawTitle = meta(body, "og:description").takeIf { !it.isNullOrBlank() }
            ?: jsonString(body, "title")
            ?: titleFromOg(body)
            ?: Regex("<title[^>]*>([^<]+)</title>").find(body)?.groupValues?.get(1)
        val title = rawTitle?.htmlUnescape()?.trim()?.takeIf { it.isNotBlank() && it != "Facebook" }
        val duration = Regex("\"playable_duration_in_ms\":(\\d+)").find(body)?.groupValues?.get(1)?.toLongOrNull()
        return PageData(
            title = title,
            author = ownerOf(body),
            thumbnail = imageUri(body) ?: meta(body, "og:image")?.htmlUnescape(),
            durationMs = duration,
            hd = jsonString(body, "browser_native_hd_url"),
            sd = jsonString(body, "browser_native_sd_url"),
            dash = jsonString(body, "dash_manifest"),
        )
    }

    /** The video's own image; `og:image` is gone from Facebook's video pages. */
    private fun imageUri(body: String): String? {
        val regex = Regex("\"image\":\\{\"uri\":\"((?:[^\"\\\\]|\\\\.)*)\"")
        return regex.find(body)?.groupValues?.get(1)?.let(::unescapeJson)?.takeIf { it.startsWith("http") }
    }

    private fun ownerOf(body: String): String? {
        val candidates = listOf(
            Regex("\"owner\":\\{[^{}]{0,400}?\"name\":\"((?:[^\"\\\\]|\\\\.)*)\""),
            Regex("\"owner_name\":\"((?:[^\"\\\\]|\\\\.)*)\""),
            Regex("\"page_name\":\"((?:[^\"\\\\]|\\\\.)*)\""),
        )
        val found = candidates.firstNotNullOfOrNull { regex ->
            regex.find(body)?.groupValues?.get(1)?.let(::unescapeJson)?.trim()?.takeIf { it.isNotEmpty() }
        }
        if (found != null) return found

        // Reels have no "owner" object in the served JSON; the share title ends with it:
        // "174 mil reproducciones · 157 reacciones | <título> | <autor>".
        val parts = meta(body, "og:title")?.htmlUnescape()?.split(" | ")
            ?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        return parts.lastOrNull()?.takeIf { parts.size >= 2 && it.isNotEmpty() }
    }

    private fun titleFromOg(body: String): String? {
        val parts = meta(body, "og:title")?.htmlUnescape()?.split(" | ")
            ?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        return when {
            parts.size >= 3 -> parts[parts.size - 2]
            parts.size == 2 -> parts.first()
            else -> parts.firstOrNull()
        }?.takeIf { it.isNotEmpty() }
    }

    /** Reads a JSON string field out of the page without pulling in a JSON parser. */
    private fun jsonString(body: String, name: String): String? {
        val regex = Regex("\"" + Regex.escape(name) + "\":\"((?:[^\"\\\\]|\\\\.)*)\"")
        return regex.find(body)?.groupValues?.get(1)?.let(::unescapeJson)?.takeIf { it.isNotBlank() }
    }

    private fun unescapeJson(value: String): String {
        var text = value
            .replace("\\/", "/")
            .replace("\\u0025", "%")
            .replace("\\u0026", "&")
            .replace("\\u003D", "=")
            .replace("\\u003d", "=")
            .replace("\\\"", "\"")
        Regex("\\\\u([0-9a-fA-F]{4})").findAll(text).forEach { match ->
            val ch = match.groupValues[1].toInt(16).toChar()
            text = text.replace(match.value, ch.toString())
        }
        return text
    }

    private fun meta(body: String, name: String): String? {
        val tag = Regex("<meta[^>]+(?:property|name)=[\"']" + Regex.escape(name) + "[\"'][^>]*>")
            .find(body)?.value
            ?: Regex(
                "<meta[^>]+content=[\"'][^\"']*[\"'][^>]+(?:property|name)=[\"']" +
                    Regex.escape(name) + "[\"'][^>]*>",
            ).find(body)?.value
        return tag?.let { Regex("content=[\"']([^\"']*)[\"']").find(it)?.groupValues?.get(1) }
    }

    private fun String.htmlUnescape(): String {
        var text = replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
        Regex("&#(x?)([0-9a-fA-F]+);").findAll(text).forEach { match ->
            val hex = match.groupValues[1].isNotEmpty()
            val code = runCatching {
                if (hex) match.groupValues[2].toInt(16) else match.groupValues[2].toInt()
            }.getOrNull() ?: return@forEach
            if (code in 1..0x10FFFF) {
                text = text.replace(match.value, String(Character.toChars(code)))
            }
        }
        return text
    }

    private fun attr(attributes: String, name: String): String? = Regex(Regex.escape(name) + "=\"([^\"]*)\"").find(attributes)?.groupValues?.get(1)

    private fun watchUrl(id: String): String = "$baseUrl/watch/?v=$id"

    private fun mobileWatchUrl(id: String): String = "https://m.facebook.com/watch/?v=$id"

    private fun extractVideoId(raw: String): String? {
        val text = raw.trim()
        if (text.matches(Regex("\\d{6,}"))) return text
        Regex("[?&]v=(\\d{6,})").find(text)?.let { return it.groupValues[1] }
        Regex("/(?:videos|reel|watch)/(?:[^/?#]+/)*?(\\d{6,})").find(text)?.let { return it.groupValues[1] }
        Regex("(\\d{6,})").find(text)?.let { return it.groupValues[1] }
        return null
    }

    // ============================ Preferences ==========================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addPreference(
            ListPreference(screen.context).apply {
                key = PREF_QUALITY_KEY
                title = "Calidades que se ofrecen"
                entries = PREF_QUALITY_ENTRIES
                entryValues = PREF_QUALITY_VALUES
                setDefaultValue(PREF_QUALITY_DEFAULT)
                summary = "%s"
            },
        )
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList()

    companion object {
        private const val TAG = "ONbox"
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "both"
        private val PREF_QUALITY_ENTRIES = arrayOf("HD y SD", "Solo HD", "Solo SD")
        private val PREF_QUALITY_VALUES = arrayOf("both", "hd", "sd")
    }
}
