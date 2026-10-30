package casa.moller.tandem.core.network.openfoodfacts

import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.network.error.ErrorClassifier
import casa.moller.tandem.core.network.error.apiCall
import casa.moller.tandem.core.network.http.HttpsOnlyInterceptor
import casa.moller.tandem.core.network.http.RateLimitInterceptor
import casa.moller.tandem.core.network.http.TokenBucket
import casa.moller.tandem.core.network.http.UserAgentInterceptor
import casa.moller.tandem.core.network.http.await
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.CookieJar
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

// Fas S (hemma-data lane): an unknown barcode looked up in Open Food Facts' public read API (plan
// §8.8, B21). Privacy: ONLY the code goes out (the path), with a fixed field list and Tandem's own
// User-Agent; the client is built from scratch, so no Tandoor interceptor, header, token or
// cookie can ever reach it (it shares nothing with TandemHttpClients).

/** The product fields the app asks for (a fixed list: the request carries nothing about the user). */
@Serializable
data class OpenFoodFactsProductDto(
    @SerialName("product_name") val productName: String? = null,
    @SerialName("product_name_sv") val productNameSv: String? = null,
    @SerialName("product_name_en") val productNameEn: String? = null,
    val brands: String? = null,
    val quantity: String? = null,
)

/**
 * `GET /api/v2/product/{code}`: `{code, status: 1, status_verbose: "product found", product}`; an
 * unknown code is a 404 with `{code, status: 0, status_verbose: "product not found"}`.
 */
@Serializable
data class OpenFoodFactsResponseDto(
    val code: String? = null,
    val status: Int = 0,
    @SerialName("status_verbose") val statusVerbose: String? = null,
    val product: OpenFoodFactsProductDto? = null,
)

/** What Open Food Facts said about one code. */
sealed interface OpenFoodFactsResult {
    /** [code] as Open Food Facts normalised it (a UPC-A comes back with a leading 0). */
    data class Found(val code: String, val product: OpenFoodFactsProductDto) : OpenFoodFactsResult

    /** Open Food Facts does not know the code (404, or `status: 0`). */
    data object NotFound : OpenFoodFactsResult
}

/**
 * The Open Food Facts read client. Short timeouts (the scan screen waits for it), one request at a
 * time and at most one per second (Open Food Facts asks apps to stay well below 100 product reads
 * a minute), HTTPS only, no cookies, no cache, redirects only to https.
 */
class OpenFoodFactsClient(private val client: OkHttpClient, baseUrl: String = DEFAULT_BASE_URL) {
    private val base: HttpUrl = baseUrl.toHttpUrl()

    init {
        require(base.isHttps) { "Open Food Facts is only called over https" }
    }

    /** The product of [code] (digits only; the caller validates it), or [OpenFoodFactsResult.NotFound]. */
    suspend fun product(code: String): Outcome<OpenFoodFactsResult> {
        if (code.isEmpty() || code.any { it !in '0'..'9' }) return Outcome.Failure(TandemError.BadRequest())
        val url = base.newBuilder()
            .addPathSegments("api/v2/product")
            .addPathSegment(code)
            .addQueryParameter("fields", FIELDS)
            .build()
        val request = Request.Builder().url(url).get().header("Accept", "application/json").build()
        // On IO: `await` resumes on the caller's dispatcher (a ViewModel's main thread), and reading
        // the body there threw NetworkOnMainThreadException on the device (found by the release
        // smoke test; JVM tests have no such check).
        val answer = withContext(Dispatchers.IO) {
            apiCall {
                client.newCall(request).await().use { response ->
                    RawAnswer(response.code, response.header("Retry-After"), response.body.string())
                }
            }
        }
        val raw = when (answer) {
            is Outcome.Failure -> return answer
            is Outcome.Success -> answer.value
        }
        return when {
            raw.code == HTTP_NOT_FOUND -> Outcome.Success(OpenFoodFactsResult.NotFound)
            raw.code == HTTP_TOO_MANY -> Outcome.Failure(TandemError.Throttled(ErrorClassifier.Default.parseRetryAfter(raw.retryAfter)))
            raw.code in HTTP_SERVER_ERRORS -> Outcome.Failure(TandemError.ServerError(raw.code))
            raw.code !in HTTP_SUCCESS -> Outcome.Failure(TandemError.Unknown("openfoodfacts HTTP ${raw.code}"))
            else -> decode(raw.text, code)
        }
    }

    private class RawAnswer(val code: Int, val retryAfter: String?, val text: String)

    private fun decode(text: String, code: String): Outcome<OpenFoodFactsResult> {
        val answer = try {
            json.decodeFromString(OpenFoodFactsResponseDto.serializer(), text)
        } catch (e: SerializationException) {
            return Outcome.Failure(TandemError.Decode(DECODE))
        } catch (e: IllegalArgumentException) {
            return Outcome.Failure(TandemError.Decode(DECODE))
        }
        val product = answer.product
        return if (answer.status != STATUS_FOUND || product == null) {
            Outcome.Success(OpenFoodFactsResult.NotFound)
        } else {
            Outcome.Success(OpenFoodFactsResult.Found(answer.code ?: code, product))
        }
    }

    companion object {
        const val DEFAULT_BASE_URL: String = "https://world.openfoodfacts.org/"

        /** The fields asked for; nothing else is requested or sent. */
        const val FIELDS: String = "code,product_name,product_name_sv,product_name_en,brands,quantity"

        const val CONNECT_TIMEOUT_SECONDS: Long = 5
        const val READ_TIMEOUT_SECONDS: Long = 10
        const val CALL_TIMEOUT_SECONDS: Long = 15

        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_TOO_MANY = 429
        private val HTTP_SERVER_ERRORS = 500..599
        private val HTTP_SUCCESS = 200..299
        private const val STATUS_FOUND = 1
        private const val DECODE = "openfoodfacts"

        private val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }

        /** `Tandem/<versionName> (+https://github.com/Demacish/tandem)`, the app's own User-Agent for Open Food Facts. */
        fun userAgent(versionName: String): String = "Tandem/$versionName (+https://github.com/Demacish/tandem)"

        /**
         * A NEW OkHttp client (not derived from Tandoor's): its own dispatcher, connection pool and
         * rate limit, no cookie jar, no cache, and only [HttpsOnlyInterceptor], [UserAgentInterceptor]
         * and the rate limit. [customize] is for tests (a trusted test certificate).
         */
        fun httpClient(userAgent: String, customize: OkHttpClient.Builder.() -> Unit = {}): OkHttpClient = OkHttpClient.Builder()
            .dispatcher(Dispatcher().apply { maxRequestsPerHost = 1 })
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .cookieJar(CookieJar.NO_COOKIES)
            .followRedirects(true)
            .followSslRedirects(false)
            .addInterceptor(HttpsOnlyInterceptor())
            .addInterceptor(UserAgentInterceptor(userAgent))
            .addInterceptor(RateLimitInterceptor(TokenBucket(permitsPerSecond = 1, burst = 2)))
            .apply(customize)
            .build()
    }
}
