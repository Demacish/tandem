package casa.moller.tandem.core.ai

import casa.moller.tandem.core.model.ai.AiKeyCheck
import casa.moller.tandem.core.model.ai.AiProvider
import casa.moller.tandem.core.model.ai.FridgeVisionError
import casa.moller.tandem.core.model.ai.FridgeVisionResult
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.core.RequestOptions
import com.anthropic.errors.AnthropicException
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.models.ErrorType
import com.anthropic.models.beta.AnthropicBeta
import com.anthropic.models.beta.messages.BetaBase64ImageSource
import com.anthropic.models.beta.messages.BetaContentBlockParam
import com.anthropic.models.beta.messages.BetaImageBlockParam
import com.anthropic.models.beta.messages.BetaJsonOutputFormat
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.MessageCreateParams
import java.time.Duration
import java.util.Base64
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Claude through Anthropic's official Java SDK (B22; never raw HTTP, never an OpenAI-compatible
 * shim). One non-streaming call on the beta Messages endpoint: the photo as a base64 image block,
 * structured output via `output_config.format` (a JSON schema, so nothing depends on reflection
 * over our own classes after R8), effort MEDIUM set explicitly, adaptive thinking (the model's
 * default: no `thinking` parameter; disabled or a budget would be a 400), `max_tokens` 16000, and
 * the server-side refusal fallbacks (`fallbacks: "default"`, beta `server-side-fallback-2026-07-01`)
 * so a classifier refusal is retried on Anthropic's recommended fallback model instead of failing.
 *
 * The SDK builds its own OkHttp client: nothing of the Tandoor client (interceptors, tokens, cookies)
 * reaches it. `maxRetries(0)`: no automatic retries (a call costs the user credits, plan 5.4). The
 * key is only in the client for this one call; errors are mapped by type and status, never by
 * message (it could echo request details).
 *
 * Fas S review (fix-data): like [AiHttpClient], every call refuses redirects (`RequestOptions`
 * `followRedirects(false)`: OkHttp would send `x-api-key` and the photo again to another host of a
 * custom base URL); a 3xx is a failed call. Leaving the page cancels a running call at once: the
 * SDK's blocking call runs beside the coroutine, and a cancellation closes the call's sockets
 * ([ClosableSockets]), so a paid request never keeps running for minutes in the background.
 */
