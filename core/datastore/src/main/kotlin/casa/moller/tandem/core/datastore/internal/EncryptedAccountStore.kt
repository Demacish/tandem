package casa.moller.tandem.core.datastore.internal

import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import casa.moller.tandem.core.datastore.DatastoreFiles
import casa.moller.tandem.core.datastore.SecureAccountStore
import casa.moller.tandem.core.datastore.SecureStorageException
import casa.moller.tandem.core.datastore.StorageHealthMonitor
import casa.moller.tandem.core.datastore.crypto.AeadProvider
import casa.moller.tandem.core.model.account.ServerAccount
import casa.moller.tandem.core.model.settings.RecoveryReason
import java.io.File
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

/**
 * [SecureAccountStore] backed by a DataStore whose serializer encrypts the whole document.
 *
 * Failure handling (plan 4.2 step 7, never a crash loop):
 * - key permanently unusable while reading: report [RecoveryReason.KEY_UNUSABLE], destroy the
 *   keys, reset to an empty document encrypted with fresh keys;
 * - ciphertext or JSON unreadable: report [RecoveryReason.ACCOUNTS_UNREADABLE], reset to empty;
 * - key not reachable right now (a transient Keystore failure that outlasts the retries) or no
 *   key can be created at all: report [RecoveryReason.SECURE_STORAGE_UNAVAILABLE] and emit an
 *   empty list, but keep the file and the keys and read again later ([accounts] resubscribes
 *   with a backoff of up to [MAX_RETRY_DELAY_MILLIS]); writes throw [SecureStorageException].
 */
internal class EncryptedAccountStore(
    produceFile: () -> File,
    private val keys: AeadProvider,
    private val health: StorageHealthMonitor,
    scope: CoroutineScope,
    associatedData: ByteArray = DatastoreFiles.SECURE_ACCOUNTS_ASSOCIATED_DATA,
    keyRetryDelaysMillis: List<Long> = EncryptedDocumentSerializer.DEFAULT_KEY_RETRY_DELAYS_MILLIS,
) : SecureAccountStore {

    private val dataStore: DataStore<SecureDocument> = DataStoreFactory.create(
        serializer = EncryptedDocumentSerializer(keys, associatedData, keyRetryDelaysMillis),
        corruptionHandler = ReplaceFileCorruptionHandler<SecureDocument> { exception ->
            val reason = (exception.cause as? UnreadableDocumentException)?.reason
                ?: RecoveryReason.ACCOUNTS_UNREADABLE
            health.report(reason)
            if (reason == RecoveryReason.KEY_UNUSABLE) keys.reset()
            SecureDocument()
        },
        scope = scope,
        produceFile = produceFile,
    )

    // retryWhen, not catch: catch would complete the flow after the fallback, and the app-wide
    // session (shared eagerly for the life of the process) would never see an account again.
    override val accounts: Flow<List<ServerAccount>> = dataStore.data
        .map { document -> document.accounts.map(StoredAccount::toModel) }
        // Readable again: a "not available right now" from an earlier attempt no longer applies.
        .onEach { health.resolve(RecoveryReason.SECURE_STORAGE_UNAVAILABLE) }
        .resubscribeOnFailure(fallback = emptyList()) {
            health.report(RecoveryReason.SECURE_STORAGE_UNAVAILABLE)
        }
        .distinctUntilChanged()

    override suspend fun upsert(account: ServerAccount, token: String) {
        require(token.isNotBlank()) { "token must not be blank" }
        val stored = StoredAccount.from(account, token)
        edit { document ->
            val index = document.accounts.indexOfFirst { it.id == account.id }
            val accounts = if (index < 0) {
                document.accounts + stored
            } else {
                document.accounts.toMutableList().also { it[index] = stored }
            }
            document.copy(accounts = accounts)
        }
    }

    override suspend fun update(account: ServerAccount): Boolean {
        var found = false
        edit { document ->
            val index = document.accounts.indexOfFirst { it.id == account.id }
            found = index >= 0
            if (index < 0) {
                document
            } else {
                val stored = StoredAccount.from(account, document.accounts[index].token)
                document.copy(accounts = document.accounts.toMutableList().also { it[index] = stored })
            }
        }
        return found
    }

    override suspend fun token(accountId: String): String? = try {
        dataStore.data.first().accounts.firstOrNull { it.id == accountId }?.token
    } catch (e: CancellationException) {
        throw e
    } catch (ignored: Exception) {
        health.report(RecoveryReason.SECURE_STORAGE_UNAVAILABLE)
        null
    }

    override suspend fun remove(accountId: String) {
        edit { document -> document.copy(accounts = document.accounts.filterNot { it.id == accountId }) }
    }

    override suspend fun clearAll() {
        try {
            edit { SecureDocument() }
        } catch (e: SecureStorageException) {
            // Crypto-shredding: without the keys the old ciphertext can never be read again.
            keys.reset()
            throw e
        }
    }

    private suspend fun edit(transform: (SecureDocument) -> SecureDocument) {
        try {
            dataStore.updateData { transform(it) }
        } catch (e: SecureStorageException) {
            throw e
        } catch (e: IOException) {
            throw SecureStorageException("Secure account store could not be written", e)
        }
    }
}
