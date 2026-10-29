package casa.moller.tandem.core.ai

import casa.moller.tandem.core.model.ai.AiProvider
import casa.moller.tandem.core.model.ai.FridgeVisionError
import casa.moller.tandem.core.model.ai.FridgeVisionResult
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import java.time.Duration
import java.util.Base64
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Claude through Anthropic's official Java SDK (B22). A first implementation the ai-foto lane builds
 * on (tests against an in-process fake server, the fallback on a refusal, effort). The SDK makes
 * its own OkHttp client: nothing of the Tandoor client (interceptors, tokens, cookies) reaches it.
 * `maxRetries(0)`: no automatic retries (a call costs the user credits, plan 5.4).
 */
internal class ClaudeFridgeVisionClient @Inject constructor() : FridgeVisionClient {
    override val provider: AiProvider = AiProvider.CLAUDE

    override suspend fun recognize(request: FridgeVisionRequest): FridgeVisionResult = withContext(Dispatchers.IO) {
        val client = AnthropicOkHttpClient.builder()
            .apiKey(request.key)
            .maxRetries(0)
            .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .apply { request.baseUrl?.let { baseUrl(it) } }
            .build()
        try {
            val image = ImageBlockParam.builder()
                .source(
                    Base64ImageSource.builder()
                        .data(Base64.getEncoder().encodeToString(request.jpeg))
                        .mediaType(Base64ImageSource.MediaType.IMAGE_JPEG)
                        .build(),
                )
                .build()
            val params = MessageCreateParams.builder()
                .model(request.model)
                .maxTokens(MAX_TOKENS)
                .system(FridgePrompt.system(request.languageTag))
                .addUserMessageOfBlockParams(
                    listOf(
                        ContentBlockParam.ofImage(image),
                        ContentBlockParam.ofText(TextBlockParam.builder().text(FridgePrompt.USER).build()),
                    ),
                )
                .build()
            val message = client.messages().create(params)
            if (message.stopReason().orElse(null) == StopReason.REFUSAL) {
                FridgeVisionResult.Failed(FridgeVisionError.Refusal)
            } else {
                FridgePrompt.parse(message.content().mapNotNull { block -> block.text().orElse(null)?.text() }.joinToString("\n"))
            }
        } catch (ignored: UnauthorizedException) {
            FridgeVisionResult.Failed(FridgeVisionError.InvalidKey)
        } catch (ignored: PermissionDeniedException) {
            FridgeVisionResult.Failed(FridgeVisionError.InvalidKey)
        } catch (ignored: RateLimitException) {
            FridgeVisionResult.Failed(FridgeVisionError.QuotaOrRateLimit())
        } catch (error: AnthropicServiceException) {
            // Only the status: the message could echo request details.
            FridgeVisionResult.Failed(FridgeVisionError.Network("http-${error.statusCode()}"))
        } catch (ignored: AnthropicIoException) {
            FridgeVisionResult.Failed(FridgeVisionError.Network("io"))
        } finally {
            client.close()
        }
    }

    private companion object {
        const val TIMEOUT_SECONDS = 120L
        const val MAX_TOKENS = 4096L
    }
}
