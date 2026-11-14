package casa.moller.tandem.core.datastore.internal

import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.datastore.DatastoreFiles
import casa.moller.tandem.core.datastore.StoreFenceRegistration
import casa.moller.tandem.core.datastore.StoreReminderRecord
import casa.moller.tandem.core.datastore.StoreScopes
import casa.moller.tandem.core.datastore.StoreSpotRecord
import casa.moller.tandem.core.datastore.StoreVisitRecord
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

// Fas 5 (butik lane, plan §7 #34): the places and visits are kept per account and go with the account.
@RunWith(AndroidJUnit4::class)
class PreferencesStoreReminderStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scopes = StoreScopes()
    private lateinit var file: File

    private val spot = StoreSpotRecord(storeId = 1, latitude = 12.3456, longitude = -45.6789, radiusMeters = 200)

    @Before
    fun setUp() {
        file = File(tmp.newFolder("datastore"), DatastoreFiles.STORE_REMINDERS)
    }

    @After
    fun tearDown() = runBlocking { scopes.closeAll() }

    private fun open(): PreferencesStoreReminderStore =
        PreferencesStoreReminderStore.create(produceFile = { file }, scope = scopes.newScope())

    @Test
    fun offAndEmptyUntilSomethingIsSet() = runTest {
        val store = open()
        assertEquals(StoreReminderRecord(), store.observe("a").first())
        assertNull(store.registration.first())
        assertEquals(emptySet<String>(), store.accounts())
    }

    @Test
    fun theRecordIsPerAccountAndSurvivesARestart() = runTest {
        open().apply {
            update("a") {
                it.copy(enabled = true, spots = listOf(spot), visits = listOf(StoreVisitRecord(1, inside = true, lastTransitionMillis = 5)))
            }
            update("b") { it.copy(spots = listOf(spot.copy(storeId = 2, radiusMeters = 300))) }
            setRegistration(StoreFenceRegistration("a", "1:abc", bootCount = 7, versionCode = 10))
        }
        scopes.closeAll()

        val store = open()
        val a = store.observe("a").first()
        assertEquals(true, a.enabled)
        assertEquals(listOf(spot), a.spots)
        assertEquals(12.3456, a.spots.single().latitude, 0.0)
        assertEquals(true, a.visits.single().inside)
        assertEquals(300, store.observe("b").first().spots.single().radiusMeters)
        assertEquals(StoreFenceRegistration("a", "1:abc", 7, 10), store.registration.first())
        assertEquals(setOf("a", "b"), store.accounts())
    }

    @Test
    fun anAccountThatLeftTakesItsPlacesAlong() = runTest {
        val store = open()
        store.update("a") { it.copy(enabled = true, spots = listOf(spot)) }
        store.update("b") { it.copy(spots = listOf(spot)) }
        store.retainOnly(setOf("b"))
        assertEquals(StoreReminderRecord(), store.observe("a").first())
        assertEquals(setOf("b"), store.accounts())
    }

    @Test
    fun anEmptyRecordLeavesNothingBehind() = runTest {
        val store = open()
        store.update("a") { it.copy(spots = listOf(spot)) }
        store.update("a") { StoreReminderRecord() }
        assertEquals(emptySet<String>(), store.accounts())
    }

    @Test
    fun aCorruptFileReadsAsOff() = runTest {
        file.writeBytes(byteArrayOf(0x7f, 0x01, 0x02, 0x03))
        assertFalse(open().observe("a").first().enabled)
    }

    @Test
    fun aPlaceIsNeverPrinted() {
        assertFalse(spot.toString().contains("12.3"))
        assertFalse(StoreReminderRecord(spots = listOf(spot)).toString().contains("45.6"))
    }
}
