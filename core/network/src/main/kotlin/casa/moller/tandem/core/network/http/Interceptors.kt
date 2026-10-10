package casa.moller.tandem.core.network.http

import casa.moller.tandem.core.model.error.InvalidUrlReason
import casa.moller.tandem.core.model.error.NotTandoorReason
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.error.UnauthorizedSource
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

internal const val HEADER_AUTHORIZATION = "Authorization"
internal const val HEADER_USER_AGENT = "User-Agent"

/** Sets `User-Agent: Tandem/<versionName> (Android <sdkInt>)` on every request (plan 4.1). */
class UserAgentInterceptor(private val userAgent: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response =
        chain.proceed(chain.request().newBuilder().header(HEADER_USER_AGENT, userAgent).build())
}

/**
 * Refuses every request that is not HTTPS (plan 5.5: no cleartext). The network security config
 * blocks cleartext too; this gives a typed error instead of an `UnknownServiceException`.
 */
class HttpsOnlyInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.url.isHttps) {
            throw TandemIOException(TandemError.InvalidUrl(InvalidUrlReason.CLEARTEXT_NOT_ALLOWED))
        }
        return chain.proceed(request)
    }
}

/**
 * Adds `Authorization: Bearer <token>` only to requests for the configured server: same scheme,
 * host and port, and a path inside the base path (so `https://x/tandoor/` never sends the token
 * to `https://x/other-app/`). Any `Authorization` header on other requests is removed, so the
 * token can never leak to another host (e.g. an image URL on a CDN).
 *
 * A token with a character that is not allowed in a header (anything outside printable ASCII,
 * e.g. a smart quote or "ö" pasted along with it) fails the call with
 * `TandemError.Unauthorized(TANDOOR)` instead of OkHttp's IllegalArgumentException, which would
 * crash the app from an asynchronous call.
 */
class AuthInterceptor(serverBaseUrl: HttpUrl, private val tokenProvider: TokenProvider) : Interceptor {
    private val scheme = serverBaseUrl.scheme
    private val host = serverBaseUrl.host
    private val port = serverBaseUrl.port
    private val basePath = serverBaseUrl.encodedPath.let { if (it.endsWith("/")) it else "$it/" }

    fun isServerUrl(url: HttpUrl): Boolean = url.scheme == scheme && url.host == host && url.port == port &&
        url.encodedPath.startsWith(basePath)

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val builder = request.newBuilder().removeHeader(HEADER_AUTHORIZATION)
        if (isServerUrl(request.url)) {
            val token = tokenProvider.currentToken()?.trim()
            if (!token.isNullOrEmpty()) {
                if (!token.all { it in TOKEN_CHARS }) throw invalidToken()
                try {
                    builder.header(HEADER_AUTHORIZATION, "Bearer $token")
                } catch (e: IllegalArgumentException) {
                    // Defence in depth: never let a non-IOException escape an interceptor.
                    throw invalidToken()
                }
            }
        }
        return chain.proceed(builder.build())
    }

    private fun invalidToken() = TandemIOException(TandemError.Unauthorized(UnauthorizedSource.TANDOOR))

    private companion object {
        /** Printable ASCII without the space: what a Bearer token can consist of. */
        val TOKEN_CHARS = '\u0021'..'\u007E'
    }
}

/**
 * A 2xx HTML page on an API call means something else answered (a captive portal, an SSO login
 * page, another web app). Turn it into [NotTandoorReason.HTML_RESPONSE] before the JSON
 * converter fails with a confusing decode error.
 */
class HtmlResponseGuardInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val contentType = response.body.contentType()
        if (response.isSuccessful && contentType?.subtype == "html") {
            val code = response.code
            response.close()
            throw TandemIOException(TandemError.NotTandoor(NotTandoorReason.HTML_RESPONSE, code))
        }
        return response
    }
}
