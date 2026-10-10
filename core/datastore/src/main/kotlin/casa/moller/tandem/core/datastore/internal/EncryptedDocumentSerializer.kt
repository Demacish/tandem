package casa.moller.tandem.core.datastore.internal

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import casa.moller.tandem.core.datastore.SecureStorageException
import casa.moller.tandem.core.datastore.crypto.AeadProvider
import casa.moller.tandem.core.datastore.crypto.TransientKeyException
import casa.moller.tandem.core.model.settings.RecoveryReason
import com.google.crypto.tink.Aead
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import kotlinx.coroutines.delay

/**
 * Cause of the [CorruptionException] thrown by [EncryptedDocumentSerializer.readFrom]. It carries
 * only the [reason] and the type of the original error: messages of JSON errors can quote the
 * plaintext, so the original exception is deliberately not chained.
 */
internal class UnreadableDocumentException(val reason: RecoveryReason, causeType: String?) :
    IOException("Secure account document unreadable: $reason ($causeType)")

/**
 * Serializes [SecureDocument] as JSON and encrypts the whole document with the AEAD from
 * [keys], using [associatedData] to bind the ciphertext to its file.
 *
 * Key failures:
 * - transient ([TransientKeyException]): retried after each of [keyRetryDelaysMillis]; if the
 *   key is still not reachable, reading throws [SecureStorageException] (a plain IOException, so
 *   DataStore's corruption handler does NOT run and nothing is reset) and writing throws it too;
 * - permanent (any other [GeneralSecurityException]): reading throws a [CorruptionException] with
 *   [RecoveryReason.KEY_UNUSABLE], which resets the store; writing resets the keys once and
 *   retries (see [aeadForWrite]).
 *
 * Writes never report to the health monitor: their caller gets the exception and shows its own
 * message (plan 4.2 step 7 is about stored data that cannot be read).
 */
internal class EncryptedDocumentSerializer(
    private val keys: AeadProvider,
    private val associatedData: ByteArray,
    private val keyRetryDelaysMillis: List<Long> = DEFAULT_KEY_RETRY_DELAYS_MILLIS,
) : Serializer<SecureDocument> {
    override val defaultValue: SecureDocument = SecureDocument()

    override suspend fun readFrom(input: InputStream): SecureDocument {
        val ciphertext = input.readBytes()
        if (ciphertext.isEmpty()) return defaultValue

        val aead = try {
            aeadWithRetries()
        } catch (e: TransientKeyException) {
            throw SecureStorageException("Secure storage is not available right now", e)
        } catch (e: GeneralSecurityException) {
            throw unreadable(RecoveryReason.KEY_UNUSABLE, e)
        }
        val plaintext = try {
            aead.decrypt(ciphertext, associatedData)
        } catch (e: GeneralSecurityException) {
            throw unreadable(RecoveryReason.ACCOUNTS_UNREADABLE, e)
        }
        try {
            val document = SecureDocumentJson.decodeFromString(SecureDocument.serializer(), plaintext.decodeToString())
            // Fail here, inside the corruption handling, rather than later in a collector.
            document.accounts.forEach { it.toModel() }
            return document
        } catch (e: IllegalArgumentException) { // includes SerializationException
            throw unreadable(RecoveryReason.ACCOUNTS_UNREADABLE, e)
        } finally {
            plaintext.fill(0)
        }
    }

    override suspend fun writeTo(t: SecureDocument, output: OutputStream) {
        val aead = aeadForWrite()
        val plaintext = SecureDocumentJson.encodeToString(SecureDocument.serializer(), t).encodeToByteArray()
        try {
            output.write(aead.encrypt(plaintext, associatedData))
        } catch (e: GeneralSecurityException) {
            throw SecureStorageException("Secure account document could not be encrypted", e)
        } finally {
            plaintext.fill(0)
        }
    }

    /**
     * A write only happens after a successful read (or with no file at all), so resetting keys
     * after a PERMANENT failure here cannot destroy data that is still readable. A transient
     * failure never resets anything: the write fails and the caller may try again later.
     */
    private suspend fun aeadForWrite(): Aead = try {
        aeadWithRetries()
    } catch (e: TransientKeyException) {
        throw SecureStorageException("Secure storage is not available right now", e)
    } catch (ignored: GeneralSecurityException) {
        keys.reset()
        try {
            aeadWithRetries()
        } catch (e: GeneralSecurityException) {
            throw SecureStorageException("Secure storage is not available", e)
        }
    }

    /** [AeadProvider.aead], retried after a [TransientKeyException] with a short backoff. */
    private suspend fun aeadWithRetries(): Aead {
        var attempt = 0
        while (true) {
            try {
                return keys.aead()
            } catch (e: TransientKeyException) {
                val wait = keyRetryDelaysMillis.getOrNull(attempt++) ?: throw e
                delay(wait)
            }
        }
    }

    private fun unreadable(reason: RecoveryReason, cause: Exception): CorruptionException = CorruptionException(
        "Secure account document unreadable",
        // Fully qualified, so that an obfuscated name can be retraced with the release mapping.
        UnreadableDocumentException(reason, cause.javaClass.name),
    )

    companion object {
        /** Waits before the 2nd and 3rd attempt after a transient Keystore failure. */
        val DEFAULT_KEY_RETRY_DELAYS_MILLIS: List<Long> = listOf(100L, 300L)
    }
}
