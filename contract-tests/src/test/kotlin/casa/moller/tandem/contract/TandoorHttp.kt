package casa.moller.tandem.contract

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody

/** One request/response pair, kept raw (unsanitized) in memory for assertions. */
internal class Exchange(
    val name: String,
    val method: String,
    val path: String,
    val requestHeaders: List<Pair<String, String>>,
    val requestBody: JsonElement?,
    val status: Int,
    val responseHeaders: List<Pair<String, String>>,
    val bodyText: String,
) {
    val contentType: String? = header("Content-Type")

    /** The parsed body, or null when it is empty or not JSON (e.g. an HTML error page). */
    val json: JsonElement? = runCatching { Json.parseToJsonElement(bodyText) }.getOrNull()

    val jsonObject: JsonObject get() = json as? JsonObject ?: error("$name: body is not a JSON object: $bodyText")

    fun header(name: String): String? = responseHeaders.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

    override fun toString(): String = "$method $path -> $status ${bodyText.take(300)}"
}

/**
 * Raw HTTP client for the contract tests (deliberately independent of :core:network).
 * Every call is written, sanitized, to [ContractEnv.recordDir] as `NN-name.json`.
 */
internal class TandoorHttp(private val baseUrl: String, private val sanitizer: Sanitizer, private val recordDir: File) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()
    private var counter = 0

    /**
     * @param recordBody what to write to the recording for the request body (must already hide
     *   the password); the real [body] is never recorded.
     * @param record false for repetitive calls that add nothing to the fixtures.
     */
    fun call(
        name: String,
        method: String,
        path: String,
        bearer: String? = null,
        authorization: String? = bearer?.let { "Bearer $it" },
        body: RequestBody? = null,
        recordBody: JsonElement? = null,
        record: Boolean = true,
        extraHeaders: Map<String, String> = emptyMap(),
    ): Exchange {
        val request = Request.Builder()
            .url(baseUrl + path)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .apply { if (authorization != null) header("Authorization", authorization) }
            .apply { extraHeaders.forEach { (name, value) -> header(name, value) } }
            .method(method, body)
            .build()
        val requestHeaders = buildList<Pair<String, String>> {
            request.headers.forEach { add(it) }
            body?.contentType()?.let { add("Content-Type" to it.toString()) }
        }
        val exchange = client.newCall(request).execute().use { response ->
            Exchange(
                name = name,
                method = method,
                path = path,
                requestHeaders = requestHeaders,
                requestBody = recordBody,
                status = response.code,
                responseHeaders = response.headers.toList(),
                bodyText = response.body.string(),
            )
        }
        if (record) record(exchange)
        return exchange
    }

    private fun record(exchange: Exchange) {
        counter += 1
        val file = File(recordDir, "%02d-%s.json".format(counter, exchange.name))
        val recorded = buildJsonObject {
            put("name", exchange.name)
            put(
                "request",
                buildJsonObject {
                    put("method", exchange.method)
                    put("path", exchange.path)
                    put("headers", headersJson(exchange.requestHeaders))
                    exchange.requestBody?.let { put("body", sanitizer.sanitize(it)) }
                },
            )
            put(
                "response",
                buildJsonObject {
                    put("status", exchange.status)
                    put("headers", headersJson(exchange.responseHeaders))
                    put("isJson", exchange.json != null)
                    val json = exchange.json
                    if (json != null) {
                        put("body", sanitizer.sanitize(json))
                    } else {
                        put("bodyText", sanitizer.sanitize(exchange.bodyText.take(MAX_TEXT_BODY)))
                    }
                },
            )
        }
        recordDir.mkdirs()
        file.writeText(PrettyJson.encodeToString(JsonElement.serializer(), recorded) + "\n")
    }

    private fun headersJson(headers: List<Pair<String, String>>): JsonObject = buildJsonObject {
        headers
            .filterNot { it.first.lowercase() in VOLATILE_HEADERS }
            .groupBy({ it.first.lowercase() }, { it.second })
            .toSortedMap()
            .forEach { (name, values) ->
                val clean = values.map { value ->
                    // Keep only the cookie name; values and expiry dates are secret or volatile.
                    if (name == "set-cookie") value.substringBefore('=') + "=" + Sanitizer.REDACTED else sanitizer.sanitize(value)
                }
                put(name, JsonPrimitive(clean.joinToString(", ")))
            }
    }

    companion object {
        const val USER_AGENT = "Tandem-contract-tests (JVM)"
        private const val MAX_TEXT_BODY = 4000
        private val VOLATILE_HEADERS = setOf("date", "content-length", "connection", "keep-alive", "user-agent", "accept-encoding", "host")
        val PrettyJson = Json {
            prettyPrint = true
            prettyPrintIndent = "  "
        }
    }
}
