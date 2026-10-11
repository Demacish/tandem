package casa.moller.tandem.core.network.image

import casa.moller.tandem.core.network.di.TandoorImageHttpClient
import casa.moller.tandem.core.network.http.AuthInterceptor
import casa.moller.tandem.core.network.http.TokenProvider
import javax.inject.Inject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

/**
 * OkHttp clients for image loading (Coil 3: `OkHttpNetworkFetcherFactory(callFactory = { client })`),
 * one per account (plan 3.4, 4.1, 5.5).
 *
 * - The token goes ONLY to the account's origin and base path ([AuthInterceptor]): `/media`
 *   needs no login on a standard install (FACTS "Fas 1 e"), but the token is sent anyway in case
 *   a server protects it later (plan 3.4). Any `Authorization` header is stripped for other
 *   hosts, and OkHttp drops it on a redirect to another host.
 * - Images run on their own dispatcher and rate limit
 *   ([casa.moller.tandem.core.network.http.TandemHttpClients.images]), so a screen full of
 *   pictures never delays an API call: `/media` is served by the web server in front of
 *   Tandoor, not by its (often only two) worker threads.
 * - Repeated 4xx answers pause image requests for a while ([ImageErrorBreakerInterceptor]), so
 *   a list of stale image URLs cannot look like a scan to a firewall (plan 4.1).
 */
class TandoorImageClientFactory @Inject constructor(@TandoorImageHttpClient private val imageBase: OkHttpClient) {

    /**
     * @param baseUrl the account's normalized `https://` base URL (trailing slash).
     * @param tokenProvider the account's token source; called for requests to [baseUrl] only.
     * @throws IllegalArgumentException if [baseUrl] is not an absolute https URL.
     */
    fun create(baseUrl: String, tokenProvider: TokenProvider): OkHttpClient {
        val url = requireHttpsBase(baseUrl)
        return imageBase.newBuilder()
            .addInterceptor(AuthInterceptor(url, tokenProvider))
            .build()
    }

    private fun requireHttpsBase(baseUrl: String): HttpUrl {
        val url = requireNotNull(baseUrl.toHttpUrlOrNull()) { "Not a URL: $baseUrl" }
        require(url.isHttps) { "Tandem only talks HTTPS (plan 5.5): $baseUrl" }
        return if (url.encodedPath.endsWith("/")) url else url.newBuilder().addPathSegment("").build()
    }
}
