package casa.moller.tandem.wear.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import casa.moller.tandem.core.watchlink.WatchCodec
import casa.moller.tandem.core.watchlink.WatchCredentials
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * The watch token (plan §3.1, §5.5): only ever stored encrypted, never in [WearState], never
 * backed up, never logged.
 */
interface CredentialsStore {
    /** The stored credentials, or null (not paired, or the key could not be used). */
    suspend fun load(): WatchCredentials?

    /** Stores [credentials]; false when they could not be encrypted or written. */
    suspend fun save(credentials: WatchCredentials): Boolean

    /** Forgets them (unpairing, or a key that no longer works). */
    suspend fun clear()
}

/**
 * [CredentialsStore] in one file, encrypted with AES-256-GCM under an Android Keystore key that
 * never leaves the watch (the same idea as the phone's encrypted account store, without Tink: one
 * value needs no keyset). A key that was lost (watch reset, Keystore cleared) makes the file
 * unreadable: it is deleted and the watch asks to be paired again.
 */
class KeystoreCredentialsStore(private val file: File, private val io: CoroutineDispatcher) : CredentialsStore {
    @Volatile
    private var cached: WatchCredentials? = null

    override suspend fun load(): WatchCredentials? = cached ?: withContext(io) {
        val bytes = readFile() ?: return@withContext null
        val plain = try {
            decrypt(bytes)
        } catch (e: GeneralSecurityException) {
            file.delete()
            null
        } catch (e: IllegalArgumentException) {
            file.delete()
            null
        }
        WatchCodec.decode(WatchCredentials.serializer(), plain).also { cached = it }
    }

    override suspend fun save(credentials: WatchCredentials): Boolean = withContext(io) {
        try {
            val encrypted = encrypt(WatchCodec.encode(WatchCredentials.serializer(), credentials))
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, file.name + ".tmp")
            temporary.writeBytes(encrypted)
            if (!temporary.renameTo(file)) {
                temporary.copyTo(file, overwrite = true)
                temporary.delete()
            }
            cached = credentials
            true
        } catch (e: GeneralSecurityException) {
            false
        } catch (e: IOException) {
            false
        }
    }

    override suspend fun clear() {
        cached = null
        withContext(io) { file.delete() }
    }

    private fun readFile(): ByteArray? = try {
        if (file.isFile) file.readBytes() else null
    } catch (e: IOException) {
        null
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        return byteArrayOf(iv.size.toByte()) + iv + cipher.doFinal(plain)
    }

    private fun decrypt(stored: ByteArray): ByteArray {
        require(stored.isNotEmpty()) { "empty" }
        val ivLength = stored[0].toInt()
        require(ivLength in 1 until stored.size) { "bad iv" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, stored, 1, ivLength))
        return cipher.doFinal(stored, 1 + ivLength, stored.size - 1 - ivLength)
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        /** The file in the app's no-backup folder. */
        const val FILE_NAME: String = "tandem_wear_token.bin"

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "tandem_wear_token"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private const val KEY_BITS = 256
    }
}
