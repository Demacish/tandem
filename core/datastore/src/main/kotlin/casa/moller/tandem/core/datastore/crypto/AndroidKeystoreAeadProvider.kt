package casa.moller.tandem.core.datastore.crypto

import android.annotation.SuppressLint
import android.content.Context
import casa.moller.tandem.core.datastore.DatastoreFiles
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.google.crypto.tink.integration.android.AndroidKeystoreKmsClient
import java.io.IOException
import java.security.GeneralSecurityException

/**
 * Tink AES-256-GCM keyset stored in its own SharedPreferences file
 * ([DatastoreFiles.KEYSET_PREFS]) and encrypted by an Android Keystore master key
 * ([DatastoreFiles.MASTER_KEY_ALIAS]).
 *
 * Tink silently falls back to a cleartext keyset when Android Keystore does not work. This
 * provider refuses that: the unprotected keyset is wiped and [aead] throws, so a token is never
 * stored with a key that sits next to it in plain text.
 *
 * Every failure, including the runtime exceptions some Keystore implementations throw, is
 * reported as [GeneralSecurityException] so callers never crash-loop. Failures are classified
 * ([classify]): only a key that is permanently gone or a keyset that can no longer be decrypted or
 * parsed is reported as a plain [GeneralSecurityException], which lets the store reset. Anything
 * else (a busy or not yet started keystore, an I/O error reading the keyset) becomes a
 * [TransientKeyException], so a short Keystore hiccup after a reboot never deletes the accounts.
 * Tink itself refuses to regenerate a master key that exists but cannot be used, for the same
 * reason.
 */
internal class AndroidKeystoreAeadProvider(private val context: Context) : AeadProvider {
    @Volatile
    private var cached: Aead? = null

    override fun aead(): Aead {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: createAead().also { cached = it }
        }
    }

    override fun reset() {
        synchronized(this) {
            cached = null
            wipeKeyset()
            try {
                AndroidKeystoreKmsClient().deleteKey(DatastoreFiles.MASTER_KEY_URI)
            } catch (ignored: Exception) {
                // Best effort: a missing or broken master key is replaced on the next aead() call.
            }
        }
    }

    private fun createAead(): Aead {
        try {
            AeadConfig.register()
            val manager = AndroidKeysetManager.Builder()
                .withSharedPref(context, DatastoreFiles.KEYSET_NAME, DatastoreFiles.KEYSET_PREFS)
                .withKeyTemplate(KeyTemplates.get(KEY_TEMPLATE))
                .withMasterKeyUri(DatastoreFiles.MASTER_KEY_URI)
                .build()
            if (!manager.isUsingKeystore) {
                wipeKeyset()
                throw GeneralSecurityException("Android Keystore is not available")
            }
            return manager.keysetHandle.getPrimitive(RegistryConfiguration.get(), Aead::class.java)
        } catch (e: TransientKeyException) {
            throw e
        } catch (e: GeneralSecurityException) {
            throw classify(e)
        } catch (e: IOException) {
            throw classify(e)
        } catch (e: RuntimeException) {
            throw classify(e)
        }
    }

    @SuppressLint("ApplySharedPref") // Must be on disk before a new keyset is generated.
    private fun wipeKeyset() {
        try {
            context.getSharedPreferences(DatastoreFiles.KEYSET_PREFS, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
        } catch (ignored: RuntimeException) {
            // Nothing else to do; the next build() fails again and is reported.
        }
    }

    internal companion object {
        private const val KEY_TEMPLATE = "AES256_GCM"
        private const val MAX_CAUSE_DEPTH = 10

        /** Simple names of causes that mean the stored keyset can never be read again. */
        private val PERMANENT_CAUSE_NAMES = setOf(
            // The Keystore key was invalidated (lock screen removed, biometrics changed, ...).
            "KeyPermanentlyInvalidatedException",
            // The Keystore entry exists but its key material cannot be recovered.
            "UnrecoverableKeyException",
            // The master key cannot decrypt the keyset (a different master key).
            "AEADBadTagException",
            // Deliberately NOT here: InvalidProtocolBufferException. Tink also produces it when
            // a Keystore call fails for a moment, because it then disables the Keystore and
            // reads the still encrypted keyset as cleartext. A keyset that is really corrupt
            // keeps failing; the recovery screen's "Clear app data" resets it then.
        )

        /**
         * A plain [GeneralSecurityException] for permanent failures, a [TransientKeyException]
         * for everything else. Matched on class names so that no Android-only or shaded class is
         * referenced directly.
         */
        fun classify(failure: Throwable): GeneralSecurityException {
            var current: Throwable? = failure
            var depth = 0
            while (current != null && depth < MAX_CAUSE_DEPTH) {
                if (current::class.java.simpleName in PERMANENT_CAUSE_NAMES) {
                    return GeneralSecurityException("Keyset or master key permanently unusable", failure)
                }
                current = current.cause
                depth++
            }
            return TransientKeyException("Android Keystore failed (may be temporary)", failure)
        }
    }
}
