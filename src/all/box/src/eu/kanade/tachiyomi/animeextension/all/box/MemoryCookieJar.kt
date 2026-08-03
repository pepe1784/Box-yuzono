package eu.kanade.tachiyomi.animeextension.all.box

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory cookie jar that keeps every cookie the server sets, including
 * cookies with SameSite=None which some Aniyomi/Animetail builds may drop.
 *
 * Invidious instances protected by Anubis set an auth cookie with
 * SameSite=None; Secure after solving the proof-of-work challenge. If that
 * cookie is lost, the next request to /search hits the challenge again and
 * OkHttp follows the pass-challenge redirect into an infinite loop, ending
 * with "ProtocolException: too many follow-up requests".
 */
class MemoryCookieJar : CookieJar {

    private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val key = hostKey(url)
        val list = store.getOrPut(key) { mutableListOf() }
        synchronized(list) {
            for (cookie in cookies) {
                // Remove any older cookie with the same name+domain+path.
                list.removeAll {
                    it.name == cookie.name &&
                        it.domain == cookie.domain &&
                        it.path == cookie.path
                }
                list.add(cookie)
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val key = hostKey(url)
        val list = store[key] ?: return emptyList()
        val now = System.currentTimeMillis()
        synchronized(list) {
            list.removeAll { !it.matches(url) || it.expiresAt < now }
            return list.toList()
        }
    }

    private fun hostKey(url: HttpUrl): String = url.host
}
