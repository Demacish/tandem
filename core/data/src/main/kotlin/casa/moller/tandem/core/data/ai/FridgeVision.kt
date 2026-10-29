package casa.moller.tandem.core.data.ai

import casa.moller.tandem.core.model.ai.AiKeyCheck
import casa.moller.tandem.core.model.ai.FridgeVisionResult

/**
 * The fridge photo (#45, EXPERIMENTAL, B22): photo bytes to the user's chosen AI provider ->
 * recognised items, or a typed error ([casa.moller.tandem.core.model.ai.FridgeVisionError]: no
 * key, invalid key, quota/rate limit, refusal, network, unparseable, demo mode). The result always
 * becomes a review list (never saved without the user). The photo is scaled to at most 1600 px,
 * sent only to the chosen provider and not kept. No automatic retries (a call costs credits, plan
 * 5.4). Implemented in :core:ai (ai-foto lane): Claude through Anthropic's official Java SDK,
 * Gemini and OpenAI through their REST APIs, on a separate OkHttp client without Tandoor headers.
 */
interface FridgeVision {
    /**
     * @param jpeg the photo as JPEG, already scaled (the caller scales; core:ai checks the size).
     * @param languageTag the UI language (BCP 47), so item names come back in it ("sv", "en").
     */
    suspend fun recognize(jpeg: ByteArray, languageTag: String): FridgeVisionResult

    /**
     * "Testa nyckeln": checks the stored key against the chosen provider and model with a free call
     * (the provider's model lookup); never sends a photo. Demo mode answers [AiKeyCheck.DEMO_MODE]
     * without a request.
     */
    suspend fun checkKey(): AiKeyCheck
}
