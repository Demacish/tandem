package casa.moller.tandem.core.datastore.internal

import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.datastore.DatastoreFiles
import casa.moller.tandem.core.datastore.LocalCalendarRecord
import casa.moller.tandem.core.datastore.StoreScopes
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

// Fas 5 (kalender lane, plan §7 #36): "Visa i telefonens kalender" is off until turned on, and the
// last write's outcome is remembered (and forgotten when it is turned off).
@RunWith(AndroidJUnit4::class)
class PreferencesMealCalendarStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scopes = StoreScopes()
    private lateinit var file: File

    @Before
    fun setUp() {
        file = File(tmp.newFolder("datastore"), DatastoreFiles.MEAL_CALENDAR)
    }

    @After
    fun tearDown() = runBlocking { scopes.closeAll() }

    private fun open(): PreferencesMealCalendarStore =
        PreferencesMealCalendarStore.create(produceFile = { file }, scope = scopes.newScope())

    @Test
    fun offByDefault() = runTest {
        assertEquals(LocalCalendarRecord(), open().state.first())
    }

    @Test
    fun onWithItsLastWriteSurvivesARestartAndOffForgetsIt() = runTest {
        open().apply {
            setEnabled(true)
            recordWrite(atMillis = 1_000, events = 5, problem = null)
            // A failed attempt keeps the last good write and adds the problem.
            recordWrite(atMillis = 2_000, events = 0, problem = "PERMISSION")
        }
        scopes.closeAll()

        val store = open()
        val kept = LocalCalendarRecord(enabled = true, lastWrittenMillis = 1_000, events = 5, problem = "PERMISSION")
        assertEquals(kept, store.state.first())
        store.recordWrite(atMillis = 3_000, events = 6, problem = null)
        assertEquals(LocalCalendarRecord(enabled = true, lastWrittenMillis = 3_000, events = 6), store.state.first())
        store.setEnabled(false)
        assertEquals(LocalCalendarRecord(), store.state.first())
    }
}
