package casa.moller.tandem.core.network.miniflux

import casa.moller.tandem.core.model.error.InvalidUrlReason
import casa.moller.tandem.core.model.error.NotTandoorReason
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.error.UnauthorizedSource
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
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.CookieJar
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer

// Fas 5 (rss lane, plan §7 #35, §4.5, §5.4, §5.5): the recipe inbox's Miniflux client. Generic
// (any Miniflux the user points it at), with its OWN OkHttp client like Open Food Facts: no Tandoor
// interceptor, header, token or cookie can reach it, and its requests never share Tandoor's two
// slots or rate budget. The API key goes in `X-Auth-Token`, only to the address the user entered.

/**
 * The Miniflux API calls the inbox needs. Every call takes the server ([server], from [normalize])
 * and the user's API key; every failure is a typed [Outcome.Failure]:
 *
 * | Answer | Error |
 * |---|---|
 * | 401, or 403 with Miniflux's JSON `error_message` | [TandemError.Unauthorized] (the key) |
 * | 403 otherwise | [TandemError.Blocked] (a firewall or proxy) |
 * | 3xx (never followed: a login page of a proxy) | [NotTandoorReason.REDIRECT] |
 * | 2xx HTML or a body that is not the expected JSON | [NotTandoorReason.HTML_RESPONSE] / [TandemError.Decode] |
 * | 404 with JSON | [TandemError.NotFound]; without JSON [NotTandoorReason.UNEXPECTED_STATUS] |
 * | 400 / 429 / 5xx | [TandemError.BadRequest] / [TandemError.Throttled] / [TandemError.ServerError] |
 * | a body over [MAX_BODY_BYTES] | [TandemError.Decode] (nothing is read past the limit) |
 */
class MinifluxClient(private val client: OkHttpClient) {

    /** `GET /v1/me`: does the key work. */
    suspend fun me(server: HttpUrl, apiKey: String): Outcome<MinifluxUserDto> =
        get(server, apiKey, "v1/me", MinifluxUserDto.serializer())

    /** `GET /v1/categories?counts=true`. */
    suspend fun categories(server: HttpUrl, apiKey: String): Outcome<List<MinifluxCategoryDto>> =
        get(server, apiKey, "v1/categories", ListSerializer(MinifluxCategoryDto.serializer()), "counts" to "true")

    /** `GET /v1/categories/{id}/entries?status=unread&order=published_at&direction=desc&limit=[limit]`. */
    suspend fun unreadEntries(server: HttpUrl, apiKey: String, categoryId: Long, limit: Int = PAGE_SIZE): Outcome<MinifluxEntriesDto> =
        get(
            server,
            apiKey,
            "v1/categories/$categoryId/entries",
            MinifluxEntriesDto.serializer(),
            "status" to "unread",
            "order" to "published_at",
            "direction" to "desc",
            "limit" to limit.coerceIn(1, PAGE_SIZE).toString(),
        )

    /** `PUT /v1/entries` `{"entry_ids": [...], "status": "read"}` (204). */
    suspend fun markRead(server: HttpUrl, apiKey: String, entryIds: List<Long>): Outcome<Unit> {
        if (entryIds.isEmpty()) return Outcome.Success(Unit)
        val body = json.encodeToString(MinifluxEntryStatusDto.serializer(), MinifluxEntryStatusDto(entryIds.distinct(), STATUS_READ))
        val request = request(server, apiKey, "v1/entries") ?: return Outcome.Failure(TandemError.Unauthorized(UnauthorizedSource.PROXY))
        return send(request.put(body.toRequestBody(JSON)).build()) { Unit }
    }

    private suspend fun <T> get(
        server: HttpUrl,
        apiKey: String,
        path: String,
        serializer: KSerializer<T>,
        vararg query: Pair<String, String>,
    ): Outcome<T> {
        val builder = request(server, apiKey, path, *query) ?: return Outcome.Failure(TandemError.Unauthorized(UnauthorizedSource.PROXY))
        return send(builder.get().build()) { text -> json.decodeFromString(serializer, text) }
    }

