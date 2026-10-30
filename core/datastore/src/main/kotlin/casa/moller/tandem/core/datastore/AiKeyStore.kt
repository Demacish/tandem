package casa.moller.tandem.core.datastore

import casa.moller.tandem.core.model.ai.AiProvider
import kotlinx.coroutines.flow.Flow

/**
 * Fas S (decision B22): the user's own AI keys for the fridge photo, encrypted on the device
 * ([DatastoreFiles.SECURE_AI_KEYS], the Keystore-backed AEAD of [SecureAccountStore]).
 *
 * The NARROW secure API: features never see this module (checkModuleGraph), so a key is only
 * written through `AiSettingsRepository` (core:data) and only read by core:ai through [useKey], for
 * the one request to the chosen provider. A key is never returned as a value that could end up in
 * a state, a log, diagnostics, a crash report or an export. Implemented by `EncryptedAiKeyStore`
 * (ai-foto lane), bound in [casa.moller.tandem.core.datastore.di.DatastoreModule].
 */
interface AiKeyStore {
    /** The providers that have a key stored (never the keys). */
    val providersWithKey: Flow<Set<AiProvider>>

    /** Stores (replaces) the key of [provider]. @throws SecureStorageException when it could not be saved. */
    suspend fun store(provider: AiProvider, key: String)

    /** Removes the key of [provider]; nothing happens when there is none. */
    suspend fun remove(provider: AiProvider)

    /**
     * Calls [block] with the decrypted key of [provider] and returns its result, or null when no
     * key is stored. The key must not escape [block] (no logging, no storing, no exceptions with it).
     */
    suspend fun <T> useKey(provider: AiProvider, block: suspend (key: String) -> T): T?

    /** Removes every key (logout of the last account, "Rensa allt"). */
    suspend fun clearAll()
}
