package eu.kanade.tachiyomi.animeextension.all.inbox

import android.util.Base64
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Everything the relay needs to talk SABR about one exact stream.
 *
 * SABR (`serverAbrStreamingUrl`) is the protocol the YouTube apps use to play a
 * video: the player posts a protobuf asking for a position in time and the server
 * answers with media segments pushed as UMP frames. Unlike a plain `Range:` request
 * it is not tied to the first minute of the file, and every segment states the byte
 * range it occupies inside the real mp4, so the relay can rebuild the file exactly
 * and answer the player's byte ranges with it.
 */
internal data class SabrSpec(
    val abrUrl: String,
    val ustreamerConfig: String,
    val itag: Int,
    val lastModified: String,
    val xTags: String,
    val audioTrackId: String?,
    val isAudio: Boolean,
    val height: Int,
    val clientId: Int,
    val clientVersion: String,
    val osName: String?,
    val osVersion: String?,
    val deviceMake: String?,
    val deviceModel: String?,
    val userAgent: String,
    /** Last byte of the `moov` (initRange.end): the only part served by a plain range. */
    val initEnd: Long,
    val indexStart: Long,
    val indexEnd: Long,
    val length: Long,
    val durationMs: Long,
) {
    /**
     * Last byte before the media: the `moov` plus the `sidx`. SABR segments always
     * start right after this region, so it is the only part fetched from the link.
     */
    val headEnd: Long get() = maxOf(initEnd, indexEnd)
}

/** One media segment as announced by the server. */
internal class SabrHeader(
    val headerId: Int,
    val itag: Int,
    val segmentNum: Int,
    val init: Boolean,
    val byteStart: Long,
    val length: Long,
    val startMs: Long,
    val durationMs: Long,
) {
    /** Bytes of this segment already handed to the callback. */
    var seen: Long = 0
}

internal interface SabrSink {
    fun header(header: SabrHeader)

    fun data(headerId: Int, bytes: ByteArray, offset: Int, length: Int)

    fun end(headerId: Int)
}

/**
 * Minimal SABR client: one `VideoPlaybackAbrRequest` in, UMP parts out.
 *
 * Only the parts that matter for playback are decoded (media header, media bytes,
 * media end, next request policy, stream protection, sabr error). The tricky bits
 * are the two encodings: protobuf for the payloads and YouTube's UMP framing for
 * the stream itself, which prefixes every part with two base-128 varints.
 */
