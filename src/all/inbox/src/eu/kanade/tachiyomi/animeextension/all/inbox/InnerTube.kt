package eu.kanade.tachiyomi.animeextension.all.inbox

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

internal class InnerTube(
    private val client: OkHttpClient,
    private val apiHeaders: (ClientSpec) -> Headers,
) {
    companion object {
        const val BASE_URL = "https://www.youtube.com"
        private const val API_URL = "$BASE_URL/youtubei/v1"
        private const val OEMBED_URL = "$BASE_URL/oembed"

        const val HEADER_VIDEO_ID = "X-Inbox-Video-Id"
        const val HEADER_CLIENT = "X-Inbox-Client"

        private val JSON = Json { ignoreUnknownKeys = true }
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

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

        val PLAYER_CLIENTS = listOf(ANDROID, ANDROID_VR, IOS)
    }

    fun searchRequest(query: String): Request {
        val body = buildJsonObject {
            put("context", context(WEB))
            put("query", query.ifBlank { "trending" })
            put("params", "EgIQAQ%3D%3D")
        }
        return Request.Builder()
            .url("$API_URL/search?prettyPrint=false")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .headers(apiHeaders(WEB))
            .build()
    }

    fun playerRequest(videoId: String, spec: ClientSpec): Request {
        val body = buildJsonObject {
            put("context", context(spec))
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }
        return Request.Builder()
            .url("$API_URL/player?prettyPrint=false")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .headers(apiHeaders(spec))
            .header(HEADER_VIDEO_ID, videoId)
            .header(HEADER_CLIENT, spec.name)
            .build()
    }

    fun oembedRequest(videoId: String): Request {
        val url = "$OEMBED_URL?url=${"https://www.youtube.com/watch?v=$videoId".encodeUrl()}&format=json"
        return Request.Builder().url(url).headers(apiHeaders(WEB)).get().build()
    }

    fun resolvePlayer(
        response: Response,
        includeDash: Boolean,
        includeMuxed: Boolean,
        fetchSubtitles: Boolean,
        preferredAudioLang: String,
        preferredQuality: String,
    ): List<Video> {
        val videoId = response.request.header(HEADER_VIDEO_ID)
            ?: extractVideoId(response.request.url.toString())
            ?: throw Exception("INbox: request without a video id")
        val first = PLAYER_CLIENTS.firstOrNull { it.name == response.request.header(HEADER_CLIENT) }
            ?: PLAYER_CLIENTS.first()
        val order = listOf(first) + PLAYER_CLIENTS.filter { it.name != first.name }

        var body = response.use { it.body?.string() }.orEmpty()

        for ((index, spec) in order.withIndex()) {
            if (index > 0) {
                body = try {
                    client.newCall(playerRequest(videoId, spec)).execute().use { it.body?.string() }.orEmpty()
                } catch (e: Exception) {
                    continue
                }
            }
            if (body.isBlank()) {
                continue
            }
            try {
                val videos = parsePlayer(
                    JSON.parseToJsonElement(body).jsonObject,
                    spec,
                    includeDash,
                    includeMuxed,
                    fetchSubtitles,
                    preferredAudioLang,
                    preferredQuality,
                )
                if (videos.isNotEmpty()) return videos
            } catch (e: Exception) {
            }
        }

        throw Exception("INbox: no playable stream")
    }

    private fun parsePlayer(
        json: JsonObject,
        spec: ClientSpec,
        includeDash: Boolean,
        includeMuxed: Boolean,
        fetchSubtitles: Boolean,
        preferredAudioLang: String,
        preferredQuality: String,
    ): List<Video> {
        val status = json.obj("playabilityStatus")
        val state = status?.str("status") ?: "UNKNOWN"
        if (state != "OK") {
            val reason = status?.str("reason").orEmpty()
            throw Exception("$state${if (reason.isNotBlank()) ": $reason" else ""}")
        }

        val streamingData = json.obj("streamingData") ?: throw Exception("missing streamingData")
        val streamHeaders = streamHeaders(spec)
        val subtitles = if (fetchSubtitles) extractSubtitles(json) else emptyList()
        val videos = mutableListOf<Video>()

        if (includeMuxed) {
            val muxedList = streamingData.arr("formats").orEmpty().mapNotNull { element ->
                val format = element as? JsonObject ?: return@mapNotNull null
                val url = format.str("url") ?: return@mapNotNull null
                val mime = format.str("mimeType").orEmpty()
                if (!mime.startsWith("video/")) return@mapNotNull null
                val height = parseInt(format.str("height")) ?: 0
                val bitrate = parseInt(format.str("bitrate")) ?: 0
                val label = format.str("qualityLabel") ?: if (height > 0) "${height}p" else "Muxed"
                MuxedRep(height, bitrate, label, url)
            }.sortedWith(compareByDescending<MuxedRep> { it.height }.thenByDescending { it.bitrate })
            for (rep in muxedList) {
                if (!passesQuality(rep.height, preferredQuality)) continue
                videos += Video(rep.url, "Muxed ${rep.label} (AAC)", rep.url, streamHeaders, subtitleTracks = subtitles)
            }
        }

        if (includeDash) {
            val adaptive = streamingData.arr("adaptiveFormats").orEmpty()
            val audios = adaptive.mapNotNull { element ->
                val format = element as? JsonObject ?: return@mapNotNull null
                val url = format.str("url") ?: return@mapNotNull null
                if (!format.str("mimeType").orEmpty().startsWith("audio/mp4")) return@mapNotNull null
                val bitrate = parseInt(format.str("bitrate")) ?: 0
                val track = format.obj("audioTrack")
                val lang = track?.str("languageCode") ?: ""
                val display = track?.str("displayName")?.takeIf { it.isNotBlank() }
                    ?: track?.str("audioIsDefault")?.let { if (it == "true") "Original" else "Audio" }
                    ?: "Audio"
                AudioRep(url, bitrate, lang.lowercase(Locale.US), display)
            }
            val filteredAudios = filterAudios(audios, preferredAudioLang)
            if (filteredAudios.isNotEmpty()) {
                val videoReps = adaptive.mapNotNull { element ->
                    val format = element as? JsonObject ?: return@mapNotNull null
                    val url = format.str("url") ?: return@mapNotNull null
                    val mime = format.str("mimeType").orEmpty()
                    if (!mime.startsWith("video/mp4") || !mime.contains("avc1")) return@mapNotNull null
                    val height = parseInt(format.str("height")) ?: return@mapNotNull null
                    if (height > 1080) return@mapNotNull null
                    VideoRep(url, height, parseInt(format.str("bitrate")) ?: 0)
                }.distinctBy { it.height }.sortedByDescending { it.height }
                for (rep in videoReps) {
                    if (!passesQuality(rep.height, preferredQuality)) continue
                    for (audio in filteredAudios) {
                        videos += Video(
                            rep.url,
                            "DASH ${rep.height}p • ${audio.display}",
                            rep.url,
                            streamHeaders,
                            audioTracks = listOf(Track(audio.url, audio.display)),
                            subtitleTracks = subtitles,
                        )
                    }
                }
            }
        }

        return videos.distinctBy { it.videoUrl to it.quality }
    }

    private fun extractSubtitles(json: JsonObject): List<Track> {
        val tracks = json.obj("captions")
            ?.obj("playerCaptionsTracklistRenderer")
            ?.arr("captionTracks") ?: return emptyList()
        return tracks.mapNotNull { element ->
            val track = element as? JsonObject ?: return@mapNotNull null
            val baseUrl = track.str("baseUrl") ?: return@mapNotNull null
            val name = track.obj("name")?.arr("runs")?.joinToString("") { (it as? JsonObject)?.str("text").orEmpty() }
                ?.takeIf { it.isNotBlank() }
                ?: track.str("languageCode") ?: "Subtitles"
            val url = if (baseUrl.contains("&fmt=") || baseUrl.contains("?fmt=")) baseUrl else "$baseUrl&fmt=vtt"
            Track(url, name)
        }.distinctBy { it.lang to it.url }
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
        if (preferredAudioLang == "all") return audios.sortedByDescending { it.bitrate }
        val filtered = audios.filter { audio ->
            when (preferredAudioLang) {
                "es" -> audio.lang.startsWith("es")
                "en" -> audio.lang.startsWith("en")
                "ja" -> audio.lang.startsWith("ja")
                else -> true
            }
        }
        val result = if (filtered.isNotEmpty()) filtered else audios
        return result.sortedByDescending { it.bitrate }
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
        put("user", buildJsonObject { put("lockedSafetyMode", false) })
        put("request", buildJsonObject { put("useSsl", true) })
    }

    private fun streamHeaders(spec: ClientSpec): Headers = Headers.Builder()
        .add("User-Agent", spec.userAgent)
        .add("Accept", "*/*")
        .add("Accept-Language", "en-US,en;q=0.9")
        .build()

    fun parseSearch(response: Response): List<YouTubeVideo> {
        val body = response.use { it.body?.string() } ?: return emptyList()
        val root = JSON.parseToJsonElement(body)
        return collectObjects(root, "videoRenderer")
            .mapNotNull { toVideo(it) }
            .distinctBy { it.id }
    }

    private fun toVideo(renderer: JsonObject): YouTubeVideo? {
        val id = renderer.str("videoId") ?: return null
        val title = renderer.obj("title").let { titleObj ->
            titleObj?.arr("runs")?.joinToString("") { (it as? JsonObject)?.str("text").orEmpty() }
                ?.takeIf { it.isNotBlank() }
                ?: titleObj?.str("simpleText")
                ?: id
        }
        val author = renderer.obj("ownerText")?.arr("runs")?.firstOrNull()
            ?.let { it as? JsonObject }?.str("text")
            ?: renderer.obj("longBylineText")?.arr("runs")?.firstOrNull()
                ?.let { it as? JsonObject }?.str("text")
            ?: ""
        val thumbnail = renderer.obj("thumbnail")?.arr("thumbnails")?.lastOrNull()
            ?.let { it as? JsonObject }?.str("url")
            ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg"
        val duration = renderer.obj("lengthText")?.str("simpleText") ?: ""
        val published = renderer.obj("publishedTimeText")?.str("simpleText") ?: ""
        return YouTubeVideo(id, title, author, thumbnail, duration, published)
    }
}

