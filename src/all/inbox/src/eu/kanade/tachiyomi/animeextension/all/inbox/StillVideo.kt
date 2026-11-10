package eu.kanade.tachiyomi.animeextension.all.inbox

import android.util.Base64
import android.util.Log
import eu.kanade.tachiyomi.animesource.model.Video
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The still frame that makes an audio-only download possible.
 *
 * The app builds the download command with `-map 0:v`, so a source without a video
 * stream makes ffmpeg stop with `Stream map '0:v' matches no streams` before it writes
 * a single byte. Every audio-only entry therefore hands the downloader a video that is
 * one black frame of about a kilobyte, and [soundOnly] asks ffmpeg to drop that stream
 * again just before the file is written, so what lands in the library is only the
 * sound. When the app is too old for that, the file still downloads: it simply keeps
 * the black frame next to the audio.
 *
 * The frame lasts as long as the song (see [mp4]), which is what keeps the length of
 * the container, the seek bar and the progress of the download honest.
 */
internal object StillVideo {
    private const val TAG = "INboxStill"

    /** Longer than any video YouTube serves, and still a 32 bit count of milliseconds. */
    private const val MAX_DURATION_MS = 86_400_000L

    /** Where the length of the template is written: `mvhd`, `tkhd`, `mdhd` and `stts`. */
    private val DURATION_OFFSETS = intArrayOf(64, 184, 316, 644)

    /** The box each of those offsets belongs to, to notice if the template ever changes. */
    private val DURATION_BOXES = listOf("mvhd" to 44, "tkhd" to 160, "mdhd" to 296, "stts" to 628)

    /** Decoded once: the same frame is served for every audio track of a video. */
    private val template: ByteArray by lazy { Base64.decode(TEMPLATE, Base64.NO_WRAP) }

    /** One black frame that lasts [durationMs], as a plain MP4 the player can open. */
    fun mp4(durationMs: Long): ByteArray {
        val bytes = template.copyOf()
        val intact = DURATION_BOXES.all { (type, at) ->
            at + 4 <= bytes.size && String(bytes, at, 4, Charsets.US_ASCII) == type
        }
        if (!intact) {
            // The embedded file is always the one below, so this cannot happen; if it
            // ever does, serving the frame with its own length beats serving nothing.
            Log.w(TAG, "still frame template changed shape, keeping its own length")
            return bytes
        }
        val length = durationMs.coerceIn(1_000L, MAX_DURATION_MS).toInt()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        for (offset in DURATION_OFFSETS) buffer.putInt(offset, length)
        return bytes
    }

    /**
     * Asks the downloader to leave the picture out of the file.
     *
     * `ffmpegVideoArgs` belongs to the app's `Video`, not to the older extension library
     * this module compiles against, so it is filled by reflection. The app is built with
     * `-dontobfuscate`, which is what keeps the name of the field readable here.
     */
    fun soundOnly(video: Video): Video {
        runCatching {
            val field = video.javaClass.getDeclaredField("ffmpegVideoArgs")
            field.isAccessible = true
            field.set(video, listOf("map" to "-0:v"))
        }.onFailure {
            Log.w(TAG, "no ffmpegVideoArgs in this app: the still frame will be kept (${it.message})")
        }
        return video
    }

