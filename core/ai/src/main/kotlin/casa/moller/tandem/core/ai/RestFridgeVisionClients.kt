package casa.moller.tandem.core.ai

import casa.moller.tandem.core.model.ai.AiKeyCheck
import casa.moller.tandem.core.model.ai.AiProvider
import casa.moller.tandem.core.model.ai.FridgeVisionError
import casa.moller.tandem.core.model.ai.FridgeVisionResult
import java.io.IOException
import java.util.Base64
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

// Fas S (ai-foto lane, B22): Gemini and OpenAI through their official REST APIs on core:ai's own
// OkHttp client (AiHttpClient: no Tandoor interceptor, header, token or cookie) and
// kotlinx.serialization's JSON tree (no DTO classes for R8 to rename). Request and response shapes
// are from the providers' API documentation (docs/BYGGE.md "Foto av kylen" lists the URLs). Both
// ask for the same structured result as Claude (FridgePrompt).

private val JSON_MEDIA = "application/json".toMediaType()
private val json = Json { ignoreUnknownKeys = true }

/** A POST/GET answer: status and body (bounded by the call timeout); the key is never part of it. */
private class HttpAnswer(val code: Int, val body: String, val retryAfter: String?)

private suspend fun OkHttpClient.send(request: Request): HttpAnswer = newCall(request).await().use { response ->
    HttpAnswer(response.code, response.body.string(), response.header("Retry-After"))
}

/** [base] (the official API or the user's own, e.g. a proxy) plus [segments]; null when [base] is not a URL. */
private fun url(base: String, vararg segments: String): HttpUrl? = base.trimEnd('/').toHttpUrlOrNull()?.newBuilder()?.apply {
    segments.forEach { addPathSegment(it) }
}?.build()

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/**
 * Gemini `models.generateContent` with the photo as `inlineData` and a JSON response schema
 * (`generationConfig.responseMimeType` + `responseSchema`); the key goes in `x-goog-api-key`.
 * "Testa nyckeln" = `models.get` (free).
 */
internal class GeminiFridgeVisionClient(private val http: OkHttpClient) : FridgeVisionClient {
    @Inject
    constructor(client: AiHttpClient) : this(client.okHttp)

    override val provider: AiProvider = AiProvider.GEMINI

    override suspend fun recognize(request: FridgeVisionRequest): FridgeVisionResult {
        val url = url(request.baseUrl ?: AiProvider.GEMINI.officialBaseUrl, "models", "${request.model}:generateContent")
            ?: return FridgeVisionResult.Failed(FridgeVisionError.Network("base-url"))
        val body = mapOf(
            "systemInstruction" to mapOf("parts" to listOf(mapOf("text" to FridgePrompt.system(request.languageTag)))),
            "contents" to listOf(
                mapOf(
                    "role" to "user",
                    "parts" to listOf(
                        mapOf("inlineData" to mapOf("mimeType" to "image/jpeg", "data" to request.jpeg.base64())),
                        mapOf("text" to FridgePrompt.USER),
                    ),
                ),
            ),
            "generationConfig" to mapOf(
                "responseMimeType" to "application/json",
                "responseSchema" to FridgePrompt.geminiSchema(),
                "maxOutputTokens" to FridgePrompt.MAX_OUTPUT_TOKENS,
            ),
        ).toJson().toString()
        val answer = try {
            http.send(Request.Builder().url(url).header(KEY_HEADER, request.key).post(body.toRequestBody(JSON_MEDIA)).build())
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            return FridgeVisionResult.Failed(ioFailure(e))
        }
        if (answer.code !in HTTP_OK) return FridgeVisionResult.Failed(httpFailure(answer.code, answer.body, answer.retryAfter))
        return parse(answer.body)
    }

    private fun parse(body: String): FridgeVisionResult {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: return FridgeVisionResult.Failed(FridgeVisionError.Unparseable("not JSON"))
        if (root.obj("promptFeedback")?.text("blockReason") != null) return FridgeVisionResult.Failed(FridgeVisionError.Refusal)
        val candidate = root.array("candidates")?.firstOrNull() as? JsonObject
            ?: return FridgeVisionResult.Failed(FridgeVisionError.Refusal)
        val finish = candidate.text("finishReason")
        val text = candidate.obj("content")?.array("parts").orEmpty()
            .mapNotNull { it as? JsonObject }
            // Thought summaries (when a model thinks) are not the answer.
            .filterNot { (it["thought"] as? JsonPrimitive)?.booleanOrNull == true }
            .mapNotNull { it.text("text") }
            .joinToString("")
        if (finish in BLOCKED && text.isBlank()) return FridgeVisionResult.Failed(FridgeVisionError.Refusal)
        return FridgePrompt.parse(text, truncated = finish == "MAX_TOKENS")
    }

