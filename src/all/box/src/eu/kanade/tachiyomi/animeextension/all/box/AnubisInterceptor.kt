package eu.kanade.tachiyomi.animeextension.all.box

import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.security.MessageDigest

private class AnubisRetryTag(val count: Int)

/**
 * Interceptor that solves the Anubis proof-of-work challenge used by some
 * Invidious instances. It detects the challenge HTML, computes the SHA-256
 * Hashcash nonce locally, calls the pass-challenge endpoint and then retries
 * the original request with the resulting authentication cookie.
 *
 * The pass-challenge call is made through a client that does **not** follow
 * redirects, because Anubis answers it with a 302 that carries the
 * `Set-Cookie` header. OkHttp follows redirects below the application
 * interceptor layer, so a normal call would hide that header and the auth
 * cookie would never be stored. The raw cookies are handed to [AnubisCookieJar]
 * which serves them on every later request for that host.
 */
class AnubisInterceptor(
    private val cookieJar: AnubisCookieJar,
    private val passClient: OkHttpClient,
) : Interceptor {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        // Avoid intercepting the pass-challenge request itself.
        if (request.header(PASS_HEADER) != null) {
            return chain.proceed(request.newBuilder().removeHeader(PASS_HEADER).build())
        }

        val host = request.url.host

        // Prevent infinite challenge loops if the auth cookie never sticks.
        val retryCount = request.tag(AnubisRetryTag::class.java)?.count ?: 0
        if (retryCount >= MAX_RETRIES) {
            throw Exception(
                "Anubis: la instancia sigue pidiendo challenge tras $MAX_RETRIES intentos. " +
                    "Intenta desactivar 'Usar catálogo HTML' o cambia de instancia.",
            )
        }

        val response = chain.proceed(request)
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

        // Redirects are disabled on purpose: the Set-Cookie header we need is
        // attached to the 302 itself and would be lost otherwise.
        passClient.newCall(passRequest).execute().use { passResponse ->
            val rawCookies = passResponse.headers("Set-Cookie")
                .map { it.substringBefore(";").trim() }
                .filter { it.contains("=") }
            if (rawCookies.isNotEmpty()) {
                cookieJar.store(host, rawCookies.joinToString("; "))
            } else if (!passResponse.isSuccessful) {
                Log.w(
                    "Anubis",
                    "pass-challenge failed: HTTP ${passResponse.code} for ${request.url}",
                )
            }
        }

        // Retry the original request with the auth cookie.
        val retryRequest = request.newBuilder()
            .tag(AnubisRetryTag::class.java, AnubisRetryTag(retryCount + 1))
            .header(PASS_HEADER, "1")
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

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

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
        private const val MAX_RETRIES = 2
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