internal class SabrStream(
    private val spec: SabrSpec,
    private val http: OkHttpClient,
    private val poToken: () -> String?,
) {
    /** Opaque session state the server hands back and expects next time. */
    var cookie: ByteArray? = null
        private set

    /** Last stream protection status: 0 unknown, 1 ok, 2 pending, 3 rejected. */
    var protection: Int = 0
        private set

    var lastError: String? = null
        private set

    private var requestNumber = 0

    /** Where the session is served from now; the server moves it between hosts. */
    private var abrUrl: String = spec.abrUrl

    /** Redirect named by the answer being read, if any. */
    private var pendingRedirect: String? = null

    /** Last host the server moved the session to, for the diagnostic screen. */
    var lastRedirect: String? = null
        private set

    private val config: ByteArray by lazy {
        runCatching { Base64.decode(spec.ustreamerConfig, Base64.DEFAULT) }.getOrDefault(ByteArray(0))
    }

    /**
     * Asks for media starting at [timeMs] and feeds whatever arrives to [sink].
     *
     * Returns the number of media bytes handed to the sink; zero means the server
     * refused (protection) or had nothing to give and the caller should react.
     */
    fun request(timeMs: Long, sink: SabrSink): Long {
        val body = buildBody(timeMs)
        val payload = body.toRequestBody(PROTO)
        var written = 0L
        // A SABR answer can be nothing but a redirect: the server hands another signed
        // link, on another host, and expects the same request there. It only logged it
        // before, so the session ended up with nothing to give in the middle of a video.
        repeat(MAX_REDIRECTS) {
            val url = abrUrl + (if (abrUrl.contains('?')) "&" else "?") + "rn=" + requestNumber++
            val request = Request.Builder()
                .url(url)
                .post(payload)
                .header("Accept", "application/vnd.yt-ump")
                .header("Accept-Encoding", "identity")
                .header("User-Agent", spec.userAgent)
                .build()
            pendingRedirect = null
            written = 0L
            try {
                http.newCall(request).execute().use { response ->
                    if (response.code !in 200..299) {
                        lastError = "HTTP ${response.code}"
                        return 0
                    }
                    val raw = response.body.byteStream()
                    written = readParts(BufferedInputStream(raw, 256 * 1024), sink)
                }
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
                return 0
            }
            val next = pendingRedirect
            if (next.isNullOrBlank()) {
                if (lastError?.startsWith("redirect") == true) lastError = null
                return written
            }
            abrUrl = next
            lastRedirect = next
            Log.d(TAG, "sabr sends to another host: ${next.substringBefore('?').take(70)}")
            if (written > 0) return written
        }
        return written
    }

    private fun readParts(input: InputStream, sink: SabrSink): Long {
        var written = 0L
        val headers = HashMap<Int, SabrHeader>()
        while (true) {
            val partId = readVarint(input)
            if (partId < 0) break
            val size = readVarint(input)
            if (size < 0 || size > 16 * 1024 * 1024) break
            val payload = ByteArray(size)
            if (!readFully(input, payload)) break
            when (partId) {
                PART_MEDIA_HEADER -> {
                    val header = parseHeader(payload)
                    if (header != null) {
                        headers[header.headerId] = header
                        sink.header(header)
                    }
                }
                PART_MEDIA -> {
                    if (payload.isEmpty()) continue
                    val id = payload[0].toInt() and 0xFF
                    val length = payload.size - 1
                    if (!headers.containsKey(id)) continue
                    sink.data(id, payload, 1, length)
                    written += length.toLong()
                }
                PART_MEDIA_END -> {
                    if (payload.isEmpty()) continue
                    sink.end(payload[0].toInt() and 0xFF)
                }
                PART_NEXT_REQUEST_POLICY -> parsePolicy(payload)
                PART_STREAM_PROTECTION -> protection = fieldInt(payload, 1)
                PART_SABR_ERROR -> {
                    lastError = "sabr " + fieldString(payload, 1) + " " + fieldInt(payload, 2)
                    Log.w(TAG, "server refused: $lastError")
                }
                PART_SABR_REDIRECT -> {
                    val redirect = fieldString(payload, 1)
                    pendingRedirect = redirect.ifBlank { null }
                    lastError = "redirect $redirect"
                }
            }
        }
        return written
    }

    private fun parseHeader(payload: ByteArray): SabrHeader? = try {
        val fields = Fields(payload)
        var headerId = 0
        var itag = 0
        var segmentNum = 0
        var init = false
        var byteStart = 0L
        var length = 0L
        var startMs = 0L
        var durationMs = 0L
        while (fields.next()) {
            when (fields.field) {
                1 -> headerId = fields.int()
                3 -> itag = fields.int()
                6 -> byteStart = fields.long()
                8 -> init = fields.long() != 0L
                9 -> segmentNum = fields.int()
                11 -> startMs = fields.long()
                12 -> durationMs = fields.long()
                14 -> length = fields.long()
                15 -> {
                    // timeRange is the field the server really fills in, in ticks of
                    // its own timescale; startMs/durationMs are not always present.
                    val range = Fields(fields.bytes())
                    var startTicks = 0L
                    var durationTicks = 0L
                    var timescale = 0L
                    while (range.next()) {
                        when (range.field) {
                            1 -> startTicks = range.long()
                            2 -> durationTicks = range.long()
                            3 -> timescale = range.int().toLong()
                            else -> range.skip()
                        }
                    }
                    if (timescale > 0) {
                        startMs = startTicks * 1000 / timescale
                        durationMs = durationTicks * 1000 / timescale
                    }
                }
                else -> fields.skip()
            }
        }
        SabrHeader(headerId, itag, segmentNum, init, byteStart, length, startMs, durationMs)
    } catch (e: Exception) {
        null
    }

    private fun parsePolicy(payload: ByteArray) {
        val fields = Fields(payload)
        while (fields.next()) {
            if (fields.field == 7) cookie = fields.bytes() else fields.skip()
        }
    }

    private fun buildBody(timeMs: Long): ByteArray {
        val elapsed = System.currentTimeMillis() % 1_000_000
        val abr = Writer().apply {
            long(23, BANDWIDTH) // bandwidthEstimate
            int(21, spec.height) // stickyResolution
            bool(22, false) // clientViewportIsFlexible
            long(28, timeMs) // playerTimeMs
            int(34, 2) // visibility: fullscreen
            long(36, elapsed) // elapsedWallTimeMs
            long(39, elapsed) // timeSinceLastActionMs
            int(40, if (spec.isAudio) 1 else 2) // enabledTrackTypesBitfield
            bool(71, true) // enableSabrHostFallback
            bool(85, false) // supportsChangeType
            val trackId = spec.audioTrackId
            if (!trackId.isNullOrBlank()) string(69, trackId)
        }
        val format = Writer().apply {
            int(1, spec.itag)
            long(2, spec.lastModified.toLongOrNull() ?: 0L)
            if (spec.xTags.isNotBlank()) string(3, spec.xTags)
        }
        val clientInfo = Writer().apply {
            string(1, "en")
            spec.deviceMake?.let { string(12, it) }
            spec.deviceModel?.let { string(13, it) }
            int(16, spec.clientId)
            string(17, spec.clientVersion)
            spec.osName?.let { string(18, it) }
            spec.osVersion?.let { string(19, it) }
        }
        val context = Writer().apply {
            bytes(1, clientInfo.toByteArray())
            token()?.let { bytes(2, it) }
            cookie?.let { bytes(3, it) }
        }
        return Writer().apply {
            bytes(1, abr.toByteArray())
            bytes(2, format.toByteArray())
            if (config.isNotEmpty()) bytes(5, config)
            if (spec.isAudio) bytes(16, format.toByteArray()) else bytes(17, format.toByteArray())
            bytes(19, context.toByteArray())
        }.toByteArray()
    }

    /** The websafe token, decoded, which is what the protobuf wants. */
    private fun token(): ByteArray? {
        val raw = poToken() ?: return null
        return runCatching { Base64.decode(raw, Base64.URL_SAFE or Base64.NO_WRAP) }.getOrNull()
    }

    private fun fieldInt(payload: ByteArray, field: Int): Int = fieldValue(payload, field, 0L).toInt()

    private fun fieldString(payload: ByteArray, field: Int): String = try {
        val fields = Fields(payload)
        var value = ""
        while (fields.next()) {
            if (fields.field == field) value = String(fields.bytes(), Charsets.UTF_8) else fields.skip()
        }
        value
    } catch (e: Exception) {
        ""
    }

    private fun fieldValue(payload: ByteArray, field: Int, fallback: Long): Long = try {
        val fields = Fields(payload)
        var value = fallback
        while (fields.next()) {
            if (fields.field == field) value = fields.long() else fields.skip()
        }
        value
    } catch (e: Exception) {
        fallback
    }

    private companion object {
        const val TAG = "INboxSabr"
        const val BANDWIDTH = 2_000_000L

        /** How many hosts the server may move one session to before giving up. */
        const val MAX_REDIRECTS = 3

        const val PART_MEDIA_HEADER = 20
        const val PART_MEDIA = 21
        const val PART_MEDIA_END = 22
        const val PART_NEXT_REQUEST_POLICY = 35
        const val PART_SABR_REDIRECT = 43
        const val PART_SABR_ERROR = 44
        const val PART_STREAM_PROTECTION = 58

        val PROTO = "application/x-protobuf".toMediaType()

        fun readFully(input: InputStream, target: ByteArray): Boolean {
            var offset = 0
            while (offset < target.size) {
                val read = input.read(target, offset, target.size - offset)
                if (read < 0) return false
                offset += read
            }
            return true
        }

        /**
         * UMP varint: one byte holds seven bits, two bytes hold six plus eight, and
         * so on. It is not LEB128, so the sizes below are the ones YouTube uses.
         */
        fun readVarint(input: InputStream): Int {
            val first = input.read()
            if (first < 0) return -1
            if (first < 128) return first
            if (first < 192) {
                val b2 = input.read()
                if (b2 < 0) return -1
                return (first and 0x3F) or (b2 shl 6)
            }
            if (first < 224) {
                val b2 = input.read()
                val b3 = input.read()
                if (b2 < 0 || b3 < 0) return -1
                return (first and 0x1F) or (b2 shl 5) or (b3 shl 13)
            }
            if (first < 240) {
                val b2 = input.read()
                val b3 = input.read()
                val b4 = input.read()
                if (b2 < 0 || b3 < 0 || b4 < 0) return -1
                return (first and 0x0F) or (b2 shl 4) or (b3 shl 12) or (b4 shl 20)
            }
            // Five byte form: a marker byte and then a little endian 32 bit number.
            val rest = ByteArray(4)
            if (!readFully(input, rest)) return -1
            return (rest[0].toInt() and 0xFF) or
                ((rest[1].toInt() and 0xFF) shl 8) or
                ((rest[2].toInt() and 0xFF) shl 16) or
                ((rest[3].toInt() and 0xFF) shl 24)
        }
    }
}

