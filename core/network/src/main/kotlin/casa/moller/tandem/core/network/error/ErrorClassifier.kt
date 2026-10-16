package casa.moller.tandem.core.network.error

import casa.moller.tandem.core.model.error.NotTandoorReason
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.error.TlsErrorKind
import casa.moller.tandem.core.model.error.UnauthorizedSource
import casa.moller.tandem.core.network.http.TandemIOException
import casa.moller.tandem.core.network.json.TandoorJson
import java.io.IOException
import java.io.InterruptedIOException
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.time.Clock
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toKotlinDuration
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.MissingFieldException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Headers
import okhttp3.MediaType
import okhttp3.Response
import retrofit2.HttpException

/**
 * Maps responses and exceptions to [TandemError], following the contract facts observed on
 * Tandoor 2.6.13 (`api-schema/fixtures/2.6.13/FACTS.md`):
 *
 * | Response | Error |
 * |---|---|
 * | 2xx HTML | [NotTandoorReason.HTML_RESPONSE] |
 * | 3xx to `/accounts/login/` | [NotTandoorReason.LOGIN_REDIRECT] (unknown `/api/` path) |
 * | other 3xx | [NotTandoorReason.REDIRECT] |
 * | 401 | [TandemError.Unauthorized] from a proxy (Tandoor never sends 401) |
 * | 403 + JSON object with `detail` | [TandemError.Unauthorized] (missing/invalid/expired/revoked token) |
 * | 403 otherwise | [TandemError.Blocked] (firewall, WAF or auth proxy with its own body, plan 4.1) |
 * | 404 + JSON object | [TandemError.NotFound] |
 * | 404 without JSON | [NotTandoorReason.UNEXPECTED_STATUS] |
 * | 400 | [TandemError.BadRequest] with DRF field errors |
 * | 429 | [TandemError.Throttled] with `Retry-After` |
 * | 5xx | [TandemError.ServerError] |
 *
 * The `detail` texts are localized by the server (`Accept-Language`), so nothing here matches
 * on message text.
 */
class ErrorClassifier(private val clock: Clock = Clock.systemUTC()) {

    /**
     * Classifies a failed call. Never pass a `CancellationException`; rethrow it instead.
     *
     * Anything else becomes [TandemError.Unknown] with the fully qualified class name: in a
     * release build R8 renames app and library classes, and only a full name can be retraced
     * with the published mapping file.
     */
    fun classify(throwable: Throwable): TandemError = when (throwable) {
        is TandemIOException -> throwable.error
        is HttpException -> classify(throwable)
        is SerializationException -> TandemError.Decode(decodeDetail(throwable))
        is IOException -> classifyIo(throwable)
        else -> TandemError.Unknown(throwable.javaClass.name)
    }

    /** Classifies a Retrofit [HttpException] (any non-2xx response). */
    fun classify(exception: HttpException): TandemError {
        val response = exception.response()
        val body = response?.errorBody()
        val text = body?.let { runCatching { it.string() }.getOrNull() }
        return classifyHttp(
            status = exception.code(),
            headers = response?.headers() ?: Headers.Builder().build(),
            contentType = body?.contentType(),
            bodyText = text,
        ) ?: TandemError.Unknown("HTTP ${exception.code()}")
    }

    /**
     * Classifies a raw OkHttp response. Reads (and so consumes) the body. Returns null when the
     * response is a usable success.
     */
    fun classify(response: Response): TandemError? {
        val text = runCatching { response.body.string() }.getOrNull()
        return classifyHttp(response.code, response.headers, response.body.contentType(), text)
    }

    /** Pure status/header/body classification; null means "success, go ahead and parse". */
    fun classifyHttp(status: Int, headers: Headers, contentType: MediaType?, bodyText: String?): TandemError? {
        val json = parseJsonOrNull(bodyText)
        val isJsonObject = json is JsonObject
        // Tandoor's own refusals always carry `detail` (FACTS "Konsekvenser" 1); a proxy's JSON does not.
        val hasDetail = (json as? JsonObject)?.get("detail") is JsonPrimitive
        return when {
            status in 200..299 ->
                if (looksLikeHtml(contentType, bodyText)) {
                    TandemError.NotTandoor(NotTandoorReason.HTML_RESPONSE, status)
                } else {
                    null
                }

            status in 300..399 -> {
                val location = headers["Location"].orEmpty()
                val reason = if (location.contains(LOGIN_PATH)) {
                    NotTandoorReason.LOGIN_REDIRECT
                } else {
                    NotTandoorReason.REDIRECT
                }
                TandemError.NotTandoor(reason, status)
            }

            status == 401 -> TandemError.Unauthorized(UnauthorizedSource.PROXY)

            status == 403 ->
                if (hasDetail) TandemError.Unauthorized(UnauthorizedSource.TANDOOR) else TandemError.Blocked

            status == 404 ->
                if (isJsonObject) {
                    TandemError.NotFound
                } else {
                    TandemError.NotTandoor(NotTandoorReason.UNEXPECTED_STATUS, status)
                }

            status == 400 -> TandemError.BadRequest(fieldErrors(json))

            status == 429 -> TandemError.Throttled(parseRetryAfter(headers["Retry-After"]))

            status in 500..599 -> TandemError.ServerError(status)

            else -> TandemError.Unknown("HTTP $status")
        }
    }

