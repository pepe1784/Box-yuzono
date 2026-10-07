package eu.kanade.tachiyomi.animeextension.all.box

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.util.concurrent.ConcurrentHashMap

/**
 * Cookie jar that layers the Anubis proof-of-work authentication cookies on top
 * of whatever jar the app provides.
 *
 * Two problems are solved here:
 *
 * 1. Anubis answers the pass-challenge call with a 302 that carries the
 *    `Set-Cookie` header. OkHttp follows that redirect internally, so an
 *    application interceptor only ever sees the final response and never the
 *    header. The cookie is captured by [AnubisInterceptor] from the raw
 *    (non following) pass-challenge response and stored here.
 * 2. Application interceptors set the `Cookie` header *before* OkHttp's
 *    BridgeInterceptor runs, and BridgeInterceptor overwrites it with whatever
 *    the cookie jar returns. So a manually injected header is lost unless the
 *    jar itself owns the cookie. Owning the jar guarantees delivery.
 *
 * The raw Anubis cookies are cached by host and served merged with the cookies
 * owned by the app jar, so normal cookies (Invidious PREFS, etc.) keep working.
 */
class AnubisCookieJar(private val delegate: CookieJar) : CookieJar {

    private val authCookies = ConcurrentHashMap<String, String>()

    fun store(host: String, cookieHeader: String) {
        if (cookieHeader.isBlank()) return
        val existing = authCookies[host]
        val newPair = cookieHeader.substringBefore(";").trim()
        if (!newPair.contains("=")) return
        authCookies[host] = if (existing.isNullOrBlank()) newPair else "$existing; $newPair"
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        delegate.saveFromResponse(url, cookies)
        cookies
            .filter { it.name.contains(ANUBIS_MARKER, ignoreCase = true) }
            .forEach { store(url.host, "${it.name}=${it.value}") }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val merged = LinkedHashMap<String, Cookie>()
        delegate.loadForRequest(url).forEach { merged[it.name] = it }
        authCookies[url.host]?.let { raw ->
            raw.split(";").forEach { part ->
                val name = part.substringBefore("=").trim()
                val value = part.substringAfter("=", "").trim()
                if (name.isEmpty() || value.isEmpty() || name in merged) {
                    return@forEach
                }
                merged[name] = buildCookie(url, name, value)
            }
        }
        return merged.values.toList()
    }

    private fun buildCookie(url: HttpUrl, name: String, value: String): Cookie = Cookie.Builder()
        .name(name)
        .value(value)
        .domain(url.host)
        .path("/")
        .expiresAt(Long.MAX_VALUE)
        .build()

    companion object {
        private const val ANUBIS_MARKER = "anubis"
    }
}
