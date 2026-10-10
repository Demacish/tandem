package casa.moller.tandem.core.datastore

import casa.moller.tandem.core.datastore.crypto.AeadProvider
import casa.moller.tandem.core.datastore.crypto.TransientKeyException
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.PredefinedAeadParameters
import java.security.GeneralSecurityException

/**
 * In-memory Tink AES-256-GCM keyset, standing in for the Android Keystore backed provider
 * (Robolectric has no Android Keystore).
 *
 * @param failUntilReset [aead] throws until [reset] is called (lost Keystore key).
 * @param alwaysFail [aead] always throws (Keystore not available at all).
 */
internal class TestAeadProvider(private var failUntilReset: Boolean = false, private val alwaysFail: Boolean = false) : AeadProvider {
    var resetCount = 0
        private set

    /** The next this many [aead] calls throw a [TransientKeyException] (Keystore busy). */
    @Volatile
    var transientFailures = 0

    @Volatile
    var aeadCalls = 0
        private set

    private var handle: KeysetHandle = newKeyset()

    override fun aead(): Aead {
        aeadCalls++
        if (transientFailures > 0) {
            transientFailures--
            throw TransientKeyException("test: keystore busy")
        }
        if (alwaysFail || failUntilReset) throw GeneralSecurityException("test: key unusable")
        return handle.getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    override fun reset() {
        resetCount++
        failUntilReset = false
        handle = newKeyset()
    }

    private companion object {
        fun newKeyset(): KeysetHandle {
            AeadConfig.register()
            return KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM)
        }
    }
}
