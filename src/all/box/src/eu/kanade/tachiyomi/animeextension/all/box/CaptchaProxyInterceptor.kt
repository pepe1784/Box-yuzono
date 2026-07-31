package eu.kanade.tachiyomi.animeextension.all.box

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Interceptor that detects captcha / anti-bot pages (captchaproxy, Cloudflare,
 * etc.) and throws a clear error so the user can solve the challenge in the
 * source WebView. The cookies set by the WebView are shared with the OkHttp
 * client, so once solved the extension can continue using HTML endpoints.
 */
class CaptchaProxyInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        if (!response.isCaptchaChallenge()) {
            return response
        }

        response.close()
        throw Exception(
            "Esta instancia requiere completar un captcha (anti-bot). " +
                "Abre la fuente en WebView (icono del mundo arriba a la derecha), " +
                "resuelve el desafío y vuelve a intentarlo. " +
                "Las cookies se comparten, así que con hacerlo una vez suele bastar.",
        )
    }

    private fun Response.isCaptchaChallenge(): Boolean {
        val contentType = header("Content-Type") ?: return false
        if (!contentType.contains("text/html", ignoreCase = true)) return false
        val body = try {
            peekBody(CAPTCHA_PEEK_BYTES).string()
        } catch (_: Exception) {
            return false
        }
        return CAPTCHA_MARKERS.any { body.contains(it, ignoreCase = true) }
    }

    companion object {
        private const val CAPTCHA_PEEK_BYTES = 64 * 1024L
        private val CAPTCHA_MARKERS = listOf(
            "captchaproxy",
            "cap-widget",
            "cf-turnstile",
            "g-recaptcha",
            "challenge-form",
            "challenge-stage",
            "window._cf_chl_opt",
        )
    }
}
