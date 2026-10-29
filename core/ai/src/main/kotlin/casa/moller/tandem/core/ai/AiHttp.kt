package casa.moller.tandem.core.ai

import casa.moller.tandem.core.model.ai.AiKeyCheck
import casa.moller.tandem.core.model.ai.FridgeVisionError
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Response

/**
 * The HTTP client of the Gemini and OpenAI calls (B22): its OWN OkHttp client, built here and shared
 * by nothing else, so no Tandoor interceptor, header, token or cookie can reach an AI provider
 * (core:ai never depends on core:network). No retries on connection failures and no redirects (the
 * key must not follow a redirect to another host); the call may take a while (a model looks at a
 * photo), so [CALL_TIMEOUT_SECONDS] is generous.
 */
@Singleton
internal class AiHttpClient @Inject constructor() {
    val okHttp: OkHttpClient by lazy { create() }

    companion object {
        const val CONNECT_TIMEOUT_SECONDS: Long = 15
        const val CALL_TIMEOUT_SECONDS: Long = 120

        fun create(callTimeoutSeconds: Long = CALL_TIMEOUT_SECONDS): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(callTimeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(callTimeoutSeconds, TimeUnit.SECONDS)
            .callTimeout(callTimeoutSeconds, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}

/** Runs the call on OkHttp's dispatcher; cancelling the coroutine cancels the call. */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, _, _ -> response.close() }
            }
        },
    )
}

/** What a failed HTTP answer means for the fridge photo (the body is read only for its error code, never logged). */
internal fun httpFailure(code: Int, body: String, retryAfter: String?): FridgeVisionError {
    val retryAfterSeconds = retryAfter?.trim()?.toLongOrNull()
    return when {
        code == HTTP_UNAUTHORIZED || (code == HTTP_FORBIDDEN && !body.mentionsModel()) -> FridgeVisionError.InvalidKey
        code == HTTP_BAD_REQUEST && body.isInvalidKey() -> FridgeVisionError.InvalidKey
        code == HTTP_NOT_FOUND || (body.mentionsModel() && code in HTTP_CLIENT_ERRORS) -> FridgeVisionError.ModelUnavailable
        code == HTTP_PAYMENT_REQUIRED || code == HTTP_TOO_MANY_REQUESTS -> FridgeVisionError.QuotaOrRateLimit(retryAfterSeconds)
        code >= HTTP_SERVER_ERRORS -> FridgeVisionError.Network("http-$code")
        else -> FridgeVisionError.Unparseable("http-$code")
    }
}

/** What a failed HTTP answer means for "Testa nyckeln". */
internal fun httpKeyCheck(code: Int, body: String): AiKeyCheck = when (val failure = httpFailure(code, body, null)) {
    FridgeVisionError.InvalidKey -> AiKeyCheck.INVALID_KEY
    FridgeVisionError.ModelUnavailable -> AiKeyCheck.NO_MODEL_ACCESS
    is FridgeVisionError.QuotaOrRateLimit -> AiKeyCheck.RATE_LIMITED
    is FridgeVisionError.Network -> if (failure.detail == "http-$HTTP_GATEWAY_TIMEOUT") AiKeyCheck.NO_NETWORK else AiKeyCheck.FAILED
    else -> AiKeyCheck.FAILED
}

/** An I/O failure: a timeout or no connection (only the kind, the message could carry the URL). */
internal fun ioFailure(error: IOException): FridgeVisionError.Network =
    FridgeVisionError.Network(if (error is InterruptedIOException) "timeout" else "io")

// Gemini answers a wrong key with 400 INVALID_ARGUMENT and the reason API_KEY_INVALID.
private fun String.isInvalidKey(): Boolean = contains("API_KEY_INVALID") || contains("API key not valid", ignoreCase = true)

// OpenAI: {"error": {"code": "model_not_found"}}; Gemini: "models/x is not found".
private fun String.mentionsModel(): Boolean = contains("model_not_found") || (contains("does not exist") && contains("model"))

private const val HTTP_BAD_REQUEST = 400
private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_PAYMENT_REQUIRED = 402
private const val HTTP_FORBIDDEN = 403
private const val HTTP_NOT_FOUND = 404
private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_SERVER_ERRORS = 500
private const val HTTP_GATEWAY_TIMEOUT = 504
private val HTTP_CLIENT_ERRORS = 400..499

/** A plain Kotlin value (maps, lists, strings, numbers, booleans, null) as JSON. */
internal fun Any?.toJson(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is String -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is Map<*, *> -> JsonObject(entries.associate { (key, value) -> key.toString() to value.toJson() })
    is List<*> -> JsonArray(map { it.toJson() })
    else -> error("not JSON: ${this::class.simpleName}")
}
