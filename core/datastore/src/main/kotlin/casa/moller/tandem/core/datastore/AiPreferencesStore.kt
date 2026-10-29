package casa.moller.tandem.core.datastore

import casa.moller.tandem.core.model.ai.AiProvider
import kotlinx.coroutines.flow.Flow

/**
 * Fas S (ai-foto lane, B22): the NON-secret AI settings of this device (Settings → AI), in their own
 * Preferences DataStore ([DatastoreFiles.AI_SETTINGS], excluded from backup like every file here).
 * Per device, not per Tandoor account. The keys themselves are in [AiKeyStore], never here.
 *
 * @property provider the chosen service, null = none (the fridge photo is hidden).
 * @property models the user's model per provider (absent = the provider's default).
 * @property baseUrls the user's own endpoint per provider (absent = the official API).
 * @property warningAcknowledged the experimental warning was read and acknowledged.
 */
data class AiPreferences(
    val provider: AiProvider? = null,
    val models: Map<AiProvider, String> = emptyMap(),
    val baseUrls: Map<AiProvider, String> = emptyMap(),
    val warningAcknowledged: Boolean = false,
)

interface AiPreferencesStore {
    /** The current preferences; reading never throws (a broken file reads as the defaults). */
    val preferences: Flow<AiPreferences>

    /** @throws java.io.IOException when the change could not be written. */
    suspend fun update(transform: (AiPreferences) -> AiPreferences)

    /** Back to the defaults ("Rensa appdata"). @throws java.io.IOException when it could not be written. */
    suspend fun clear()
}
