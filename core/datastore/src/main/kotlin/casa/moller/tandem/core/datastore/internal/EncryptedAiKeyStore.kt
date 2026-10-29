package casa.moller.tandem.core.datastore.internal

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import casa.moller.tandem.core.datastore.AiKeyStore
import casa.moller.tandem.core.datastore.DatastoreFiles
import casa.moller.tandem.core.datastore.SecureStorageException
import casa.moller.tandem.core.datastore.crypto.AeadProvider
import casa.moller.tandem.core.datastore.crypto.TransientKeyException
import casa.moller.tandem.core.model.ai.AiProvider
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

/**
 * The plaintext of `secure_ai_keys.pb`: provider name → key. Only ever on disk encrypted; [toString]
 * never shows a key.
 */
@Serializable
internal data class AiKeyDocument(val keys: Map<String, String> = emptyMap()) {
    override fun toString(): String = "AiKeyDocument(providers=${keys.keys})"
}

/**
 * [AiKeyStore] (B22): ONE document with the user's AI keys, encrypted as a whole with the same
 * Keystore-backed AEAD as the accounts ([AeadProvider]) and bound to its file by associated data.
 *
 * Unlike the account store this one NEVER calls [AeadProvider.reset]: the key set is shared with the
 * accounts, and an AI key is easy to paste again. A document that cannot be decrypted or parsed
 * reads as "no keys" (and is replaced on the next write); a key that is not reachable right now
 * fails the read (the flow resubscribes) and the write ([SecureStorageException]). Keys are never
 * logged and no exception message carries one.
 */
internal class EncryptedAiKeyStore(
    produceFile: () -> File,
    keys: AeadProvider,
    scope: CoroutineScope,
    associatedData: ByteArray = DatastoreFiles.SECURE_AI_KEYS_ASSOCIATED_DATA,
) : AiKeyStore {

    private val dataStore: DataStore<AiKeyDocument> = DataStoreFactory.create(
        serializer = AiKeyDocumentSerializer(keys, associatedData),
        corruptionHandler = ReplaceFileCorruptionHandler { AiKeyDocument() },
        scope = scope,
        produceFile = produceFile,
    )

    override val providersWithKey: Flow<Set<AiProvider>> = dataStore.data
        .map { document -> AiProvider.entries.filter { it.name in document.keys }.toSet() }
        .resubscribeOnFailure(fallback = emptySet())
        .distinctUntilChanged()

    override suspend fun store(provider: AiProvider, key: String) {
        require(key.isNotBlank()) { "key must not be blank" }
        edit { it.copy(keys = it.keys + (provider.name to key)) }
    }

    override suspend fun remove(provider: AiProvider) {
        edit { it.copy(keys = it.keys - provider.name) }
    }

    override suspend fun <T> useKey(provider: AiProvider, block: suspend (key: String) -> T): T? {
        val key = try {
            dataStore.data.first().keys[provider.name]
        } catch (e: CancellationException) {
            throw e
        } catch (ignored: IOException) {
            null
        } ?: return null
        return block(key)
    }

    override suspend fun clearAll() {
        edit { AiKeyDocument() }
    }

    private suspend fun edit(transform: (AiKeyDocument) -> AiKeyDocument) {
        try {
            dataStore.updateData { transform(it) }
        } catch (e: SecureStorageException) {
            throw e
        } catch (e: IOException) {
            throw SecureStorageException("AI key store could not be written", e)
        }
    }
}

/** JSON + AEAD for [AiKeyDocument]. Errors carry only their type, never the plaintext. */
internal class AiKeyDocumentSerializer(private val keys: AeadProvider, private val associatedData: ByteArray) : Serializer<AiKeyDocument> {
    override val defaultValue: AiKeyDocument = AiKeyDocument()

    override suspend fun readFrom(input: InputStream): AiKeyDocument {
        val ciphertext = input.readBytes()
        if (ciphertext.isEmpty()) return defaultValue
        val aead = try {
            keys.aead()
        } catch (e: TransientKeyException) {
            throw SecureStorageException("Secure storage is not available right now", e)
        } catch (e: GeneralSecurityException) {
            throw CorruptionException("AI key document: key unusable (${e.javaClass.name})")
        }
        val plaintext = try {
            aead.decrypt(ciphertext, associatedData)
        } catch (e: GeneralSecurityException) {
            throw CorruptionException("AI key document unreadable (${e.javaClass.name})")
        }
        return try {
            SecureDocumentJson.decodeFromString(AiKeyDocument.serializer(), plaintext.decodeToString())
        } catch (e: IllegalArgumentException) { // includes SerializationException; not chained (could quote a key)
            throw CorruptionException("AI key document unreadable (${e.javaClass.name})")
        } finally {
            plaintext.fill(0)
        }
    }

    override suspend fun writeTo(t: AiKeyDocument, output: OutputStream) {
        val aead = try {
            keys.aead()
        } catch (e: GeneralSecurityException) {
            throw SecureStorageException("Secure storage is not available", e)
        }
        val plaintext = SecureDocumentJson.encodeToString(AiKeyDocument.serializer(), t).encodeToByteArray()
        try {
            output.write(aead.encrypt(plaintext, associatedData))
        } catch (e: GeneralSecurityException) {
            throw SecureStorageException("AI key document could not be encrypted", e)
        } finally {
            plaintext.fill(0)
        }
    }
}
