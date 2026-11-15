package casa.moller.tandem.core.datastore.internal

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import casa.moller.tandem.core.datastore.DatastoreFiles
import casa.moller.tandem.core.datastore.RecipeInboxKeyStore
import casa.moller.tandem.core.datastore.SecureStorageException
import casa.moller.tandem.core.datastore.crypto.AeadProvider
import casa.moller.tandem.core.datastore.crypto.TransientKeyException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

/** The plaintext of `secure_inbox_keys.pb`: account id → Miniflux API key. [toString] never shows a key. */
@Serializable
internal data class InboxKeyDocument(val keys: Map<String, String> = emptyMap()) {
    override fun toString(): String = "InboxKeyDocument(accounts=${keys.size})"
}

/**
 * [RecipeInboxKeyStore] (Fas 5, rss lane): ONE document with the inbox keys, encrypted as a whole with
 * the accounts' Keystore-backed AEAD ([AeadProvider]) and bound to its file by associated data. Like
 * the AI keys it NEVER resets the shared key set: a document that cannot be read is "no keys" (the
 * user enters the key again); a key that is not reachable right now fails the write.
 */
internal class EncryptedRecipeInboxKeyStore(
    produceFile: () -> File,
    keys: AeadProvider,
    scope: CoroutineScope,
    associatedData: ByteArray = DatastoreFiles.SECURE_INBOX_KEYS_ASSOCIATED_DATA,
) : RecipeInboxKeyStore {

    private val dataStore: DataStore<InboxKeyDocument> = DataStoreFactory.create(
        serializer = InboxKeyDocumentSerializer(keys, associatedData),
        corruptionHandler = ReplaceFileCorruptionHandler { InboxKeyDocument() },
        scope = scope,
        produceFile = produceFile,
    )

    override val accountsWithKey: Flow<Set<String>> = dataStore.data
        .map { document -> document.keys.keys.toSet() }
        .resubscribeOnFailure(fallback = emptySet())
        .distinctUntilChanged()

    override suspend fun store(accountId: String, key: String) {
        require(key.isNotBlank()) { "key must not be blank" }
        edit { it.copy(keys = it.keys + (accountId to key)) }
    }

    override suspend fun remove(accountId: String) {
        edit { it.copy(keys = it.keys - accountId) }
    }

    override suspend fun <T> useKey(accountId: String, block: suspend (key: String) -> T): T? {
        val key = try {
            dataStore.data.first().keys[accountId]
        } catch (e: CancellationException) {
            throw e
        } catch (ignored: IOException) {
            null
        } ?: return null
        return block(key)
    }

    override suspend fun retainOnly(accountIds: Set<String>) {
        edit { document -> document.copy(keys = document.keys.filterKeys { it in accountIds }) }
    }

    private suspend fun edit(transform: (InboxKeyDocument) -> InboxKeyDocument) {
        try {
            dataStore.updateData { transform(it) }
        } catch (e: SecureStorageException) {
            throw e
        } catch (e: IOException) {
            throw SecureStorageException("Inbox key store could not be written", e)
        }
    }
}

/** JSON + AEAD for [InboxKeyDocument]. Errors carry only their type, never the plaintext. */
internal class InboxKeyDocumentSerializer(private val keys: AeadProvider, private val associatedData: ByteArray) :
    Serializer<InboxKeyDocument> {
    override val defaultValue: InboxKeyDocument = InboxKeyDocument()

    override suspend fun readFrom(input: InputStream): InboxKeyDocument {
        val ciphertext = input.readBytes()
        if (ciphertext.isEmpty()) return defaultValue
        val aead = try {
            keys.aead()
        } catch (e: TransientKeyException) {
            throw SecureStorageException("Secure storage is not available right now", e)
        } catch (e: GeneralSecurityException) {
            throw CorruptionException("Inbox key document: key unusable (${e.javaClass.name})")
        }
        val plaintext = try {
            aead.decrypt(ciphertext, associatedData)
        } catch (e: GeneralSecurityException) {
            throw CorruptionException("Inbox key document unreadable (${e.javaClass.name})")
        }
        return try {
            SecureDocumentJson.decodeFromString(InboxKeyDocument.serializer(), plaintext.decodeToString())
        } catch (e: IllegalArgumentException) { // includes SerializationException; not chained (could quote a key)
            throw CorruptionException("Inbox key document unreadable (${e.javaClass.name})")
        } finally {
            plaintext.fill(0)
        }
    }

    override suspend fun writeTo(t: InboxKeyDocument, output: OutputStream) {
        val aead = try {
            keys.aead()
        } catch (e: GeneralSecurityException) {
            throw SecureStorageException("Secure storage is not available", e)
        }
        val plaintext = SecureDocumentJson.encodeToString(InboxKeyDocument.serializer(), t).encodeToByteArray()
        try {
            output.write(aead.encrypt(plaintext, associatedData))
        } catch (e: GeneralSecurityException) {
            throw SecureStorageException("Inbox key document could not be encrypted", e)
        } finally {
            plaintext.fill(0)
        }
    }
}