/** Protobuf writer, only what this protocol needs. */
private class Writer {
    private val out = ByteArrayOutputStream(512)

    fun varint(value: Long) {
        var rest = value
        while (rest and 0x7FL.inv() != 0L) {
            out.write(((rest and 0x7F) or 0x80).toInt())
            rest = rest ushr 7
        }
        out.write((rest and 0x7F).toInt())
    }

    fun int(field: Int, value: Int) {
        if (value == 0) return
        varint((field shl 3).toLong())
        varint(value.toLong() and 0xFFFFFFFFL)
    }

    fun long(field: Int, value: Long) {
        if (value == 0L) return
        varint((field shl 3).toLong())
        varint(value)
    }

    fun bool(field: Int, value: Boolean) {
        if (!value) return
        varint((field shl 3).toLong())
        varint(1)
    }

    fun bytes(field: Int, value: ByteArray) {
        varint(((field shl 3) or 2).toLong())
        varint(value.size.toLong())
        out.write(value)
    }

    fun string(field: Int, value: String) = bytes(field, value.toByteArray(Charsets.UTF_8))

    fun toByteArray(): ByteArray = out.toByteArray()
}

/** Protobuf reader for the parts the relay decodes. */
private class Fields(private val data: ByteArray) {
    private var position = 0

