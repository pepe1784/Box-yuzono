package eu.kanade.tachiyomi.animeextension.all.box

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.security.MessageDigest

/**
 * Interceptor that solves the go-away challenge chain used by some Invidious
 * instances (e.g. inv.nadeko.net). The server may chain several challenges
 * (commonly js-pow-sha256 followed by js-refresh); this interceptor keeps
 * solving/following them until the original request can proceed.
 */
class GoAwayInterceptor : Interceptor {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        // Avoid intercepting our own internal challenge/verify requests.
        if (request.header(PASS_HEADER) != null) {
            return chain.proceed(request.newBuilder().removeHeader(PASS_HEADER).build())
        }

        // Limit how many times we retry the original request.
        val retryCount = request.header(RETRY_HEADER)?.toIntOrNull() ?: 0
        if (retryCount >= MAX_RETRIES) {
            throw Exception(
                "GoAway: la instancia sigue requiriendo challenge tras $MAX_RETRIES intentos. " +
                    "Probablemente detecta la app como bot. Prueba con otra instancia.",
            )
        }

        var response = chain.proceed(request)
        if (!response.isGoAwayChallenge()) {
            return response
        }

        repeat(MAX_CHALLENGE_STEPS) {
            try {
                solveGoAwayChallenge(response, request, chain)
            } finally {
                response.close()
            }

            val next = chain.proceed(
                request.newBuilder()
                    .header(RETRY_HEADER, (retryCount + 1).toString())
                    .build(),
            )
            if (!next.isGoAwayChallenge()) {
                return next
            }
            response = next
        }

