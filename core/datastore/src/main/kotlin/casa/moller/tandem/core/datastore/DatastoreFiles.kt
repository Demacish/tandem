package casa.moller.tandem.core.datastore

import android.content.Context
import androidx.datastore.dataStoreFile
import androidx.datastore.preferences.preferencesDataStoreFile
import java.io.File

/**
 * Every file and key alias this module creates on the device. Backup and device-transfer rules
 * (`app/src/main/res/xml/data_extraction_rules.xml`) must exclude all of them; see README.md.
 */
object DatastoreFiles {
    /** Encrypted accounts + tokens document: `files/datastore/secure_accounts.pb`. */
    const val SECURE_ACCOUNTS = "secure_accounts.pb"

    /** Name passed to `preferencesDataStoreFile`, giving `files/datastore/app_settings.preferences_pb`. */
    const val SETTINGS_NAME = "app_settings"

    /** File name of the settings store. */
    const val SETTINGS = "$SETTINGS_NAME.preferences_pb"

    /** Name passed to `preferencesDataStoreFile` for the measured timings (`DiagnosticsStore`). */
    const val DIAGNOSTICS_NAME = "diagnostics"

    /** File name of the diagnostics store: `files/datastore/diagnostics.preferences_pb`. */
    const val DIAGNOSTICS = "$DIAGNOSTICS_NAME.preferences_pb"

    /** SharedPreferences file holding the Tink keyset: `shared_prefs/tandem_secure_keyset.xml`. */
    const val KEYSET_PREFS = "tandem_secure_keyset"

    /** Key inside [KEYSET_PREFS] under which Tink stores the (Keystore-encrypted) keyset. */
    const val KEYSET_NAME = "secure_accounts_keyset"

    /** Alias of the AES-256-GCM master key in Android Keystore that wraps the Tink keyset. */
    const val MASTER_KEY_ALIAS = "tandem_secure_master_key"

    internal const val MASTER_KEY_URI = "android-keystore://$MASTER_KEY_ALIAS"

    /**
     * Associated data for the account document. It binds the ciphertext to this file, so a
     * ciphertext copied into another file (or another app) does not decrypt.
     */
    internal val SECURE_ACCOUNTS_ASSOCIATED_DATA: ByteArray
        get() = "casa.moller.tandem/datastore/$SECURE_ACCOUNTS".encodeToByteArray()

    internal fun secureAccountsFile(context: Context): File = context.dataStoreFile(SECURE_ACCOUNTS)

    internal fun settingsFile(context: Context): File = context.preferencesDataStoreFile(SETTINGS_NAME)

    internal fun diagnosticsFile(context: Context): File = context.preferencesDataStoreFile(DIAGNOSTICS_NAME)
}
