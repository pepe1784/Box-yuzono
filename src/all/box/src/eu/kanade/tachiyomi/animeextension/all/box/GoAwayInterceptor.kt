package eu.kanade.tachiyomi.animeextension.all.box

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.security.MessageDigest

/**
 * Interceptor that solves the go-away js-pow-sha256 proof-of-work challenge
 * used by some Invidious instances (e.g. inv.nadeko.net).
 */
class GoAwayInterceptor : Interceptor {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        // Avoid intercepting our own internal challenge/verify requests.
        if (request.header(PASS_HEADER) != null) {
            return chain.proceed(request.newBuilder().removeHeader(PASS_HEADER).build())
        }

        val response = chain.proceed(request)
        val challenge = response.extractChallenge(request) ?: return response

        response.close()

        // Fetch challenge data (challenge hex + target hex).
        val makeChallengeRequest = Request.Builder()
            .url(challenge.makeChallengeUrl(request.url.scheme, request.url.host))
            .header("User-Agent", request.header("User-Agent") ?: USER_AGENT)
            .header("Accept", "application/json")
            .header(PASS_HEADER, "1")
            .post(okhttp3.RequestBody.create(null, ByteArray(0)))
            .build()

        val makeChallengeBody = chain.proceed(makeChallengeRequest).use { it.body?.string() ?: "" }
        val powData = json.decodeFromString<GoAwayPowData>(makeChallengeBody)

        val start = System.currentTimeMillis()
        val token = solvePow(powData.challenge, powData.target)
        val elapsed = System.currentTimeMillis() - start

        val verifyUrl = request.url.newBuilder()
            .encodedPath(challenge.path + "/verify-challenge")
            .encodedQuery(null)
            .addQueryParameter("__goaway_token", token)
            .addQueryParameter("__goaway_challenge", "js-pow-sha256")
            .addQueryParameter("__goaway_redirect", request.url.toString())
            .addQueryParameter("__goaway_id", challenge.id)
            .addQueryParameter("__goaway_elapsedTime", elapsed.toString())
            .build()

        val verifyRequest = Request.Builder()
            .url(verifyUrl)
            .header("User-Agent", request.header("User-Agent") ?: USER_AGENT)
            .header("Accept", "text/html")
            .header(PASS_HEADER, "1")
            .build()

        // The verify response sets the auth cookie in OkHttp's cookie jar.
        chain.proceed(verifyRequest).close()

        // Retry the original request. The cookie jar now has the go-away cookie.
        return chain.proceed(request)
    }

    private fun Response.extractChallenge(request: Request): GoAwayChallenge? {
        if (code != 418 && code != 403) return null
        val contentType = header("Content-Type") ?: return null
        if (!contentType.contains("text/html", ignoreCase = true)) return null
        val body = peekBody(CHALLENGE_PEEK_BYTES).string()
        if (!body.contains(GO_AWAY_MARKER)) return null
        return parseChallenge(body, request)
    }

    private fun parseChallenge(html: String, request: Request): GoAwayChallenge? {
        return try {
            val scriptSrc = SCRIPT_SRC_REGEX.find(html)?.groupValues?.getOrNull(1)
                ?: return null

            val scriptUrl = when {
                scriptSrc.startsWith("http://") || scriptSrc.startsWith("https://") -> scriptSrc
                scriptSrc.startsWith("/") -> "${request.url.scheme}://${request.url.host}$scriptSrc"
                else -> "${request.url.scheme}://${request.url.host}/$scriptSrc"
            }

            val scriptRequest = Request.Builder()
                .url(scriptUrl)
                .header("User-Agent", request.header("User-Agent") ?: USER_AGENT)
                .header("Accept", "*/*")
                .header(PASS_HEADER, "1")
                .build()

            val scriptBody = okhttp3.OkHttpClient().newCall(scriptRequest).execute().use {
                it.body?.string() ?: ""
            }

            val id = GOAWAY_ID_REGEX.find(scriptBody)?.groupValues?.getOrNull(1)
                ?: return null
            val path = PATH_REGEX.find(scriptBody)?.groupValues?.getOrNull(1)
                ?: return null

            GoAwayChallenge(id = id, path = path)
        } catch (_: Exception) {
            null
        }
    }

    private data class GoAwayChallenge(
        val id: String,
        val path: String,
    ) {
        fun makeChallengeUrl(scheme: String, host: String): String {
            return "$scheme://$host$path/make-challenge"
        }
    }

    @Serializable
    private data class GoAwayPowData(
        val challenge: String,
        val target: String,
        val difficulty: Int = 0,
    )

    private fun solvePow(challengeHex: String, targetHex: String): String {
        val challenge = challengeHex.hexToBytes()
        val target = targetHex.hexToBytes()
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(challenge.size + 8)
        System.arraycopy(challenge, 0, buf, 0, challenge.size)

        var nonce = 0L
        while (true) {
            val nonceBytes = nonce.toBytesBE()
            System.arraycopy(nonceBytes, 0, buf, challenge.size, 8)
            val hash = md.digest(buf)
            if (hash.lessThan(target)) {
                return buf.toHex()
            }
            nonce++
        }
    }

    private fun Long.toBytesBE(): ByteArray {
        return byteArrayOf(
            (this shr 56).toByte(),
            (this shr 48).toByte(),
            (this shr 40).toByte(),
            (this shr 32).toByte(),
            (this shr 24).toByte(),
            (this shr 16).toByte(),
            (this shr 8).toByte(),
            this.toByte(),
        )
    }

    private fun ByteArray.lessThan(other: ByteArray): Boolean {
        for (i in indices) {
            val a = this[i].toInt() and 0xFF
            val b = other[i].toInt() and 0xFF
            if (a < b) return true
            if (a > b) return false
        }
        return false
    }

    private fun String.hexToBytes(): ByteArray {
        val len = length
        val result = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            result[i / 2] = ((this[i].digitToInt(16) shl 4) + this[i + 1].digitToInt(16)).toByte()
        }
        return result
    }

    private fun ByteArray.toHex(): String {
        return joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val PASS_HEADER = "X-Box-GoAway-Pass"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
        private const val GO_AWAY_MARKER = "Protected by go-away"
        private const val CHALLENGE_PEEK_BYTES = 64 * 1024L

        private val SCRIPT_SRC_REGEX = Regex(
            """<script async type="module" src="([^"]+)""",
        )
        private val GOAWAY_ID_REGEX = Regex(
            """__goaway_id["']?\s*:\s*["']([^"']+)["']""",
        )
        private val PATH_REGEX = Regex(
            """Path:\s*"([^"]+)""",
        )
    }
}