    /** The request with the key, or null when the key cannot be a header (nothing is sent then). */
    private fun request(server: HttpUrl, apiKey: String, path: String, vararg query: Pair<String, String>): Request.Builder? {
        val key = apiKey.trim()
        if (key.isEmpty() || !key.all { it in KEY_CHARS }) return null
        val url = server.newBuilder().addPathSegments(path).apply { query.forEach { (name, value) -> addQueryParameter(name, value) } }.build()
        return Request.Builder().url(url).header(HEADER_KEY, key).header("Accept", "application/json")
    }

    /** Sends on IO (the body is read there), classifies, then parses a success with [parse]. */
    private suspend fun <T> send(request: Request, parse: (String) -> T): Outcome<T> {
        val answer = withContext(Dispatchers.IO) {
            apiCall { client.newCall(request).await().use { response -> response.toRaw() } }
        }
        val raw = when (answer) {
            is Outcome.Failure -> return answer
            is Outcome.Success -> answer.value
        }
        classify(raw)?.let { return Outcome.Failure(it) }
        return try {
            Outcome.Success(parse(raw.text))
        } catch (e: SerializationException) {
            Outcome.Failure(TandemError.Decode(DECODE))
        } catch (e: IllegalArgumentException) {
            Outcome.Failure(TandemError.Decode(DECODE))
        }
    }

    private fun Response.toRaw(): RawAnswer {
        val source = body.source()
        // Never more than the limit in memory: a feed with huge posts, or something that is not Miniflux.
        val buffer = Buffer()
        var tooLarge = false
        while (!source.exhausted()) {
            source.read(buffer, READ_CHUNK)
            if (buffer.size > MAX_BODY_BYTES) {
                tooLarge = true
                break
            }
        }
        return RawAnswer(
            code = code,
            contentType = body.contentType()?.subtype,
            retryAfter = header("Retry-After"),
            location = header("Location"),
            text = if (tooLarge) "" else buffer.readUtf8(),
            tooLarge = tooLarge,
        )
    }

    private class RawAnswer(
        val code: Int,
        val contentType: String?,
        val retryAfter: String?,
        val location: String?,
        val text: String,
        val tooLarge: Boolean,
    )

    private fun classify(raw: RawAnswer): TandemError? {
        val hasErrorMessage = raw.contentType == "json" && runCatching {
            (json.parseToJsonElement(raw.text) as? JsonObject)?.get("error_message") is JsonPrimitive
        }.getOrDefault(false)
        val isJson = raw.contentType == "json"
        return when {
            raw.tooLarge -> TandemError.Decode(TOO_LARGE)
            raw.code in 200..299 -> if (raw.contentType == "html") TandemError.NotTandoor(NotTandoorReason.HTML_RESPONSE, raw.code) else null
            raw.code in 300..399 -> TandemError.NotTandoor(NotTandoorReason.REDIRECT, raw.code)
            raw.code == 401 -> TandemError.Unauthorized(UnauthorizedSource.PROXY)
            raw.code == 403 -> if (hasErrorMessage) TandemError.Unauthorized(UnauthorizedSource.PROXY) else TandemError.Blocked
            raw.code == 404 -> if (isJson) TandemError.NotFound else TandemError.NotTandoor(NotTandoorReason.UNEXPECTED_STATUS, raw.code)
            raw.code == 400 -> TandemError.BadRequest()
            raw.code == 429 -> TandemError.Throttled(ErrorClassifier.Default.parseRetryAfter(raw.retryAfter))
            raw.code in 500..599 -> TandemError.ServerError(raw.code)
            else -> TandemError.Unknown("miniflux HTTP ${raw.code}")
        }
    }