    override suspend fun checkKey(request: AiKeyRequest): AiKeyCheck {
        val url = url(request.baseUrl ?: AiProvider.GEMINI.officialBaseUrl, "models", request.model) ?: return AiKeyCheck.NO_NETWORK
        return try {
            val answer = http.send(Request.Builder().url(url).header(KEY_HEADER, request.key).get().build())
            if (answer.code in HTTP_OK) AiKeyCheck.WORKS else httpKeyCheck(answer.code, answer.body)
        } catch (e: CancellationException) {
            throw e
        } catch (ignored: IOException) {
            AiKeyCheck.NO_NETWORK
        }
    }

    private companion object {
        const val KEY_HEADER = "x-goog-api-key"

        /** FinishReason values that mean the answer was withheld (safety, policy). */
        val BLOCKED = setOf(
            "SAFETY",
            "PROHIBITED_CONTENT",
            "BLOCKLIST",
            "SPII",
            "IMAGE_SAFETY",
            "IMAGE_PROHIBITED_CONTENT",
            "RECITATION",
            "OTHER",
        )
    }
}

/**
 * OpenAI Responses API (`POST /responses`) with the photo as an `input_image` data URL and a strict
 * JSON schema (`text.format`), `store: false` (the photo is not kept for later turns); the key is a
 * Bearer token. "Testa nyckeln" = `GET /models/{model}` (free).
 */
internal class OpenAiFridgeVisionClient(private val http: OkHttpClient) : FridgeVisionClient {
    @Inject
    constructor(client: AiHttpClient) : this(client.okHttp)

    override val provider: AiProvider = AiProvider.OPENAI

    override suspend fun recognize(request: FridgeVisionRequest): FridgeVisionResult {
        val url = url(request.baseUrl ?: AiProvider.OPENAI.officialBaseUrl, "responses")
            ?: return FridgeVisionResult.Failed(FridgeVisionError.Network("base-url"))
        val image = "data:image/jpeg;base64," + request.jpeg.base64()
        val body = mapOf(
            "model" to request.model,
            "instructions" to FridgePrompt.system(request.languageTag),
            "input" to listOf(
                mapOf(
                    "role" to "user",
                    "content" to listOf(
                        mapOf("type" to "input_text", "text" to FridgePrompt.USER),
                        mapOf("type" to "input_image", "image_url" to image, "detail" to "auto"),
                    ),
                ),
            ),
            "text" to mapOf(
                "format" to mapOf(
                    "type" to "json_schema",
                    "name" to "fridge_items",
                    "schema" to FridgePrompt.openAiSchema(),
                    "strict" to true,
                ),
            ),
            "max_output_tokens" to FridgePrompt.MAX_OUTPUT_TOKENS,
            "store" to false,
        ).toJson().toString()
        val answer = try {
            val post = Request.Builder().url(url).header(AUTHORIZATION, "Bearer ${request.key}").post(body.toRequestBody(JSON_MEDIA))
            http.send(post.build())
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            return FridgeVisionResult.Failed(ioFailure(e))
        }
        if (answer.code !in HTTP_OK) return FridgeVisionResult.Failed(httpFailure(answer.code, answer.body, answer.retryAfter))
        return parse(answer.body)
    }

    private fun parse(body: String): FridgeVisionResult {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: return FridgeVisionResult.Failed(FridgeVisionError.Unparseable("not JSON"))
        val incomplete = root.text("status") == "incomplete"
        val reason = root.obj("incomplete_details")?.text("reason")
        val content = root.array("output").orEmpty()
            .mapNotNull { it as? JsonObject }
            .filter { it.text("type") == "message" }
            .flatMap { it.array("content").orEmpty() }
            .mapNotNull { it as? JsonObject }
        if (content.any { it.text("type") == "refusal" }) return FridgeVisionResult.Failed(FridgeVisionError.Refusal)
        if (incomplete && reason == "content_filter") return FridgeVisionResult.Failed(FridgeVisionError.Refusal)
        val text = content.filter { it.text("type") == "output_text" }.mapNotNull { it.text("text") }.joinToString("")
        return FridgePrompt.parse(text, truncated = incomplete && reason == "max_output_tokens")
    }

    override suspend fun checkKey(request: AiKeyRequest): AiKeyCheck {
        val url = url(request.baseUrl ?: AiProvider.OPENAI.officialBaseUrl, "models", request.model) ?: return AiKeyCheck.NO_NETWORK
        return try {
            val answer = http.send(Request.Builder().url(url).header(AUTHORIZATION, "Bearer ${request.key}").get().build())
            if (answer.code in HTTP_OK) AiKeyCheck.WORKS else httpKeyCheck(answer.code, answer.body)
        } catch (e: CancellationException) {
            throw e
        } catch (ignored: IOException) {
            AiKeyCheck.NO_NETWORK
        }
    }
}

private val HTTP_OK = 200..299

private const val AUTHORIZATION = "Authorization"

private fun ByteArray.base64(): String = Base64.getEncoder().encodeToString(this)
