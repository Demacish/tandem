package casa.moller.tandem.core.datastore.crypto

import com.google.crypto.tink.Aead
import java.security.GeneralSecurityException

/**
 * Source of the AEAD primitive that encrypts the account store. Production uses
 * [AndroidKeystoreAeadProvider]; tests use an in-memory Tink keyset.
 */
interface AeadProvider {
    /**
     * Returns the primitive, creating keys on first use.
     *
     * @throws TransientKeyException if the key store failed in a way that may go away on its own
     *   (Android Keystore busy or not ready yet, e.g. right after a reboot). Callers retry and
     *   never destroy keys or data because of it.
     * @throws GeneralSecurityException if the key is permanently unusable (invalidated, or a
     *   keyset that can no longer be decrypted or parsed).
     */
    @Throws(GeneralSecurityException::class)
    fun aead(): Aead

    /**
     * Destroys all key material, so the next [aead] call creates new keys. Everything encrypted
     * with the old keys becomes unreadable. Never throws.
     */
    fun reset()
}

/**
 * A key store failure that may go away on retry. Unlike a plain [GeneralSecurityException] it
 * must never lead to [AeadProvider.reset]: the key is probably fine, only not reachable right now.
 */
class TransientKeyException(message: String, cause: Throwable? = null) : GeneralSecurityException(message, cause)
