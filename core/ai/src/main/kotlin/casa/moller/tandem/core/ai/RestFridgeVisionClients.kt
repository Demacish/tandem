package casa.moller.tandem.core.ai

import casa.moller.tandem.core.model.ai.AiProvider
import casa.moller.tandem.core.model.ai.FridgeVisionError
import casa.moller.tandem.core.model.ai.FridgeVisionResult
import javax.inject.Inject

// Fas S (grund lane): Gemini and OpenAI go through their REST APIs (B22) on a separate OkHttp
// client; the ai-foto lane implements them. Until then they answer Unparseable("not implemented").

internal class GeminiFridgeVisionClient @Inject constructor() : FridgeVisionClient {
    override val provider: AiProvider = AiProvider.GEMINI

    override suspend fun recognize(request: FridgeVisionRequest): FridgeVisionResult =
        FridgeVisionResult.Failed(FridgeVisionError.Unparseable(NOT_IMPLEMENTED))
}

internal class OpenAiFridgeVisionClient @Inject constructor() : FridgeVisionClient {
    override val provider: AiProvider = AiProvider.OPENAI

    override suspend fun recognize(request: FridgeVisionRequest): FridgeVisionResult =
        FridgeVisionResult.Failed(FridgeVisionError.Unparseable(NOT_IMPLEMENTED))
}

private const val NOT_IMPLEMENTED = "not implemented yet"
