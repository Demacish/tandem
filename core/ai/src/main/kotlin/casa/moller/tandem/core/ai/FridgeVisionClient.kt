package casa.moller.tandem.core.ai

import casa.moller.tandem.core.model.ai.AiProvider
import casa.moller.tandem.core.model.ai.FridgeVisionResult

/**
 * One AI provider's call for the fridge photo (ai-foto lane). [request] carries the user's key only
 * for the duration of the call; an implementation never logs it, never keeps it and never puts it
 * in an exception message.
 */
internal interface FridgeVisionClient {
    val provider: AiProvider

    suspend fun recognize(request: FridgeVisionRequest): FridgeVisionResult
}

/**
 * @property baseUrl the user's own endpoint, null = the provider's official API.
 * @property jpeg the scaled photo (at most [FridgeVisionRequest.MAX_EDGE_PX] on the long edge).
 */
internal class FridgeVisionRequest(
    val key: String,
    val model: String,
    val baseUrl: String?,
    val jpeg: ByteArray,
    val languageTag: String,
) {
    /** Never the key (a request may end up in a log line by mistake). */
    override fun toString(): String = "FridgeVisionRequest(model=$model, custom=${baseUrl != null}, bytes=${jpeg.size}, lang=$languageTag)"

    companion object {
        /** Plan §8.8: the photo is scaled to 1600 px before it is sent. */
        const val MAX_EDGE_PX: Int = 1600

        /** A scaled JPEG is far below this; a bigger one was not scaled. */
        const val MAX_BYTES: Int = 5 * 1024 * 1024
    }
}
