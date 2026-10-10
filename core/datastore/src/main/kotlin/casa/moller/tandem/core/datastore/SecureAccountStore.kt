package casa.moller.tandem.core.datastore

import casa.moller.tandem.core.model.account.ServerAccount
import java.io.IOException
import kotlinx.coroutines.flow.Flow

/**
 * Accounts and their device tokens, stored as one document encrypted with Tink AEAD
 * (AES-256-GCM) whose keyset is wrapped by an Android Keystore key (plan 5.1 / 5.5).
 *
 * Reading never throws: if the key or the file cannot be used, the store resets to empty and
 * reports the reason to [StorageHealthMonitor]. Writes throw [SecureStorageException] when the
 * data could not be saved, so a login flow knows the token was not stored.
 *
 * Tokens are never logged and never part of [ServerAccount].
 */
interface SecureAccountStore {
    /** All accounts in the order they were added. Emits an empty list after a reset. */
    val accounts: Flow<List<ServerAccount>>

    /**
     * Adds [account] with its device [token], or replaces the account (and token) with the same
     * [ServerAccount.id].
     *
     * @throws IllegalArgumentException if [token] is blank.
     * @throws SecureStorageException if the account could not be saved.
     */
    suspend fun upsert(account: ServerAccount, token: String)

    /**
     * Replaces the metadata of an existing account (server version, space name, ...) and keeps
     * its token.
     *
     * @return `false` if no account with that id exists.
     * @throws SecureStorageException if the change could not be saved.
     */
    suspend fun update(account: ServerAccount): Boolean

    /** The device token of [accountId], or `null` if the account is unknown or unreadable. */
    suspend fun token(accountId: String): String?

    /**
     * Removes the account and its token. Unknown ids are ignored.
     *
     * @throws SecureStorageException if the change could not be saved.
     */
    suspend fun remove(accountId: String)

    /**
     * Removes every account and token ("clear app data" on the recovery screen). If the file
     * cannot be rewritten, the encryption keys are destroyed instead, which makes the old data
     * permanently unreadable, and [SecureStorageException] is thrown.
     */
    suspend fun clearAll()
}

/** Secure storage could not be written or is not available. The message never contains secrets. */
class SecureStorageException(message: String, cause: Throwable? = null) : IOException(message, cause)