    /** `Retry-After` in seconds (what Tandoor sends) or as an HTTP date. */
    fun parseRetryAfter(value: String?): Duration? {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return null
        text.toLongOrNull()?.let { return if (it >= 0) it.seconds else null }
        return try {
            val date = ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME)
            val delta = java.time.Duration.between(clock.instant(), date.toInstant())
            if (delta.isNegative) Duration.ZERO else delta.toKotlinDuration()
        } catch (e: DateTimeParseException) {
            null
        }
    }

    private fun classifyIo(e: IOException): TandemError {
        // With several routes (IPv6 and IPv4, OkHttp's fast fallback) one failure is thrown and
        // the others are attached as suppressed exceptions. A TLS failure on any route is the
        // actionable one: "connection refused" on ::1 must not hide an untrusted certificate.
        val tls = e.relatedThrowables().firstOrNull { it is SSLException }
        return when {
            tls is SSLPeerUnverifiedException -> TandemError.TlsError(TlsErrorKind.HOSTNAME_MISMATCH)

            tls != null ->
                if (tls.hasCause<CertificateException>() || tls.hasCause<CertPathValidatorException>()) {
                    TandemError.TlsError(TlsErrorKind.UNTRUSTED_CERTIFICATE)
                } else {
                    TandemError.TlsError(TlsErrorKind.HANDSHAKE_FAILED)
                }

            e is UnknownHostException -> TandemError.UnknownHost

            // SocketTimeoutException (connect/read/write) and OkHttp's call timeout ("timeout").
            e is InterruptedIOException -> TandemError.Timeout

            else -> TandemError.NetworkUnavailable
        }
    }

    /** This throwable, its causes and suppressed exceptions (breadth first, bounded). */
    private fun Throwable.relatedThrowables(): List<Throwable> {
        val seen = mutableListOf<Throwable>()
        val queue = mutableListOf(this)
        var index = 0
        while (index < queue.size && seen.size < MAX_RELATED) {
            val next = queue[index++]
            if (seen.any { it === next }) continue
            seen += next
            next.cause?.let { queue += it }
            queue += next.suppressed
        }
        return seen
    }

    private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean {
        var current: Throwable? = this
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            if (current is T) return true
            current = current.cause
            depth++
        }
        return false
    }

    companion object {
        private const val LOGIN_PATH = "/accounts/login"
        private const val MAX_CAUSE_DEPTH = 10
        private const val MAX_RELATED = 32

        /** Shared instance with the system clock. */
        val Default: ErrorClassifier = ErrorClassifier()

        /** `at path: $.test` or `$[0].token` in kotlinx.serialization messages; never a value. */
        private val JSON_PATH = Regex("""at path: (\$[A-Za-z0-9_.\[\]]*)""")

        /**
         * Detail for a JSON decoding error: the exception type, missing field names and the JSON
         * path, never the message itself. kotlinx.serialization quotes the offending input
         * ("JSON input: {...}", sometimes the literal before the path), which for a login or
         * token response contains the token in plaintext. TandemError must never carry it.
         */
        @OptIn(ExperimentalSerializationApi::class)
        internal fun decodeDetail(e: SerializationException): String {
            // Type checks, not class names: R8 renames kotlinx.serialization's exceptions in a
            // release build, so `e::class.simpleName` would be "fe1" there. JsonDecodingException
            // is internal and reported as the SerializationException it extends.
            val missing = e as? MissingFieldException
            val fields = missing?.missingFields?.takeIf { it.isNotEmpty() }?.joinToString(", ")
            val path = JSON_PATH.find(e.message.orEmpty())?.groupValues?.get(1)
            return listOfNotNull(
                if (missing != null) "MissingFieldException" else "SerializationException",
                fields?.let { "missing $it" },
                path?.let { "at $it" },
            ).joinToString(" ")
        }

        internal fun parseJsonOrNull(text: String?): JsonElement? {
            if (text.isNullOrBlank()) return null
            return try {
                TandoorJson.parseToJsonElement(text)
            } catch (e: SerializationException) {
                null
            }
        }

        private fun looksLikeHtml(contentType: MediaType?, bodyText: String?): Boolean = contentType?.subtype == "html" ||
            bodyText?.trimStart()?.let { it.startsWith("<!") || it.startsWith("<html", ignoreCase = true) } == true

        /** DRF errors: `{"field": ["msg", ...]}`, sometimes a single string or a nested object. */
        internal fun fieldErrors(json: JsonElement?): Map<String, List<String>> {
            val obj = json as? JsonObject ?: return emptyMap()
            return obj.mapValues { (_, value) -> messages(value) }
        }

        private fun messages(value: JsonElement): List<String> = when (value) {
            is JsonNull -> emptyList()
            is JsonPrimitive -> listOfNotNull(value.contentOrNull)
            is JsonArray -> value.flatMap { messages(it) }
            is JsonObject -> listOf(value.toString())
        }
    }
}