    /**
     * A 16x16 H.264 frame (baseline, so every decoder takes it), timescale 1000, one
     * sample, no audio, `moov` in front. 1475 bytes.
     */
    private const val TEMPLATE = "AAAAIGZ0eXBpc29tAAACAGlzb21pc28yYXZjMW1wNDEAAAMNbW9vdgAAAGxtdmhkAAAAAAAAAAAAAAAAAAAD6AA27oAAAQAAAQAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAgAAAjh0cmFrAAAAXHRraGQAAAADAAAAAAAAAAAAAAABAAAAAAA27oAAAAAAAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAABAAAAAABAAAAAQAAAAAAAkZWR0cwAAABxlbHN0AAAAAAAAAAEANu6AAAAAAAABAAAAAAGwbWRpYQAAACBtZGhkAAAAAAAAAAAAAAAAAAAD6AA27oBVxAAAAAAALWhkbHIAAAAAAAAAAHZpZGUAAAAAAAAAAAAAAABWaWRlb0hhbmRsZXIAAAABW21pbmYAAAAUdm1oZAAAAAEAAAAAAAAAAAAAACRkaW5mAAAAHGRyZWYAAAAAAAAAAQAAAAx1cmwgAAAAAQAAARtzdGJsAAAAt3N0c2QAAAAAAAAAAQAAAKdhdmMxAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAAAABAAEABIAAAASAAAAAAAAAABFExhdmM2My4xLjEwMiBsaWJ4MjY0AAAAAAAAAAAAAAAAGP//AAAALWF2Y0MBQsAK/+EAFWdCwArZHsBEAAA4QAAAAwAIPEiZIAEABWjLg8sgAAAAEHBhc3AAAAABAAAAAQAAABRidHJ0AAAAAAAAAAEAAAAAAAAAGHN0dHMAAAAAAAAAAQAAAAEANu6AAAAAHHN0c2MAAAAAAAAAAQAAAAEAAAABAAAAAQAAABRzdHN6AAAAAAAAAoYAAAABAAAAFHN0Y28AAAAAAAAAAQAAAz0AAABhdWR0YQAAAFltZXRhAAAAAAAAACFoZGxyAAAAAAAAAABtZGlyYXBwbAAAAAAAAAAAAAAAACxpbHN0AAAAJKl0b28AAAAcZGF0YQAAAAEAAAAATGF2ZjYzLjEuMTAyAAAACGZyZWUAAAKObWRhdAAAAnAGBf//bNxF6b3m2Ui3lizYINkj7u94MjY0IC0gY29yZSAxNjUgcjMyMjMgMDQ4MGNiMCAtIEguMjY0L01QRUctNCBBVkMgY29kZWMgLSBDb3B5bGVmdCAyMDAzLTIwMjUgLSBodHRwOi8vd3d3LnZpZGVvbGFuLm9yZy94MjY0Lmh0bWwgLSBvcHRpb25zOiBjYWJhYz0wIHJlZj0zIGRlYmxvY2s9MTowOjAgYW5hbHlzZT0weDE6MHgxMTEgbWU9aGV4IHN1Ym1lPTcgcHN5PTEgcHN5X3JkPTEuMDA6MC4wMCBtaXhlZF9yZWY9MSBtZV9yYW5nZT0xNiBjaHJvbWFfbWU9MSB0cmVsbGlzPTEgOHg4ZGN0PTAgY3FtPTAgZGVhZHpvbmU9MjEsMTEgZmFzdF9wc2tpcD0xIGNocm9tYV9xcF9vZmZzZXQ9LTIgdGhyZWFkcz0xIGxvb2thaGVhZF90aHJlYWRzPTEgc2xpY2VkX3RocmVhZHM9MCBucj0wIGRlY2ltYXRlPTEgaW50ZXJsYWNlZD0wIGJsdXJheV9jb21wYXQ9MCBjb25zdHJhaW5lZF9pbnRyYT0wIGJmcmFtZXM9MCB3ZWlnaHRwPTAga2V5aW50PTI1MCBrZXlpbnRfbWluPTEgc2NlbmVjdXQ9NDAgaW50cmFfcmVmcmVzaD0wIHJjX2xvb2thaGVhZD00MCByYz1jcmYgbWJ0cmVlPTEgY3JmPTIzLjAgcWNvbXA9MC42MCBxcG1pbj0wIHFwbWF4PTY5IHFwc3RlcD00IGlwX3JhdGlvPTEuNDAgYXE9MToxLjAwAIAAAAAOZYiEBX///w9FAAFC34A="
}
