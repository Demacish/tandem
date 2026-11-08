package casa.moller.tandem.core.datastore.internal

import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.datastore.DatastoreFiles
import casa.moller.tandem.core.datastore.StoreScopes
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

// Fas 5 (lane handla, plan §8.2): the grid on the shopping list is remembered per account.
@RunWith(AndroidJUnit4::class)
class PreferencesShoppingViewStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scopes = StoreScopes()
    private lateinit var file: File

    @Before
    fun setUp() {
        file = File(tmp.newFolder("datastore"), DatastoreFiles.SHOPPING_VIEW)
    }

    @After
    fun tearDown() = runBlocking { scopes.closeAll() }

    private fun open(): PreferencesShoppingViewStore =
        PreferencesShoppingViewStore.create(produceFile = { file }, scope = scopes.newScope())

    @Test
    fun rowsUntilTheGridIsTurnedOn() = runTest {
        assertFalse(open().observeGrid("a").first())
    }

    @Test
    fun theGridIsPerAccountAndSurvivesARestart() = runTest {
        open().apply {
            setGrid("a", true)
            setGrid("b", false)
        }
        scopes.closeAll()

        val store = open()
        assertTrue(store.observeGrid("a").first())
        assertFalse(store.observeGrid("b").first())
        store.setGrid("a", false)
        assertFalse(store.observeGrid("a").first())
    }

    @Test
    fun aForgottenAccountShowsRowsAgain() = runTest {
        val store = open()
        store.setGrid("a", true)
        store.setGrid("b", true)
        store.retainOnly(setOf("b", "c"))
        assertEquals(listOf(false, true), listOf(store.observeGrid("a").first(), store.observeGrid("b").first()))
    }

    @Test
    fun aCorruptFileReadsAsRows() = runTest {
        file.writeBytes(byteArrayOf(0x7f, 0x01, 0x02, 0x03))
        assertFalse(open().observeGrid("a").first())
    }
}
