package casa.moller.tandem.core.datastore.internal

import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.datastore.DatastoreFiles
import casa.moller.tandem.core.datastore.RecipeInboxCategoryRecord
import casa.moller.tandem.core.datastore.RecipeInboxRecord
import casa.moller.tandem.core.datastore.SecureStorageException
import casa.moller.tandem.core.datastore.StoreScopes
import casa.moller.tandem.core.datastore.TestAeadProvider
import casa.moller.tandem.core.datastore.crypto.AeadProvider
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

/**
 * Fas 5 (rss lane, plan §7 #35, §5.5): the inbox's settings per account, and its Miniflux API keys
 * encrypted on disk (only usable inside useKey, never in the file in plain text, never resetting the
 * keys the accounts share), both forgetting the accounts that left.
 */
@RunWith(AndroidJUnit4::class)
class RecipeInboxStoresTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scopes = StoreScopes()
    private lateinit var folder: File

    @Before
    fun setUp() {
        folder = tmp.newFolder("datastore")
    }

    @After
    fun tearDown() = runBlocking { scopes.closeAll() }

    private fun settings() =
        PreferencesRecipeInboxStore.create(produceFile = { File(folder, DatastoreFiles.RECIPE_INBOX) }, scope = scopes.newScope())

    private fun keys(aead: AeadProvider) = EncryptedRecipeInboxKeyStore(
        produceFile = { File(folder, DatastoreFiles.SECURE_INBOX_KEYS) },
        keys = aead,
        scope = scopes.newScope(),
    )

    @Test
    fun theSettingsArePerAccountAndSurviveARestart() = runTest {
        val store = settings()
        assertEquals(RecipeInboxRecord(), store.observe("a").first())
        store.update("a") {
            it.copy(serverUrl = "https://rss.example.com/", categories = listOf(RecipeInboxCategoryRecord(3, "Recept")))
        }
        store.update("b") { it.copy(markReadOnImport = false) }

        scopes.closeAll()
        val reopened = settings()
        assertEquals("https://rss.example.com/", reopened.observe("a").first().serverUrl)
        assertEquals(listOf(RecipeInboxCategoryRecord(3, "Recept")), reopened.observe("a").first().categories)
        assertFalse(reopened.observe("b").first().markReadOnImport)
        assertEquals(setOf("a", "b"), reopened.accounts())

        reopened.retainOnly(setOf("b"))
        assertEquals(RecipeInboxRecord(), reopened.observe("a").first())
        // The empty record removes the account.
        reopened.update("b") { RecipeInboxRecord() }
        assertEquals(emptySet<String>(), reopened.accounts())
    }

    @Test
    fun aKeyIsOnlyUsableInsideUseKeyAndNeverInTheFile() = runTest {
        val aead = TestAeadProvider()
        val store = keys(aead)
        store.store("a", KEY)
        assertEquals(setOf("a"), store.accountsWithKey.first())
        assertEquals(true, store.useKey("a") { it == KEY })
        assertNull(store.useKey("b") { it })
        val text = String(File(folder, DatastoreFiles.SECURE_INBOX_KEYS).readBytes(), Charsets.ISO_8859_1)
        assertFalse(text.contains(KEY))

        scopes.closeAll()
        val reopened = keys(aead)
        assertEquals(true, reopened.useKey("a") { it == KEY })
        reopened.store("b", "another-fake-key")
        reopened.retainOnly(setOf("b"))
        assertEquals(setOf("b"), reopened.accountsWithKey.first())
        reopened.remove("b")
        assertEquals(emptySet<String>(), reopened.accountsWithKey.first())
    }

    @Test
    fun anUnreadableDocumentIsNoKeysAndNothingIsReset() = runTest {
        val aead = TestAeadProvider()
        File(folder, DatastoreFiles.SECURE_INBOX_KEYS).writeBytes(ByteArray(GARBAGE_BYTES) { (it + 3).toByte() })
        val store = keys(aead)
        assertEquals(emptySet<String>(), store.accountsWithKey.first())
        store.store("a", KEY)
        assertEquals(setOf("a"), store.accountsWithKey.first())
        assertEquals(0, aead.resetCount)
    }

    @Test
    fun withoutAUsableKeyAWriteFails() = runTest {
        val aead = TestAeadProvider(alwaysFail = true)
        try {
            keys(aead).store("a", KEY)
            fail("expected SecureStorageException")
        } catch (e: SecureStorageException) {
            assertFalse(e.message.orEmpty().contains(KEY))
        }
        assertEquals(0, aead.resetCount)
        assertTrue(InboxKeyDocument(mapOf("a" to KEY)).toString().let { KEY !in it })
    }

    private companion object {
        const val KEY = "miniflux-test-key-not-real-0000"
        const val GARBAGE_BYTES = 31
    }
}