    var field = 0
        private set

    private var wire = 0

    fun next(): Boolean {
        if (position >= data.size) return false
        val key = varint().toInt()
        field = key ushr 3
        wire = key and 7
        return field > 0
    }

    fun long(): Long = when (wire) {
        0 -> varint()
        1 -> {
            var value = 0L
            for (index in 0 until 8) value = value or ((data[position++].toLong() and 0xFF) shl (index * 8))
            value
        }
        2 -> {
            val size = varint().toInt()
            position += size
            0L
        }
        5 -> {
            var value = 0L
            for (index in 0 until 4) value = value or ((data[position++].toLong() and 0xFF) shl (index * 8))
            value
        }
        else -> 0L
    }

    fun int(): Int = long().toInt()

    fun bytes(): ByteArray {
        val size = varint().toInt()
        val end = minOf(position + size, data.size)
        val slice = data.copyOfRange(position, end)
        position = end
        return slice
    }

    fun skip() {
        when (wire) {
            0 -> varint()
            1 -> position += 8
            2 -> {
                // Read the length first: `position += varint()` would add the length
                // to the position from *before* the length prefix was consumed.
                val size = varint().toInt()
                position += size
            }
            5 -> position += 4
            else -> position = data.size
        }
        if (position > data.size) position = data.size
    }