    companion object {
        /** The header Miniflux reads the API key from. */
        const val HEADER_KEY: String = "X-Auth-Token"

        /** Posts read per category and refresh (newest unread first). */
        const val PAGE_SIZE: Int = 50

        /** The largest answer read (50 posts with long texts stay well below it). */
        const val MAX_BODY_BYTES: Long = 4L * 1024 * 1024

        const val CONNECT_TIMEOUT_SECONDS: Long = 10
        const val READ_TIMEOUT_SECONDS: Long = 20
        const val CALL_TIMEOUT_SECONDS: Long = 30

        /** At most this many requests per second to the Miniflux server (own bucket). */
        const val PERMITS_PER_SECOND: Int = 2

        private const val READ_CHUNK = 64L * 1024
        private const val STATUS_READ = "read"
        private const val DECODE = "miniflux"
        private const val TOO_LARGE = "miniflux-too-large"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /** Printable ASCII without the space: what an API key can consist of. */
        private val KEY_CHARS = '!'..'~'

        private val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            explicitNulls = false
        }

        /**
         * The Miniflux address the user typed, as the API's base (https, no query, trailing slash),
         * or [TandemError.InvalidUrl]. A missing scheme means https; `http://` is refused (plan §5.5:
         * no cleartext); a pasted `/v1/...` path is cut off; a subfolder (`https://example.com/rss/`) stays.
         */
        fun normalize(input: String): Outcome<HttpUrl> {
            val text = input.trim()
            if (text.isEmpty()) return Outcome.Failure(TandemError.InvalidUrl(InvalidUrlReason.EMPTY))
            val lower = text.lowercase()
            val withScheme = when {
                lower.startsWith("http://") -> return Outcome.Failure(TandemError.InvalidUrl(InvalidUrlReason.CLEARTEXT_NOT_ALLOWED))
                lower.startsWith("https://") -> text
                "://" in text -> return Outcome.Failure(TandemError.InvalidUrl(InvalidUrlReason.UNSUPPORTED_SCHEME))
                else -> "https://$text"
            }
            val url = withScheme.toHttpUrlOrNull() ?: return Outcome.Failure(TandemError.InvalidUrl(InvalidUrlReason.MALFORMED))
            if (url.host.isEmpty() || url.username.isNotEmpty() || url.password.isNotEmpty()) {
                return Outcome.Failure(TandemError.InvalidUrl(InvalidUrlReason.MALFORMED))
            }
            val segments = url.pathSegments.filter { it.isNotEmpty() }
            val apiAt = segments.indexOf("v1")
            val kept = if (apiAt >= 0) segments.take(apiAt) else segments
            val base = url.newBuilder().query(null).fragment(null).encodedPath("/")
                .apply { kept.forEach { addPathSegment(it) } }
                .build()
            val withSlash = if (base.encodedPath.endsWith("/")) base else base.newBuilder().addPathSegment("").build()
            return Outcome.Success(withSlash)
        }

        /** `Tandem/<versionName> (+https://github.com/Demacish/tandem)`, the app's own User-Agent. */
        fun userAgent(versionName: String): String = "Tandem/$versionName (+https://github.com/Demacish/tandem)"

        /**
         * A NEW OkHttp client (not derived from Tandoor's): its own dispatcher (one request per host
         * at a time), connection pool and rate limit ([PERMITS_PER_SECOND]), no cookies, no cache,
         * no redirects (a proxy's login page is an error, not a 200), HTTPS only. [customize] is for
         * tests (a trusted test certificate).
         */
        fun httpClient(userAgent: String, customize: OkHttpClient.Builder.() -> Unit = {}): OkHttpClient = OkHttpClient.Builder()
            .dispatcher(Dispatcher().apply { maxRequestsPerHost = 1 })
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .cookieJar(CookieJar.NO_COOKIES)
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor(HttpsOnlyInterceptor())
            .addInterceptor(UserAgentInterceptor(userAgent))
            .addInterceptor(RateLimitInterceptor(TokenBucket(permitsPerSecond = PERMITS_PER_SECOND, burst = 3)))
            .apply(customize)
            .build()
    }
}