        throw Exception(
            "GoAway: no se pudo completar la cadena de desafíos tras $MAX_CHALLENGE_STEPS pasos. " +
                "La instancia sigue pidiendo verificación.",
        )
    }

    private fun Response.isGoAwayChallenge(): Boolean {
        if (code != 418 && code != 403) return false
        val body = try {
            peekBody(CHALLENGE_PEEK_BYTES).string()
        } catch (_: Exception) {
            return false
        }
        return GO_AWAY_MARKERS.any { body.contains(it, ignoreCase = true) }
    }

    private fun solveGoAwayChallenge(response: Response, request: Request, chain: Interceptor.Chain) {
        val body = try {
            response.peekBody(CHALLENGE_PEEK_BYTES).string()
        } catch (e: Exception) {
            throw Exception("GoAway: cannot read challenge body: ${e.message}")
        }

        // Some challenges (e.g. js-refresh) directly return a redirect URL.
        val jsRedirect = JS_REDIRECT_REGEX.find(body)?.groupValues?.getOrNull(1)
        if (jsRedirect != null && jsRedirect.contains("verify-challenge")) {
            followVerifyUrl(unescapeUnicode(jsRedirect), request, chain, request.url.toString())
            return
        }

        val metaRedirect = META_REFRESH_REGEX.find(body)?.groupValues?.getOrNull(1)
        if (metaRedirect != null && metaRedirect.contains("verify-challenge")) {
            followVerifyUrl(metaRedirect, request, chain, request.url.toString())
            return
        }

        val headerRedirect = response.header("Refresh")
        if (headerRedirect != null && headerRedirect.contains("verify-challenge")) {
            val url = headerRedirect.substringAfter("url=", "").trim().trim('"', '\'')
            if (url.isNotBlank()) {
                followVerifyUrl(url, request, chain, request.url.toString())
                return
            }
        }

        // Otherwise it is a script-backed challenge (e.g. js-pow-sha256).
        solveScriptChallenge(body, request, chain)
    }

    private fun solveScriptChallenge(html: String, request: Request, chain: Interceptor.Chain) {
        val scriptSrc = SCRIPT_SRC_REGEX.find(html)?.groupValues?.getOrNull(1)
            ?: throw Exception("GoAway: could not find challenge script src")

        val scriptUrl = when {
            scriptSrc.startsWith("http://") || scriptSrc.startsWith("https://") -> scriptSrc
            scriptSrc.startsWith("/") -> "${request.url.scheme}://${request.url.host}$scriptSrc"
            else -> "${request.url.scheme}://${request.url.host}/$scriptSrc"
        }

        val scriptRequest = Request.Builder()
            .url(scriptUrl)
            .header("User-Agent", request.header("User-Agent") ?: USER_AGENT)
            .header("Accept", "*/*")
            .header("Referer", request.url.toString())
            .header(PASS_HEADER, "1")
            .build()

        val scriptBody = chainUnsafe(chain, scriptRequest).use {
            if (!it.isSuccessful) {
                throw Exception("GoAway: challenge script returned ${it.code}")
            }
            it.body?.string() ?: throw Exception("GoAway: challenge script empty")
        }

        val id = GOAWAY_ID_REGEX.find(scriptBody)?.groupValues?.getOrNull(1)
            ?: throw Exception("GoAway: __goaway_id not found in script")
        val rawPath = PATH_REGEX.find(scriptBody)?.groupValues?.getOrNull(1)
            ?: throw Exception("GoAway: challenge Path not found in script")
        val path = rawPath.let { if (it.startsWith("/")) it else "/$it" }
        val challengeName = CHALLENGE_NAME_REGEX.find(scriptBody)?.groupValues?.getOrNull(1)
            ?: rawPath.substringAfterLast("/").takeIf { it.isNotBlank() }
            ?: "js-pow-sha256"

        val userAgent = request.header("User-Agent") ?: USER_AGENT

        // Fetch challenge data (challenge hex + target hex).
        val makeChallengeRequest = Request.Builder()
            .url("${request.url.scheme}://${request.url.host}$path/make-challenge")
            .headers(challengeHeaders(request, acceptJson = true))
            .header(PASS_HEADER, "1")
            .post(okhttp3.RequestBody.create(null, ByteArray(0)))
            .build()

        val makeChallengeResponse = chain.proceed(makeChallengeRequest)
        val makeChallengeBody = makeChallengeResponse.use {
            if (!it.isSuccessful) {
                throw Exception("GoAway make-challenge failed: ${it.code}")
            }
            it.body?.string() ?: throw Exception("GoAway make-challenge empty body")
        }
        val powData = try {
            json.decodeFromString<GoAwayPowData>(makeChallengeBody)
        } catch (e: Exception) {
            throw Exception("GoAway make-challenge parse error: ${e.message} body=$makeChallengeBody")
        }

        if (powData.challenge.isBlank() || powData.target.isBlank()) {
            throw Exception("GoAway: empty challenge or target")
        }

        val start = System.currentTimeMillis()
        val token = solvePow(powData.challenge, powData.target)
        val elapsed = System.currentTimeMillis() - start

        // Small jitter so the solve-to-verify timing looks less robotic.
        Thread.sleep((500L..1500L).random())

        val verifyUrl = request.url.newBuilder()
            .encodedPath(path + "/verify-challenge")
            .encodedQuery(null)
            .addQueryParameter("__goaway_token", token)
            .addQueryParameter("__goaway_challenge", challengeName)
            .addQueryParameter("__goaway_redirect", request.url.toString())
            .addQueryParameter("__goaway_id", id)
            .addQueryParameter("__goaway_elapsedTime", elapsed.toString())
            .build()

        val verifyRequest = Request.Builder()
            .url(verifyUrl)
            .headers(challengeHeaders(request, acceptJson = false))
            .header(PASS_HEADER, "1")
            .build()

        val verifyResponse = chain.proceed(verifyRequest)
        verifyResponse.use {
            if (it.code >= 400 && !it.isGoAwayChallenge()) {
                throw Exception("GoAway verify-challenge failed: ${it.code}")
            }
        }
    }

    private fun followVerifyUrl(
        rawUrl: String,
        request: Request,
        chain: Interceptor.Chain,
        referer: String,
    ) {
        val absoluteUrl = when {
            rawUrl.startsWith("http://") || rawUrl.startsWith("https://") -> rawUrl
            rawUrl.startsWith("/") -> "${request.url.scheme}://${request.url.host}$rawUrl"
            else -> "${request.url.scheme}://${request.url.host}/$rawUrl"
        }

        val verifyRequest = Request.Builder()
            .url(absoluteUrl)
            .headers(challengeHeaders(request, acceptJson = false))
            .header("Referer", referer)
            .header(PASS_HEADER, "1")
            .build()

        val verifyResponse = chain.proceed(verifyRequest)
        verifyResponse.use {
            if (it.code >= 400 && !it.isGoAwayChallenge()) {
                throw Exception("GoAway verify-challenge (refresh) failed: ${it.code}")
            }
        }
    }

    /**
     * Build headers that look like a real browser navigation, but keep the
     * headers used by go-away to derive the challenge key (User-Agent,
     * Accept-Language, Accept-Encoding, Sec-Ch-Ua*, ...) identical to the
     * original request. Otherwise the solved token will not match on the
     * following request.
     */
    private fun challengeHeaders(request: Request, acceptJson: Boolean): Headers {
        val builder = Headers.Builder()
        builder.add("User-Agent", request.header("User-Agent") ?: USER_AGENT)
        request.header("Accept-Language")?.let { builder.add("Accept-Language", it) }
        request.header("Accept-Encoding")?.let { builder.add("Accept-Encoding", it) }
        request.header("Sec-Ch-Ua")?.let { builder.add("Sec-Ch-Ua", it) }
        request.header("Sec-Ch-Ua-Mobile")?.let { builder.add("Sec-Ch-Ua-Mobile", it) }
        request.header("Sec-Ch-Ua-Platform")?.let { builder.add("Sec-Ch-Ua-Platform", it) }

        if (acceptJson) {
            builder.add("Accept", "application/json")
            builder.add("Origin", "${request.url.scheme}://${request.url.host}")
        } else {
            builder.add(
                "Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            )
        }

        builder.add("Referer", request.url.toString())
        builder.add("Upgrade-Insecure-Requests", "1")
        builder.add("Sec-Fetch-Dest", "document")
        builder.add("Sec-Fetch-Mode", "navigate")
        builder.add("Sec-Fetch-Site", "same-origin")
        builder.add("Sec-Fetch-User", "?1")
        return builder.build()
    }

    private fun chainUnsafe(chain: Interceptor.Chain, request: Request): Response {
        // Use the same connection chain but without re-running this interceptor.
        return chain.proceed(request)
    }

    private fun solvePow(challengeHex: String, targetHex: String): String {
        val challenge = challengeHex.hexToBytes()
        val target = targetHex.hexToBytes()
        if (challenge.isEmpty() || target.isEmpty()) {
            throw Exception("GoAway: empty challenge or target")
        }
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

    private fun unescapeUnicode(input: String): String {
        return UNICODE_ESCAPE_REGEX.replace(input) { match ->
            val code = match.groupValues[1].toIntOrNull(16) ?: 0xFFFD
            code.toChar().toString()
        }
    }

    @Serializable
    private data class GoAwayPowData(
        val challenge: String,
        val target: String,
        val difficulty: Int = 0,
    )

    companion object {
        private const val PASS_HEADER = "X-Box-GoAway-Pass"
        private const val RETRY_HEADER = "X-Box-GoAway-Retry"
        private const val MAX_RETRIES = 5
        private const val MAX_CHALLENGE_STEPS = 5
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
        private const val CHALLENGE_PEEK_BYTES = 64 * 1024L

        private val GO_AWAY_MARKERS = listOf(
            "go-away",
            "__goaway",
            "Protected by go-away",
            "Checking you are not a bot",
        )

        private val SCRIPT_SRC_REGEX = Regex(
            """<script async type="module" src="([^"]+)""",
        )
        private val GOAWAY_ID_REGEX = Regex(
            """__goaway_id["']?\s*:\s*["']([^"']+)["']""",
        )
        private val PATH_REGEX = Regex(
            """Path:\s*"([^"]+)"""",
        )
        private val CHALLENGE_NAME_REGEX = Regex(
            """Challenge:\s*"([^"]+)"""",
        )
        private val JS_REDIRECT_REGEX = Regex(
            """window\.location(?:\.href)?\s*=\s*"([^"]+)""",
        )
        private val META_REFRESH_REGEX = Regex(
            """<meta[^>]+http-equiv="refresh"[^>]+content="[^"]*url=([^"]+)""",
            RegexOption.IGNORE_CASE,
        )
        private val UNICODE_ESCAPE_REGEX = Regex(
            """\\u([0-9a-fA-F]{4})""",
        )
    }
}