    private fun varint(): Long {
        var result = 0L
        var shift = 0
        while (shift < 64) {
            val byte = data[position++].toInt() and 0xFF
            result = result or ((byte and 0x7F).toLong() shl shift)
            if (byte and 0x80 == 0) break
            shift += 7
        }
        return result
    }
}

/**
 * The `sidx` box of the mp4: for every fragment it gives its size and duration, so
 * a byte offset asked by the player turns into a position in time. That is what
 * makes seeking through SABR possible: ask the server for that time and skip the
 * bytes before the offset.
 */
internal class SabrIndex(private val entries: List<LongArray>) {
    fun timeFor(byte: Long): Long? {
        for (entry in entries) {
            if (byte >= entry[0] && byte < entry[1]) return entry[2]
        }
        return entries.lastOrNull()?.let { if (byte >= it[1]) it[2] + it[3] else null }
    }

    companion object {
        /** [startByte, endByte, startMs, durationMs] per fragment. */
        fun parse(data: ByteArray, base: Long): SabrIndex? {
            var offset = 0
            while (offset + 8 <= data.size) {
                val size = readInt(data, offset).toLong() and 0xFFFFFFFFL
                val type = String(data, offset + 4, 4, Charsets.US_ASCII)
                if (size < 8 || offset + size > data.size) break
                if (type == "sidx") {
                    return runCatching { decode(data, offset, size.toInt(), base) }.getOrNull()
                }
                offset += size.toInt()
            }
            return null
        }

        private fun decode(data: ByteArray, start: Int, size: Int, base: Long): SabrIndex? {
            val version = data[start + 8].toInt() and 0xFF
            var cursor = start + 12
            cursor += 4 // reference id
            val timescale = readInt(data, cursor).toLong() and 0xFFFFFFFFL
            cursor += 4
            var firstOffset = 0L
            if (version == 0) {
                firstOffset = readInt(data, cursor + 4).toLong() and 0xFFFFFFFFL
                cursor += 8
            } else {
                // 64 bit offsets: the low half is enough for any real file.
                firstOffset = readInt(data, cursor + 12).toLong() and 0xFFFFFFFFL
                cursor += 16
            }
            cursor += 2 // reserved
            val count = readShort(data, cursor)
            cursor += 2
            if (timescale <= 0L || count <= 0) return null
            // The fragments start right after the sidx box.
            var byte = base + start + size + firstOffset
            var time = 0L
            val entries = ArrayList<LongArray>(count)
            for (index in 0 until count) {
                if (cursor + 12 > data.size) break
                val first = readInt(data, cursor)
                val referenceSize = (first and 0x7FFFFFFF).toLong()
                val duration = readInt(data, cursor + 4).toLong() and 0xFFFFFFFFL
                cursor += 12
                if (referenceSize <= 0L) continue
                val durationMs = duration * 1000L / timescale
                entries += longArrayOf(byte, byte + referenceSize, time, durationMs)
                byte += referenceSize
                time += durationMs
            }
            return if (entries.isEmpty()) null else SabrIndex(entries)
        }

        private fun readInt(data: ByteArray, offset: Int): Int {
            if (offset + 4 > data.size) return 0
            return ((data[offset].toInt() and 0xFF) shl 24) or
                ((data[offset + 1].toInt() and 0xFF) shl 16) or
                ((data[offset + 2].toInt() and 0xFF) shl 8) or
                (data[offset + 3].toInt() and 0xFF)
        }

        private fun readShort(data: ByteArray, offset: Int): Int {
            if (offset + 2 > data.size) return 0
            return ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
        }
    }
}
