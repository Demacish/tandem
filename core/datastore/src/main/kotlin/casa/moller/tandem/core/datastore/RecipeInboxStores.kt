package casa.moller.tandem.core.datastore

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

// Fas 5 (rss lane, plan §7 #35, §5.5): the recipe inbox's settings per account. The Miniflux address
// and the chosen categories are in a small Preferences DataStore ([DatastoreFiles.RECIPE_INBOX]); the
// API key is encrypted on its own ([DatastoreFiles.SECURE_INBOX_KEYS]) behind the narrow
// [RecipeInboxKeyStore]. Both files are outside the backup like everything else, and both forget an
// account that leaves the phone.

/**
 * The non-secret inbox settings, one JSON record per account (`account.<id>`). A failed read is the
 * empty record (not set up), a failed write is dropped.
 */
interface RecipeInboxStore {
    /** [accountId]'s record (an empty one when there is none). */
    fun observe(accountId: String): Flow<RecipeInboxRecord>

    /** Changes [accountId]'s record in one write; the empty record removes it. */
    suspend fun update(accountId: String, transform: (RecipeInboxRecord) -> RecipeInboxRecord)

    /** Forgets every account not in [accountIds] (accounts that left this phone). */
    suspend fun retainOnly(accountIds: Set<String>)

    /** The accounts with a record. */
    suspend fun accounts(): Set<String>
}

/**
 * One account's inbox settings. Never a key.
 *
 * @property serverUrl the Miniflux address (normalized), null when not set up.
 * @property categories the chosen categories (id and title, the title only for the screen).
 * @property problem the name of an `InboxProblem` of the last refresh, null after a success.
 * @property keyRefused fix-09: Miniflux refused the stored key (401/403); nothing is sent with it
 *   again, also in a new process, until a key is entered (`connect`).
 * @property holdUntilMillis fix-09: a 429 (`Retry-After`) or firewall pause of the inbox's guard,
 *   on the wall clock, so it also holds in a new process; null when there is none.
 */
@Serializable
data class RecipeInboxRecord(
    val serverUrl: String? = null,
    val categories: List<RecipeInboxCategoryRecord> = emptyList(),
    val markReadOnImport: Boolean = true,
    val showOnToday: Boolean = true,
    val lastRefreshMillis: Long? = null,
    val problem: String? = null,
    // --- fix-09 ---
    val keyRefused: Boolean = false,
    val holdUntilMillis: Long? = null,
)

/** A chosen Miniflux category. */
@Serializable
data class RecipeInboxCategoryRecord(val id: Long, val title: String)

/**
 * The inbox's Miniflux API keys, one per account, encrypted with the Keystore-backed AEAD of
 * [SecureAccountStore] and bound to their own file. The NARROW secure API, like [AiKeyStore]: a key
 * is only written by the inbox repository and only read through [useKey] for one request; it is
 * never returned as a value that could end up in a state, a log, diagnostics or a crash report.
 */
interface RecipeInboxKeyStore {
    /** The accounts that have a key (never the keys). */
    val accountsWithKey: Flow<Set<String>>

    /** Stores (replaces) [accountId]'s key. @throws SecureStorageException when it could not be saved. */
    suspend fun store(accountId: String, key: String)

    /** Removes [accountId]'s key; nothing happens when there is none. */
    suspend fun remove(accountId: String)

    /**
     * Calls [block] with [accountId]'s decrypted key and returns its result, or null when there is
     * none. The key must not escape [block].
     */
    suspend fun <T> useKey(accountId: String, block: suspend (key: String) -> T): T?

    /** Forgets every account not in [accountIds]. */
    suspend fun retainOnly(accountIds: Set<String>)
}
