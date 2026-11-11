package eu.kanade.tachiyomi.animeextension.all.onbox

import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
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
import keiyoushi.utils.getPreferencesLazy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * ONbox: Facebook videos as an Aniyomi source.
 *
 * Facebook has no public API and its logged-out pages are mostly rendered on the client.
 * The exception is the video feed that `/search/videos/?q=<tema>` redirects to
 * (`/watch/explore/<tema>/`), plus the reel, page and watch pages: they are served with
 * the whole catalogue in the HTML, so this source reads them directly:
 *
 * - Each card is a `"id":"UzpfST…"` story; decoding that base64 gives the video id
 *   (`S:_I<page>:VK:<video>`), and the same block carries the text, the author name and
 *   the `preferred_thumbnail`.
 * - `browser_native_hd_url` / `browser_native_sd_url` are plain MP4 files on the CDN
 *   (`206` + `accept-ranges: bytes`, no cookie needed).
 * - `dash_manifest` (base64 XML) only shows up sometimes; when it does it can carry
 *   higher resolutions with the audio in a separate track.
 *
 * Those pages answer `400` unless the request looks like a browser navigation, hence the
 * `Sec-Fetch-*` headers below.
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

    private val popularTopic: String
        get() = preferences.getString(PREF_TOPIC_KEY, PREF_TOPIC_DEFAULT)?.trim()
            ?.takeIf { it.isNotEmpty() } ?: PREF_TOPIC_DEFAULT

    private val checkLinks: Boolean
        get() = preferences.getBoolean(PREF_CHECK_KEY, true)

    override val client: OkHttpClient by lazy {
        network.client.newBuilder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    // Short leash: this one only asks for the first two bytes of the video.
    private val probeClient: OkHttpClient by lazy {
        client.newBuilder()
            .callTimeout(8, TimeUnit.SECONDS)
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
        // Facebook decides between the desktop page and the mobile/login wall with the UA
        // and these client hints. Sending all of them avoids the mobile variant, where the
        // catalogue disappears and `browser_native_*_url` is missing.
        .add("Sec-CH-UA", "\"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\", \"Google Chrome\";v=\"131\"")
        .add("Sec-CH-UA-Mobile", "?0")
        .add("Sec-CH-UA-Platform", "\"Windows\"")
        .build()

    // The CDN links are signed for the IP that resolved them; the UA and referer keep
    // mpv and ffmpeg looking like the page that produced them.
    private val videoHeaders = Headers.Builder()
        .add("User-Agent", UA)
        .add("Referer", "$baseUrl/")
        .add("Accept", "*/*")
        .build()

    // ============================ Catalogue ============================

    // One topic per page: Facebook's explore feed is server rendered and gives 5-10
    // videos per topic, so the tab walks down this list instead of faking pagination.
    override fun popularAnimeRequest(page: Int): Request {
        val topics = popularTopics()
        val topic = topics[((page - 1).coerceAtLeast(0)) % topics.size]
        return GET(exploreUrl(topic), pageHeaders)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        var code = response.code
        var finalUrl = response.request.url.toString()
        var body = response.use { it.body.string() }.orEmpty()
        var cards = parseFeed(body)

        // A guest without cookies is a different visitor for Facebook. When the shared jar
        // carries a broken/checkpointed session, the same URL often comes back full.
        if (cards.size < 3) {
            fetchPage(finalUrl, withoutCookies = true)?.let { guest ->
                val guestCards = parseFeed(guest.body)
                if (guestCards.size > cards.size) {
                    Log.d(TAG, "populares sin cookies: ${guestCards.size} > ${cards.size}")
                    cards = guestCards
                    body = guest.body
                    code = guest.code
                    finalUrl = guest.url
                }
            }
        }

        val topics = popularTopics()
        val topic = topicFromUrl(finalUrl)
        val index = topics.indexOfFirst { it.equals(topic, ignoreCase = true) }
        val hasNext = cards.isNotEmpty() && index >= 0 && index < topics.lastIndex
        Log.d(TAG, "populares '${topic ?: "?"}' -> ${cards.size} tarjetas, más=$hasNext")
        val entries = cards.map { animeFromCard(it) }.toMutableList()
        diagnosticAnime(finalUrl, code, body, cards.size)?.let { entries.add(0, it) }
        return AnimesPage(entries, hasNext)
    }

    override fun latestUpdatesRequest(page: Int): Request = GET(baseUrl, pageHeaders)

    override fun latestUpdatesParse(response: Response): AnimesPage = AnimesPage(emptyList<SAnime>(), false)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val type = filters.filterIsInstance<TypeFilter>().firstOrNull()?.state ?: 0
        val extra = filters.filterIsInstance<ExtraFilter>().firstOrNull()?.state?.trim().orEmpty()
        val text = query.trim()
        val hint = extra.ifBlank { text }

        val url = when {
            // A link or a bare id always wins over the filters.
            isVideoLink(text) -> text
            type == TYPE_HASHTAG -> exploreUrl(hint.removePrefix("#").removePrefix("%23").trim())
            type == TYPE_PAGE -> pageUrl(hint)
            type == TYPE_GROUP -> groupUrl(hint)
            type == TYPE_LINK -> directUrl(hint)
            text.isBlank() -> exploreUrl(popularTopic)
            else -> exploreUrl(text)
        }
        val direct = isVideoLink(text) || type == TYPE_LINK
        Log.d(TAG, "search tipo=$type texto='$text' -> $url (directo=$direct)")
        return GET(url + if (direct) "#$DIRECT" else "", pageHeaders)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val requested = response.request.url.toString().substringBefore("#")
        val direct = wasDirect(response)
        var finalUrl = response.request.url.toString()
        var code = response.code
        var body = response.use { it.body.string() }.orEmpty()
        Log.d(TAG, "search ${response.code} ${body.length}b directo=$direct -> ${finalUrl.take(120)}")

        if (direct) {
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
                    "Facebook respondió $code y no devolvió ningún vídeo. " +
                        "Abre el enlace en el navegador y comprueba que el vídeo se ve sin iniciar sesión.",
                )
            }
            return AnimesPage(listOf(animeFromPage(parsePage(body), id)), false)
        }

        var cards = parseFeed(body)

        // Fewer than three cards means Facebook answered with another variant (login wall,
        // mobile page) or the shared cookie jar has a broken session. Retrying the same URL
        // as a cookie-less guest usually brings the whole feed back.
        if (cards.size < 3) {
            fetchPage(finalUrl, withoutCookies = true)?.let { guest ->
                val guestCards = parseFeed(guest.body)
                if (guestCards.size > cards.size) {
                    Log.d(TAG, "search sin cookies: ${guestCards.size} > ${cards.size}")
                    cards = guestCards
                    body = guest.body
                    code = guest.code
                    finalUrl = guest.url
                }
            }
        }

        if (cards.isEmpty()) {
            // A share link that redirected to a single video, or a page with one video.
            val id = extractVideoId(finalUrl) ?: videoIdIn(body)
            if (id != null) return AnimesPage(listOf(animeFromPage(parsePage(body), id)), false)
        }

        if (cards.isEmpty()) {
            val wall = loginWall(body)
            val htmlTitle = pageHtmlTitle(body)
            throw Exception(
                buildString {
                    append("Facebook no devolvió resultados (HTTP ").append(code).append(").\n")
                    append("Título de la página: ").append(htmlTitle ?: "(sin título)").append('\n')
                    if (wall) {
                        append("Facebook sirvió la pantalla de inicio de sesión en lugar del catálogo.\n")
                    }
                    append("Prueba con otro término, o pega el enlace del vídeo en la búsqueda. ")
                    append("Los grupos privados necesitan iniciar sesión en el WebView.")
                },
            )
        }
        val entries = cards.map { animeFromCard(it) }.toMutableList()
        diagnosticAnime(finalUrl, code, body, cards.size)?.let { entries.add(0, it) }
        return AnimesPage(entries, false)
    }

    private fun wasDirect(response: Response): Boolean {
        var current: Response? = response
        while (current != null) {
            if (current.request.url.fragment == DIRECT) return true
            current = current.priorResponse
        }
        return false
    }

    private fun videoIdIn(body: String): String? = Regex("\"video_id\":\"(\\d{6,})\"").find(body)?.groupValues?.get(1)

    private fun mobileHost(url: String): String? = when {
        url.contains("://www.facebook.com/") -> url.replace("://www.facebook.com/", "://m.facebook.com/")
        url.contains("://m.facebook.com/") -> null
        else -> null
    }

    /** A guest without cookies: Facebook treats it as a different, clean visitor. */
    private val guestClient: OkHttpClient by lazy {
        client.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()
    }

    private class Fetched(val url: String, val code: Int, val body: String)

    private fun fetchPage(url: String, withoutCookies: Boolean = false): Fetched? = runCatching {
        val http = if (withoutCookies) guestClient else client
        http.newCall(GET(url, pageHeaders)).execute().use { response ->
            Fetched(response.request.url.toString(), response.code, response.body.string().orEmpty())
        }
    }.onFailure { Log.e(TAG, "fallo pidiendo $url", it) }.getOrNull()

    /** Requests a page and returns the final URL with its body, or null on an error. */
    private fun fetchRaw(url: String): Pair<String, String>? {
        val page = fetchPage(url) ?: return null
        if (page.code !in 200..299) {
            Log.w(TAG, "HTTP ${page.code} para $url")
            return null
        }
        return page.url to page.body
    }

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

    private fun animeFromCard(card: FeedCard): SAnime = SAnime.create().apply {
        url = watchUrl(card.id)
        title = card.title?.takeIf { it.isNotBlank() } ?: "Vídeo de Facebook"
        author = card.author.orEmpty()
        thumbnail_url = card.thumbnail
        genre = "Facebook"
        description = "Facebook · " + watchUrl(card.id)
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
        val sources = mutableListOf<String>()
        if (page.hd != null || page.sd != null || page.dash != null) sources += "watch"

        // The watch page is the rich one, but it is also the one Facebook turns into a
        // login wall, and it usually has no `dash_manifest`. The embed page is tiny
        // (~140 KB), built to be watched logged out, carries the same MP4 pair in
        // `hd_src`/`sd_src` and does include the DASH manifest.
        if (id != null && (page.hd == null || page.dash == null)) {
            fetchRaw(embedUrl(id))?.let {
                val other = parsePage(it.second)
                if (other.hd != null || other.sd != null || other.dash != null) {
                    page = mergePages(page, other)
                    sources += "embed"
                }
            }
        }
        if (id != null && page.hd == null && page.sd == null) {
            fetchRaw(mobileWatchUrl(id))?.let {
                val other = parsePage(it.second)
                if (other.hd != null || other.sd != null) {
                    page = mergePages(page, other)
                    sources += "m.facebook"
                }
            }
        }
        if (id != null && page.hd == null && page.sd == null) {
            fetchRaw("$baseUrl/reel/$id/")?.let {
                val other = parsePage(it.second)
                if (other.hd != null || other.sd != null) {
                    page = mergePages(page, other)
                    sources += "reel"
                }
            }
        }
        if (id != null && page.hd == null && page.sd == null) {
            fetchRaw(pluginsUrl(id))?.let {
                val other = parsePage(it.second)
                if (other.hd != null || other.sd != null) {
                    page = mergePages(page, other)
                    sources += "plugins"
                }
            }
        }

        val hd = page.hd?.let(::absoluteVideoUrl)
        val sd = page.sd?.let(::absoluteVideoUrl)?.takeIf { it != hd }
        val mode = qualityMode
        val main = when (mode) {
            "hd" -> listOfNotNull(hd ?: sd)
            "sd" -> listOfNotNull(sd ?: hd)
            else -> listOfNotNull(hd, sd)
        }

        // A failed probe never drops a link any more: on a slow mobile connection, or with
        // a DNS/adblock that mangles the CDN host, the probe lies and we would show
        // "no video" for a file that plays fine. The probe only decorates the label.
        val videos = mutableListOf<Video>()
        main.forEach { url ->
            val label = if (url == hd) "HD" else "SD"
            val problem = if (checkLinks) probeProblem(url) else null
            val quality = if (problem == null) label else "$label · sin respuesta"
            if (problem != null) Log.w(TAG, "enlace $label no responde: $problem")
            videos += Video(url, quality, url, videoHeaders)
        }

        // Only reachable sometimes; kept last so a DASH entry never becomes the default.
        if (mode != "sd") {
            page.dash?.let { videos += dashVideos(it) }
        }

        if (videos.isEmpty()) {
            val tried = if (sources.isEmpty()) "ninguna" else sources.joinToString(", ")
            throw Exception(
                buildString {
                    append("Facebook no dio ninguna URL reproducible para este vídeo.\n")
                    append("Páginas con enlace: ").append(tried).append('\n')
                    append("Respuesta: HTTP ").append(response.code).append(", ")
                    append(body.length / 1024).append(" KB\n")
                    append("Título: ").append(pageHtmlTitle(body) ?: "(sin título)").append('\n')
                    if (loginWall(body)) {
                        append("Facebook devolvió la pantalla de inicio de sesión: abre el vídeo en el ")
                        append("WebView, inicia sesión y vuelve a intentarlo.\n")
                    }
                    append("Suele ser un vídeo borrado, privado o con restricción de derechos.")
                },
            )
        }
        Log.d(TAG, "video $id -> ${videos.size} entrada(s) desde ${sources.joinToString(",")}")
        return videos
    }

    /** Keeps the fields the first page did not have (embed has URLs but no title). */
    private fun mergePages(old: PageData, new: PageData): PageData = PageData(
        title = old.title ?: new.title,
        author = old.author ?: new.author,
        thumbnail = old.thumbnail ?: new.thumbnail,
        durationMs = old.durationMs ?: new.durationMs,
        hd = old.hd ?: new.hd,
        sd = old.sd ?: new.sd,
        dash = old.dash ?: new.dash,
    )

    private fun embedUrl(id: String): String = "$baseUrl/video/embed?video_id=$id"

    private fun pluginsUrl(id: String): String = "$baseUrl/plugins/video.php?href=" + Uri.encode("$baseUrl/watch/?v=$id") + "&show_text=false"

    /** Asks the CDN for the first two bytes. Returns the problem, or null when it works. */
    private fun probeProblem(url: String): String? = try {
        val request = Request.Builder()
            .url(url)
            .headers(videoHeaders)
            .header("Range", "bytes=0-1")
            .build()
        probeClient.newCall(request).execute().use { response ->
            if (response.isSuccessful) null else "HTTP ${response.code}"
        }
    } catch (e: Exception) {
        e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
    }

    /**
     * Turns what the page gives into something the player can open. Facebook sometimes puts
     * a relative path there (`/video_redirect/?src=…`); handing that to ExoPlayer/mpv is
     * exactly the "unknown scheme" error, so it is resolved against the site.
     */
    private fun absoluteVideoUrl(raw: String): String? {
        var url = raw.replace("&amp;", "&").trim()
        if (url.startsWith("//")) {
            url = "https:$url"
        } else if (url.startsWith("/")) {
            url = "https://www.facebook.com$url"
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            Log.w(TAG, "URL descartada por no ser absoluta: ${url.take(60)}")
            return null
        }
        return cleanVideoUrl(url)
    }

    /** `bytestart`/`byteend` turn the URL into a fragment; dropping them gives the file. */
    private fun cleanVideoUrl(raw: String): String {
        var url = raw.replace("&amp;", "&").substringBefore('#')
        if (url.contains("bytestart=") || url.contains("byteend=")) {
            url = Regex("([?&])(bytestart|byteend)=[^&]*").replace(url, "$1")
            url = url.replace("?&", "?").replace("&&", "&").trimEnd('&', '?')
        }
        return url
    }

    /**
     * Decodes the DASH manifest when the page has one. Facebook ships it as XML inside the
     * JSON (escaped with `\u003C`), not as base64; the base64 branch is kept for the pages
     * that still send it encoded.
     */
    private fun dashVideos(manifest: String): List<Video> {
        val trimmed = manifest.trimStart()
        val xml = if (trimmed.startsWith("<")) {
            manifest
        } else {
            runCatching { String(Base64.decode(manifest, Base64.DEFAULT)) }.getOrNull() ?: return emptyList()
        }
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

    private class FeedCard(
        val id: String,
        val title: String?,
        val author: String?,
        val thumbnail: String?,
    )

    /**
     * Reads the video cards of a feed page. Every story carries a base64 id
     * (`S:_I<page>:VK:<video>`), and the chunk up to the next story holds its text,
     * author and thumbnail, so no extra request per card is needed.
     */
    private fun parseFeed(body: String): List<FeedCard> {
        val storyRegex = Regex("\"id\":\"(UzpfST[^\"]{10,200})\"")
        val hits = mutableListOf<Pair<String, Int>>()
        val seenStory = HashSet<String>()
        for (match in storyRegex.findAll(body)) {
            val raw = match.groupValues[1]
            if (seenStory.add(raw)) hits += raw to match.range.first
        }
        if (hits.isEmpty()) return emptyList()

        val cards = mutableListOf<FeedCard>()
        val seenVideo = HashSet<String>()
        hits.forEachIndexed { index, (raw, start) ->
            val end = hits.getOrNull(index + 1)?.second ?: minOf(body.length, start + MAX_STORY)
            val block = body.substring(start, end)
            val decoded = runCatching { String(Base64.decode(raw, Base64.DEFAULT)) }.getOrNull() ?: return@forEachIndexed
            val id = Regex(":VK:(\\d{6,})").find(decoded)?.groupValues?.get(1) ?: return@forEachIndexed
            if (!seenVideo.add(id)) return@forEachIndexed

            val title = Regex("\"message\":\\{\"text\":\"((?:[^\"\\\\]|\\\\.)*)\"")
                .find(block)?.groupValues?.get(1)?.let(::unescapeJson)?.cleanText()
            val author = Regex("\"name\":\"((?:[^\"\\\\]|\\\\.){0,80})\"")
                .find(block)?.groupValues?.get(1)?.let(::unescapeJson)?.cleanText()
            val thumbnail = Regex("\"preferred_thumbnail\":\\{\"image\":\\{\"uri\":\"((?:[^\"\\\\]|\\\\.)*)\"")
                .find(block)?.groupValues?.get(1)?.let(::unescapeJson)?.takeIf { it.startsWith("http") }
                ?: Regex("\"image\":\\{\"uri\":\"((?:[^\"\\\\]|\\\\.)*)\"")
                    .find(block)?.groupValues?.get(1)?.let(::unescapeJson)
                    ?.takeIf { it.startsWith("http") }

            cards += FeedCard(id, title, author, thumbnail)
        }
        return cards
    }

    /** Facebook's login wall, either as a redirect target or as the whole page. */
    private fun loginWall(body: String): Boolean = body.contains("unified/login_via") ||
        body.contains("login_form") ||
        Regex(
            "<title[^>]*>[^<]*(Iniciar sesión|Log in to Facebook|Log into Facebook)",
            RegexOption.IGNORE_CASE,
        ).containsMatchIn(body)

    private fun pageHtmlTitle(body: String): String? = Regex("<title[^>]*>([^<]{1,200})</title>").find(body)?.groupValues?.get(1)
        ?.htmlUnescape()?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * First entry of a catalogue page that came back almost empty. Facebook's answer
     * changes with the device, so this is the only channel to see what it actually sent
     * without adb: the user can open the entry and read the text.
     */
    private fun diagnosticAnime(finalUrl: String, code: Int, body: String, found: Int): SAnime? {
        if (found >= 3) return null
        val storyIds = Regex("\"id\":\"(UzpfST[^\"]{10,200})\"").findAll(body).count()
        val logins = loginWall(body)
        Log.w(TAG, "catálogo pobre: $found tarjetas, HTTP $code, ${body.length}b, login=$logins")
        return SAnime.create().apply {
            url = finalUrl
            title = "Diagnóstico ONbox: $found resultado(s)"
            author = "ONbox"
            genre = "Diagnóstico"
            description = buildString {
                append("Resultados leídos: ").append(found).append('\n')
                append("HTTP: ").append(code).append('\n')
                append("Tamaño: ").append(body.length / 1024).append(" KB\n")
                append("Identificadores de vídeo: ").append(storyIds).append('\n')
                append("Título HTML: ").append(pageHtmlTitle(body) ?: "(sin título)").append('\n')
                append("Pide sesión: ").append(if (logins) "sí" else "no").append('\n')
                append("URL final: ").append(finalUrl).append('\n')
                append('\n')
                append("Si esto sale con 0-2 resultados, Facebook no está sirviendo el catálogo ")
                append("a este dispositivo. Pásame este texto y ajusto la extracción.")
            }
        }
    }

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
            hd = jsonString(body, "browser_native_hd_url")
                ?: jsonString(body, "hd_src")
                ?: jsonString(body, "playable_url_quality_hd"),
            sd = jsonString(body, "browser_native_sd_url")
                ?: jsonString(body, "sd_src")
                ?: jsonString(body, "playable_url"),
            dash = jsonString(body, "dash_manifest"),
        )
    }

    /** The video's own image; `og:image` is gone from Facebook's video pages. */
    private fun imageUri(body: String): String? {
        val regex = Regex("\"preferred_thumbnail\":\\{\"image\":\\{\"uri\":\"((?:[^\"\\\\]|\\\\.)*)\"")
        val match = regex.find(body) ?: Regex("\"image\":\\{\"uri\":\"((?:[^\"\\\\]|\\\\.)*)\"").find(body)
        return match?.groupValues?.get(1)?.let(::unescapeJson)?.takeIf { it.startsWith("http") }
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
            .replace("\\n", " ")
            .replace("\\r", " ")
            .replace("\\t", " ")
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

    private fun String.cleanText(): String? = replace(Regex("\\s+"), " ")
        .trim()
        .take(MAX_TEXT)
        .takeIf { it.isNotEmpty() }

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

    private fun exploreUrl(topic: String): String = "$baseUrl/search/videos/?q=" + Uri.encode(topic.ifBlank { PREF_TOPIC_DEFAULT })

    private fun topicFromUrl(url: String): String? = Regex("/watch/explore/([^/?#]+)").find(url)?.groupValues?.get(1)?.let { Uri.decode(it) }

    private fun isVideoLink(text: String): Boolean = when {
        text.isBlank() -> false
        text.matches(Regex("\\d{6,}")) -> true
        text.contains("/share/") || text.contains("fb.watch/") -> true
        text.contains("facebook.com/") || text.startsWith("http", ignoreCase = true) -> extractVideoId(text) != null
        else -> false
    }

    private fun directUrl(text: String): String {
        val id = extractVideoId(text)
        return when {
            id != null -> watchUrl(id)
            text.startsWith("http", ignoreCase = true) -> text
            text.contains("facebook.com/") || text.contains("fb.watch/") -> "https://$text"
            else -> watchUrl(text)
        }
    }

    private fun pageUrl(text: String): String {
        val slug = pageSlug(text)
        return if (slug != null) "$baseUrl/$slug/reels/" else exploreUrl(text)
    }

    private fun pageSlug(text: String): String? {
        if (text.isBlank()) return null
        val cleaned = text.trim().trimEnd('/')
        val fromUrl = Regex("facebook\\.com/([^/?#]+)").find(cleaned)?.groupValues?.get(1)
        if (fromUrl != null) {
            return fromUrl.takeIf { it !in RESERVED_PATHS }
        }
        return cleaned.takeIf { !it.contains(' ') && it.matches(Regex("[A-Za-z0-9._-]{2,80}")) }
    }

    private fun groupUrl(text: String): String {
        if (text.isBlank()) return exploreUrl(popularTopic)
        val fromUrl = Regex("facebook\\.com/groups/([^/?#]+)").find(text)?.groupValues?.get(1)
        val group = fromUrl ?: text.trim().takeIf { !it.contains(' ') && it.matches(Regex("[A-Za-z0-9._-]{2,80}")) }
        return if (group != null) "$baseUrl/groups/$group/" else exploreUrl(text)
    }

    private fun extractVideoId(raw: String): String? {
        val text = raw.trim()
        if (text.matches(Regex("\\d{6,}"))) return text
        Regex("[?&]video_id=(\\d{6,})").find(text)?.let { return it.groupValues[1] }
        Regex("[?&]v=(\\d{6,})").find(text)?.let { return it.groupValues[1] }
        Regex("/(?:videos|reel|watch)/(?:[^/?#]+/)*?(\\d{6,})").find(text)?.let { return it.groupValues[1] }
        Regex("(\\d{6,})").find(text)?.let { return it.groupValues[1] }
        return null
    }

    private fun popularTopics(): List<String> {
        val topics = mutableListOf(popularTopic)
        topics += DEFAULT_TOPICS
        return topics.map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
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
        screen.addPreference(
            EditTextPreference(screen.context).apply {
                key = PREF_TOPIC_KEY
                title = "Tema de Populares"
                summary = "%s"
                setDefaultValue(PREF_TOPIC_DEFAULT)
                dialogTitle = "Tema que abre la pestaña Populares"
                dialogMessage = "Se usa como búsqueda en Facebook. Las páginas siguientes recorren " +
                    "temas parecidos (tendencias, viral, comedia, música…)."
            },
        )
        screen.addPreference(
            SwitchPreferenceCompat(screen.context).apply {
                key = PREF_CHECK_KEY
                title = "Comprobar los enlaces antes de abrirlos"
                summary = "Pide los primeros bytes al CDN y avisa si el vídeo no responde. " +
                    "Desactívalo si tu red tarda en contestar."
                setDefaultValue(true)
            },
        )
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(TypeFilter(), ExtraFilter())

    private class TypeFilter :
        AnimeFilter.Select<String>(
            "Buscar por",
            arrayOf("Automático", "Hashtag", "Página o autor", "Grupo", "Enlace o ID"),
        )

    private class ExtraFilter : AnimeFilter.Text("Página, grupo o enlace (opcional)")

    companion object {
        private const val TAG = "ONbox"
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        private const val DIRECT = "onbox-direct"
        private const val MAX_STORY = 80_000
        private const val MAX_TEXT = 200

        private const val TYPE_HASHTAG = 1
        private const val TYPE_PAGE = 2
        private const val TYPE_GROUP = 3
        private const val TYPE_LINK = 4

        private val RESERVED_PATHS = setOf(
            "watch", "reel", "reels", "video", "videos", "search", "groups", "hashtag",
            "share", "login", "help", "policies", "privacy", "marketplace", "gaming",
            "events", "pages", "profile.php", "story.php", "permalink.php",
        )

        // Facebook's own topic feed, ordered so the first page is what the user asked for.
        private val DEFAULT_TOPICS = listOf(
            "tendencias", "viral", "comedia", "humor", "chistes", "memes",
            "música", "baile", "deportes", "fútbol", "cocina", "recetas", "postres",
            "animales", "gatos", "perros", "mascotas", "bebés", "familia",
            "viajes", "playa", "autos", "tecnología", "videojuegos",
            "belleza", "moda", "salud", "motivación", "curiosidades", "naturaleza", "noticias",
        )

        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "both"
        private val PREF_QUALITY_ENTRIES = arrayOf("HD y SD", "Solo HD", "Solo SD")
        private val PREF_QUALITY_VALUES = arrayOf("both", "hd", "sd")

        private const val PREF_TOPIC_KEY = "pref_popular_topic"
        private const val PREF_TOPIC_DEFAULT = "populares"
        private const val PREF_CHECK_KEY = "pref_check_links"
    }
}
