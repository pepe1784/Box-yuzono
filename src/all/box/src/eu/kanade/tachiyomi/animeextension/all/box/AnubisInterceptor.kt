package eu.kanade.tachiyomi.animeextension.all.box

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Interceptor that solves the Anubis proof-of-work challenge used by some
 * Invidious instances. It detects the challenge HTML, computes the SHA-256
 * Hashcash nonce locally, calls the pass-challenge endpoint and then retries
 * the original request with the resulting authentication cookie.
 *
 * The auth cookie is also cached in memory and manually injected as a
 * "Cookie" header on later requests. This works around Aniyomi/Animetail
 * CookieJar implementations that drop or ignore cookies with SameSite=None,
 * which would otherwise cause every request to re-trigger the challenge and
 * create a redirect loop.
 */
class AnubisInterceptor : Interceptor {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val authCookies = ConcurrentHashMap<String, String>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        // Avoid intercepting the pass-challenge request itself.
        if (request.header(PASS_HEADER) != null) {
            return chain.proceed(request.newBuilder().removeHeader(PASS_HEADER).build())
        }

        val host = request.url.host

        // If we already solved a challenge for this host, inject the auth
        // cookie before the request is sent.
        val authCookie = authCookies[host]
        val requestWithCookie = if (authCookie != null && request.header("Cookie") == null) {
            request.newBuilder()
                .header("Cookie", authCookie)
                .build()
        } else {
            request
        }

        val response = chain.proceed(requestWithCookie)
        val challenge = response.extractChallenge() ?: return response

        response.close()

        val start = System.currentTimeMillis()
        val (hash, nonce) = solvePow(challenge.randomData, challenge.difficulty)
        val elapsed = System.currentTimeMillis() - start

        val basePrefix = challenge.basePrefix.orEmpty().trim('"').trim()
        val passPath = if (basePrefix.isEmpty()) {
            "/.within.website/x/cmd/anubis/api/pass-challenge"
        } else {
            "$basePrefix/.within.website/x/cmd/anubis/api/pass-challenge"
        }

        val passUrl = request.url.newBuilder()
            .encodedPath(passPath)
            .encodedQuery(null)
            .addQueryParameter("id", challenge.id)
            .addQueryParameter("response", hash)
            .addQueryParameter("nonce", nonce.toString())
            .addQueryParameter("redir", request.url.toString())
            .addQueryParameter("elapsedTime", elapsed.toString())
            .build()

        val passRequest = Request.Builder()
            .url(passUrl)
            .header("User-Agent", request.header("User-Agent") ?: USER_AGENT)
            .header("Accept", "text/html")
            .header(PASS_HEADER, "1")
            .build()

        val passResponse = chain.proceed(passRequest)
        passResponse.extractAuthCookie(host)?.let { authCookies[host] = it }
        passResponse.close()

        // Retry the original request with the auth cookie.
        val retryRequest = request.newBuilder()
            .header(PASS_HEADER, "1")
            .apply {
                authCookies[host]?.let { header("Cookie", it) }
            }
            .build()
        return chain.proceed(retryRequest)
    }

    private fun Response.extractChallenge(): ChallengeData? {
        if (!isSuccessful) return null
        val contentType = header("Content-Type") ?: return null
        if (!contentType.contains("text/html", ignoreCase = true)) return null
        val body = peekBody(CHALLENGE_PEEK_BYTES).string()
        if (!body.contains(ANUBIS_CHALLENGE_MARKER)) return null
        return parseChallenge(body)
    }

    private fun Response.extractAuthCookie(host: String): String? {
        val setCookies = headers("Set-Cookie")
        for (setCookie in setCookies) {
            val cookie = Cookie.parse(request.url, setCookie) ?: continue
            if (cookie.name.contains("anubis-auth") && cookie.matches(request.url)) {
                return "${cookie.name}=${cookie.value}"
            }
        }
        return null
    }

    private fun parseChallenge(html: String): ChallengeData? {
        return try {
            val challengeJson = CHALLENGE_REGEX.find(html)?.groupValues?.getOrNull(1)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: return null

            val page = json.decodeFromString<AnubisChallengePage>(challengeJson)
            val version = VERSION_REGEX.find(html)?.groupValues?.getOrNull(1)
                ?.trim('"')?.trim()
            val basePrefix = BASE_PREFIX_REGEX.find(html)?.groupValues?.getOrNull(1)
                ?.trim('"')?.trim()

            ChallengeData(
                id = page.challenge?.id ?: return null,
                randomData = page.challenge.randomData ?: return null,
                difficulty = page.rules?.difficulty ?: 2,
                algorithm = page.rules?.algorithm ?: "fast",
                version = version,
                basePrefix = basePrefix,
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun solvePow(randomData: String, difficulty: Int): Pair<String, Long> {
        val requiredZeroBytes = difficulty / 2
        val isOdd = difficulty % 2 != 0
        val md = MessageDigest.getInstance("SHA-256")
        var nonce = 0L
        val dataBytes = randomData.toByteArray(Charsets.UTF_8)

        while (true) {
            md.reset()
            md.update(dataBytes)
            md.update(nonce.toString().toByteArray(Charsets.UTF_8))
            val hash = md.digest()

            var valid = true
            for (i in 0 until requiredZeroBytes) {
                if (hash[i] != 0.toByte()) {
                    valid = false
                    break
                }
            }

            if (valid && isOdd) {
                if ((hash[requiredZeroBytes].toInt() shr 4) != 0) {
                    valid = false
                }
            }

            if (valid) {
                return hash.toHex() to nonce
            }
            nonce++
        }
    }

    private fun ByteArray.toHex(): String {
        return joinToString("") { "%02x".format(it) }
    }

    @Serializable
    private data class AnubisChallengePage(
        val rules: AnubisRules? = null,
        val challenge: AnubisChallenge? = null,
    )

    @Serializable
    private data class AnubisRules(
        val algorithm: String? = null,
        val difficulty: Int? = null,
    )

    @Serializable
    private data class AnubisChallenge(
        val id: String? = null,
        val randomData: String? = null,
    )

    private data class ChallengeData(
        val id: String,
        val randomData: String,
        val difficulty: Int,
        val algorithm: String,
        val version: String?,
        val basePrefix: String?,
    )

    companion object {
        private const val PASS_HEADER = "X-Box-Anubis-Pass"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
        private const val ANUBIS_CHALLENGE_MARKER = "id=\"anubis_challenge\""
        private const val CHALLENGE_PEEK_BYTES = 64 * 1024L

        private val CHALLENGE_REGEX = Regex(
            """<script id="anubis_challenge" type="application/json">(.*?)</script>""",
            RegexOption.DOT_MATCHES_ALL,
        )
        private val VERSION_REGEX = Regex(
            """<script id="anubis_version" type="application/json">(.*?)</script>""",
            RegexOption.DOT_MATCHES_ALL,
        )
        private val BASE_PREFIX_REGEX = Regex(
            """<script id="anubis_base_prefix" type="application/json">(.*?)</script>""",
            RegexOption.DOT_MATCHES_ALL,
        )
    }
}
