package casa.moller.tandem.core.ai

import casa.moller.tandem.core.data.ai.AiSettingsRepository
import casa.moller.tandem.core.data.ai.FridgeVision
import casa.moller.tandem.core.datastore.AiKeyStore
import casa.moller.tandem.core.model.ai.FridgeVisionError
import casa.moller.tandem.core.model.ai.FridgeVisionResult
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/**
 * [FridgeVision] over the user's settings: the chosen provider, its key (read only for the call,
 * through [AiKeyStore.useKey]) and model. Demo mode and the 1600 px scaling are the ai-foto lane's
 * (the caller passes a scaled JPEG; a much larger one is refused here without a request).
 */
internal class DefaultFridgeVision @Inject constructor(
    private val settings: AiSettingsRepository,
    private val keys: AiKeyStore,
    private val clients: Set<@JvmSuppressWildcards FridgeVisionClient>,
) : FridgeVision {
    override suspend fun recognize(jpeg: ByteArray, languageTag: String): FridgeVisionResult {
        val current = settings.settings.first()
        val provider = current.provider ?: return FridgeVisionResult.Failed(FridgeVisionError.NoKey)
        val model = current.effectiveModel ?: return FridgeVisionResult.Failed(FridgeVisionError.NoKey)
        if (jpeg.isEmpty() || jpeg.size > FridgeVisionRequest.MAX_BYTES) {
            return FridgeVisionResult.Failed(FridgeVisionError.Unparseable("photo size ${jpeg.size}"))
        }
        val client = clients.firstOrNull { it.provider == provider }
            ?: return FridgeVisionResult.Failed(FridgeVisionError.Unparseable("no client for $provider"))
        return keys.useKey(provider) { key ->
            client.recognize(FridgeVisionRequest(key, model, current.customBaseUrl, jpeg, languageTag))
        } ?: FridgeVisionResult.Failed(FridgeVisionError.NoKey)
    }
}
