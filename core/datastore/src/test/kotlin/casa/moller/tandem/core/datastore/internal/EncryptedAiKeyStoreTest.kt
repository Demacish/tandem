package casa.moller.tandem.core.datastore.internal

import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.datastore.DatastoreFiles
import casa.moller.tandem.core.datastore.SecureStorageException
import casa.moller.tandem.core.datastore.StoreScopes
import casa.moller.tandem.core.datastore.TestAeadProvider
import casa.moller.tandem.core.datastore.crypto.AeadProvider
import casa.moller.tandem.core.model.ai.AiProvider
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** The user's AI keys (B22): encrypted on disk, only usable inside useKey, never resetting the shared keys. */
@RunWith(AndroidJUnit4::class)
class EncryptedAiKeyStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scopes = StoreScopes()
    private lateinit var file: File

    @Before
    fun setUp() {
        file = File(tmp.newFolder("datastore"), DatastoreFiles.SECURE_AI_KEYS)
    }

    @After
    fun tearDown() = runBlocking { scopes.closeAll() }

    private fun open(keys: AeadProvider) = EncryptedAiKeyStore(produceFile = { file }, keys = keys, scope = scopes.newScope())

    @Test
    fun aStoredKeyIsOnlyUsableInsideUseKeyAndSurvivesARestart() = runTest {
        val keys = TestAeadProvider()
        val store = open(keys)
        store.store(AiProvider.CLAUDE, KEY)

        assertEquals(setOf(AiProvider.CLAUDE), store.providersWithKey.first())
        assertEquals(KEY.length, store.useKey(AiProvider.CLAUDE) { it.length })
        assertNull(store.useKey(AiProvider.OPENAI) { it })

        scopes.closeAll()
        val reopened = open(keys)
        assertEquals(setOf(AiProvider.CLAUDE), reopened.providersWithKey.first())
        assertEquals(true, reopened.useKey(AiProvider.CLAUDE) { it == KEY })
    }

    @Test
    fun theFileNeverContainsTheKeyInPlainText() = runTest {
        open(TestAeadProvider()).store(AiProvider.GEMINI, KEY)
        val bytes = file.readBytes()
        assertTrue(bytes.isNotEmpty())
        val text = String(bytes, Charsets.ISO_8859_1)
        assertFalse(text.contains(KEY))
        assertFalse(text.contains("GEMINI"))
    }

    @Test
    fun removeAndClearAll() = runTest {
        val store = open(TestAeadProvider())
        store.store(AiProvider.CLAUDE, KEY)
        store.store(AiProvider.OPENAI, "sk-test-another-fake")
        store.remove(AiProvider.CLAUDE)
        assertEquals(setOf(AiProvider.OPENAI), store.providersWithKey.first())
        store.clearAll()
        assertEquals(emptySet<AiProvider>(), store.providersWithKey.first())
        assertNull(store.useKey(AiProvider.OPENAI) { it })
    }

    @Test
    fun anUnreadableDocumentReadsAsNoKeysAndNeverResetsTheSharedKeys() = runTest {
        val keys = TestAeadProvider()
        file.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29))
        val store = open(keys)

        assertEquals(emptySet<AiProvider>(), store.providersWithKey.first())
        assertEquals(0, keys.resetCount)
        store.store(AiProvider.CLAUDE, KEY)
        assertEquals(setOf(AiProvider.CLAUDE), store.providersWithKey.first())
        assertEquals(0, keys.resetCount)
    }

    @Test
    fun withoutAUsableKeyAWriteFailsAndNothingIsReset() = runTest {
        val keys = TestAeadProvider(alwaysFail = true)
        val store = open(keys)
        try {
            store.store(AiProvider.CLAUDE, KEY)
            fail("expected SecureStorageException")
        } catch (e: SecureStorageException) {
            assertFalse(e.message.orEmpty().contains(KEY))
        }
        assertEquals(0, keys.resetCount)
    }

    @Test
    fun theDocumentsToStringHidesTheKeys() {
        val text = AiKeyDocument(mapOf("CLAUDE" to KEY)).toString()
        assertFalse(text.contains(KEY))
        assertTrue(text.contains("CLAUDE"))
    }

    private companion object {
        const val KEY = "sk-test-not-a-real-key"
    }
}