internal class ClaudeFridgeVisionClient(
    private val timeout: Duration,
    private val tls: () -> AiTls = { AiTls.platform() },
) : FridgeVisionClient {
    @Inject
    constructor() : this(Duration.ofSeconds(AiHttpClient.CALL_TIMEOUT_SECONDS))

    override val provider: AiProvider = AiProvider.CLAUDE

    override suspend fun recognize(request: FridgeVisionRequest): FridgeVisionResult = withContext(Dispatchers.IO) {
        val sockets = ClosableSockets(tls())
        val client = client(request.key, request.baseUrl, sockets)
        try {
            val image = BetaImageBlockParam.builder()
                .source(
                    BetaBase64ImageSource.builder()
                        .data(Base64.getEncoder().encodeToString(request.jpeg))
                        .mediaType(BetaBase64ImageSource.MediaType.IMAGE_JPEG)
                        .build(),
                )
                .build()
            val params = MessageCreateParams.builder()
                .model(request.model)
                .maxTokens(FridgePrompt.MAX_OUTPUT_TOKENS)
                .system(FridgePrompt.system(request.languageTag))
                .outputConfig(
                    BetaOutputConfig.builder()
                        .effort(BetaOutputConfig.Effort.MEDIUM)
                        .format(BetaJsonOutputFormat.builder().schema(schema()).build())
                        .build(),
                )
                .addBeta(AnthropicBeta.SERVER_SIDE_FALLBACK_2026_07_01)
                .fallbacksDefault()
                .addUserMessageOfBetaContentBlockParams(
                    listOf(BetaContentBlockParam.ofImage(image), BetaContentBlockParam.ofText(FridgePrompt.USER)),
                )
                .build()
            result(abortable(sockets) { client.beta().messages().create(params, NO_REDIRECTS) })
        } catch (e: AnthropicServiceException) {
            FridgeVisionResult.Failed(failure(e))
        } catch (e: AnthropicIoException) {
            FridgeVisionResult.Failed(FridgeVisionError.Network(if (e.cause is java.io.InterruptedIOException) "timeout" else "io"))
        } catch (e: AnthropicException) {
            // The answer could not be read (an unexpected body from an own base URL, for example).
            FridgeVisionResult.Failed(FridgeVisionError.Unparseable(e.javaClass.name))
        } finally {
            client.close()
        }
    }

    override suspend fun checkKey(request: AiKeyRequest): AiKeyCheck = withContext(Dispatchers.IO) {
        val sockets = ClosableSockets(tls())
        val client = client(request.key, request.baseUrl, sockets)
        try {
            abortable(sockets) { client.models().retrieve(request.model, NO_REDIRECTS) }
            AiKeyCheck.WORKS
        } catch (e: AnthropicServiceException) {
            when (val failure = failure(e)) {
                FridgeVisionError.InvalidKey -> AiKeyCheck.INVALID_KEY
                FridgeVisionError.ModelUnavailable -> AiKeyCheck.NO_MODEL_ACCESS
                is FridgeVisionError.QuotaOrRateLimit -> AiKeyCheck.RATE_LIMITED
                is FridgeVisionError.Network -> if (failure.detail == "timeout") AiKeyCheck.NO_NETWORK else AiKeyCheck.FAILED
                else -> AiKeyCheck.FAILED
            }
        } catch (ignored: AnthropicIoException) {
            AiKeyCheck.NO_NETWORK
        } catch (ignored: AnthropicException) {
            AiKeyCheck.FAILED
        } finally {
            client.close()
        }
    }

    private fun client(key: String, baseUrl: String?, sockets: ClosableSockets): AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(key)
        .maxRetries(0)
        .timeout(timeout)
        .sslSocketFactory(sockets)
        .trustManager(sockets.trustManager)
        .apply { baseUrl?.let { baseUrl(it.trimEnd('/')) } }
        .build()

    /**
     * Runs the SDK's blocking [call] beside the coroutine: a cancellation (the page was left) closes
     * the call's sockets, which ends the blocking request at once instead of after the timeout.
     */
    private suspend fun <T> abortable(sockets: ClosableSockets, call: () -> T): T = coroutineScope {
        // runCatching inside: a failed call never cancels this scope, only a real cancellation does.
        val running = async(Dispatchers.IO) { runCatching(call) }
        try {
            running.await()
        } catch (e: CancellationException) {
            sockets.closeAll()
            throw e
        }
    }.getOrThrow()

    private fun schema(): BetaJsonOutputFormat.Schema = BetaJsonOutputFormat.Schema.builder()
        .apply { FridgePrompt.claudeSchema().forEach { (key, value) -> putAdditionalProperty(key, JsonValue.from(value)) } }
        .build()

    /** Refusal first (the content is not the answer then), then the text blocks; thinking blocks are skipped. */
    private fun result(message: BetaMessage): FridgeVisionResult {
        val stop = message.stopReason().orElse(null)
        if (stop == BetaStopReason.REFUSAL) return FridgeVisionResult.Failed(FridgeVisionError.Refusal)
        val text = message.content().mapNotNull { block -> block.text().orElse(null)?.text() }.joinToString("")
        return FridgePrompt.parse(text, truncated = stop == BetaStopReason.MAX_TOKENS)
    }

    private fun failure(e: AnthropicServiceException): FridgeVisionError {
        val retryAfter = e.headers().values("retry-after").firstOrNull()?.trim()?.toLongOrNull()
        return when (e.errorType().orElse(null)) {
            ErrorType.AUTHENTICATION_ERROR, ErrorType.PERMISSION_ERROR -> FridgeVisionError.InvalidKey

            ErrorType.NOT_FOUND_ERROR -> FridgeVisionError.ModelUnavailable

            ErrorType.RATE_LIMIT_ERROR, ErrorType.OVERLOADED_ERROR, ErrorType.BILLING_ERROR ->
                FridgeVisionError.QuotaOrRateLimit(retryAfter)

            ErrorType.TIMEOUT_ERROR -> FridgeVisionError.Network("timeout")

            else -> byStatus(e.statusCode(), retryAfter)
        }
    }

    private fun byStatus(status: Int, retryAfter: Long?): FridgeVisionError = when (status) {
        HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> FridgeVisionError.InvalidKey
        HTTP_NOT_FOUND -> FridgeVisionError.ModelUnavailable
        HTTP_TOO_MANY_REQUESTS, HTTP_OVERLOADED -> FridgeVisionError.QuotaOrRateLimit(retryAfter)
        else -> FridgeVisionError.Network("http-$status")
    }

    private companion object {
        /** Every call: no redirects (the key and the photo never go to another host). */
        val NO_REDIRECTS: RequestOptions = RequestOptions.builder().followRedirects(false).build()

        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val HTTP_NOT_FOUND = 404
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val HTTP_OVERLOADED = 529
    }
}
