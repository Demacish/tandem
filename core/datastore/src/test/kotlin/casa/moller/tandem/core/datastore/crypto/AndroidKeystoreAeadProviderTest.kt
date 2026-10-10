package casa.moller.tandem.core.datastore.crypto

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.datastore.DatastoreFiles
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStoreException
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Robolectric has no Android Keystore, so this checks the failure path: the provider must
 * report a [GeneralSecurityException] (never crash) and must not leave a cleartext keyset
 * behind. If an environment does provide a Keystore, the primitive must work instead.
 */
@RunWith(AndroidJUnit4::class)
class AndroidKeystoreAeadProviderTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun failsSafelyOrWorks() {
        val provider = AndroidKeystoreAeadProvider(context)
        val aead = try {
            provider.aead()
        } catch (e: GeneralSecurityException) {
            null
        }

        if (aead == null) {
            val prefs = context.getSharedPreferences(DatastoreFiles.KEYSET_PREFS, Context.MODE_PRIVATE)
            assertFalse("cleartext keyset left behind", prefs.contains(DatastoreFiles.KEYSET_NAME))
        } else {
            val plaintext = "hello".encodeToByteArray()
            val associatedData = "ad".encodeToByteArray()
            assertArrayEquals(plaintext, aead.decrypt(aead.encrypt(plaintext, associatedData), associatedData))
        }
    }

    @Test
    fun resetNeverThrows() {
        val provider = AndroidKeystoreAeadProvider(context)
        provider.reset()
        provider.reset()
    }

    @Test
    fun onlyPermanentKeyFailuresAllowAReset() {
        val permanent = listOf(
            android.security.keystore.KeyPermanentlyInvalidatedException("lock screen removed"),
            UnrecoverableKeyException("key material gone"),
            KeyStoreException("the master key exists but is unusable", UnrecoverableKeyException("gone")),
            GeneralSecurityException("decryption failed", AEADBadTagException("tag mismatch")),
        )
        permanent.forEach { failure ->
            val classified = AndroidKeystoreAeadProvider.classify(failure)
            assertFalse("$failure must be permanent", classified is TransientKeyException)
        }

        val transient = listOf(
            ProviderException("Keystore operation failed"),
            // Tink's wrapper around a busy keystore right after boot.
            KeyStoreException("the master key exists but is unusable", ProviderException("busy")),
            IOException("keyset prefs could not be read"),
            GeneralSecurityException("Android Keystore is not available"),
        )
        transient.forEach { failure ->
            assertTrue("$failure must be transient", AndroidKeystoreAeadProvider.classify(failure) is TransientKeyException)
        }
    }
}