internal data class ClientSpec(
    val id: String,
    val name: String,
    val version: String,
    val userAgent: String,
    val extra: JsonObject = JsonObject(emptyMap()),
)

internal data class YouTubeVideo(
    val id: String,
    val title: String,
    val author: String,
    val thumbnail: String,
    val duration: String,
    val published: String,
)

private data class VideoRep(val url: String, val height: Int, val bitrate: Int)
private data class MuxedRep(val height: Int, val bitrate: Int, val label: String, val url: String)
private data class AudioRep(val url: String, val bitrate: Int, val lang: String, val display: String)

internal fun extractVideoId(text: String): String? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    Regex("[?&]v=([A-Za-z0-9_-]{11})").find(trimmed)?.let { return it.groupValues[1] }
    Regex("(?:youtube\\.com/(?:watch\\?(?:[^#]*&)?v=|shorts/|embed/|live/|v/)|youtu\\.be/)([A-Za-z0-9_-]{11})")
        .find(trimmed)?.let { return it.groupValues[1] }
    if (trimmed.matches(Regex("[A-Za-z0-9_-]{11}"))) return trimmed
    return null
}

private fun collectObjects(
    root: JsonElement,
    key: String,
    out: MutableList<JsonObject> = mutableListOf(),
): List<JsonObject> {
    when (root) {
        is JsonObject -> {
            (root[key] as? JsonObject)?.let { out += it }
            root.values.forEach { collectObjects(it, key, out) }
        }
        is JsonArray -> root.forEach { collectObjects(it, key, out) }
        else -> Unit
    }
    return out
}

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content?.takeIf { it != "null" }

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

private fun parseInt(s: String?): Int? = s?.toLongOrNull()?.toInt()

private fun String.encodeUrl(): String = java.net.URLEncoder.encode(this, "UTF-8")
