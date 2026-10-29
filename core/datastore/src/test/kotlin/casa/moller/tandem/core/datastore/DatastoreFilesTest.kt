package casa.moller.tandem.core.datastore

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Guards the file names documented in README.md, which the backup rules rely on. */
@RunWith(AndroidJUnit4::class)
class DatastoreFilesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun secureAccountsLiveInFilesDatastore() {
        assertEquals(
            File(context.filesDir, "datastore/secure_accounts.pb"),
            DatastoreFiles.secureAccountsFile(context),
        )
    }

    @Test
    fun settingsLiveInFilesDatastore() {
        assertEquals(
            File(context.filesDir, "datastore/app_settings.preferences_pb"),
            DatastoreFiles.settingsFile(context),
        )
        assertEquals("app_settings.preferences_pb", DatastoreFiles.SETTINGS)
    }

    @Test
    fun diagnosticsLiveInFilesDatastore() {
        assertEquals(
            File(context.filesDir, "datastore/diagnostics.preferences_pb"),
            DatastoreFiles.diagnosticsFile(context),
        )
        assertEquals("diagnostics.preferences_pb", DatastoreFiles.DIAGNOSTICS)
    }

    @Test
    fun aiKeysAndSettingsLiveInFilesDatastore() {
        assertEquals(File(context.filesDir, "datastore/secure_ai_keys.pb"), DatastoreFiles.secureAiKeysFile(context))
        assertEquals(File(context.filesDir, "datastore/ai_settings.preferences_pb"), DatastoreFiles.aiSettingsFile(context))
        assertEquals("ai_settings.preferences_pb", DatastoreFiles.AI_SETTINGS)
    }
}
