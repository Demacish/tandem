package casa.moller.tandem.core.datastore.internal

import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.datastore.AiPreferences
import casa.moller.tandem.core.datastore.DatastoreFiles
import casa.moller.tandem.core.datastore.StoreScopes
import casa.moller.tandem.core.model.ai.AiProvider
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PreferencesAiPreferencesStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scopes = StoreScopes()
    private lateinit var file: File

    @Before
    fun setUp() {
        file = File(tmp.newFolder("datastore"), DatastoreFiles.AI_SETTINGS)
    }

    @After
    fun tearDown() = runBlocking { scopes.closeAll() }

    private fun open() = PreferencesAiPreferencesStore.create(produceFile = { file }, scope = scopes.newScope())

    @Test
    fun defaultsThenRoundTripPerProviderAndClear() = runTest {
        val store = open()
        assertEquals(AiPreferences(), store.preferences.first())

        val changed = AiPreferences(
            provider = AiProvider.GEMINI,
            models = mapOf(AiProvider.GEMINI to "gemini-custom", AiProvider.CLAUDE to "claude-custom"),
            baseUrls = mapOf(AiProvider.OPENAI to "https://proxy.example.com/v1"),
            warningAcknowledged = true,
        )
        store.update { changed }
        scopes.closeAll()
        val reopened = open()
        assertEquals(changed, reopened.preferences.first())

        reopened.update { it.copy(models = it.models - AiProvider.CLAUDE, provider = null) }
        assertEquals(mapOf(AiProvider.GEMINI to "gemini-custom"), reopened.preferences.first().models)
        assertEquals(null, reopened.preferences.first().provider)

        reopened.clear()
        assertEquals(AiPreferences(), reopened.preferences.first())
    }
}
