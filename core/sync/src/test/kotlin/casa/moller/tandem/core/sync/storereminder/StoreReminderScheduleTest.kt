package casa.moller.tandem.core.sync.storereminder

import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.datastore.StorageHealthMonitor
import casa.moller.tandem.core.datastore.StoreReminderRecord
import casa.moller.tandem.core.datastore.StoreVisitRecord
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.storereminder.StoreReminderSamples.ACCOUNT
import casa.moller.tandem.core.sync.storereminder.StoreReminderSamples.OTHER_ACCOUNT
import casa.moller.tandem.core.sync.storereminder.StoreReminderSamples.STORE
import casa.moller.tandem.core.sync.testing.FakeAccountStore
import casa.moller.tandem.core.sync.testing.FakeSources
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fas 5 (butik lane, plan §7 #34): started at every process start, the geofences follow the
 * account, its switch and its stores, and an account that leaves the phone takes its places along.
 */
@RunWith(AndroidJUnit4::class)
class StoreReminderScheduleTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sources = FakeSources(StoreReminderSamples.remote())
    private val store = FakeStoreReminderStore()
    private val gateway = FakeGeofenceGateway()
    private val accounts = FakeAccountStore(listOf(StoreReminderSamples.account(), StoreReminderSamples.account(OTHER_ACCOUNT)))
    private val registrar =
        StoreReminderRegistrar(
            sources,
            store,
            gateway,
            FakePermissionReader(),
            FakePlayServices(),
            FakeDeviceKey(),
            FakeSystemEventSwitch(),
        )
    private val schedule = StoreReminderSchedule(sources, store, registrar, accounts, StorageHealthMonitor(), scope)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private suspend fun eventually(what: String, condition: () -> Boolean) {
        withTimeoutOrNull(5_000) {
            while (!condition()) delay(20)
        }
        assertTrue(what, condition())
    }

    @Test
    fun theGeofencesFollowTheSwitchTheStoresAndTheAccount() = runBlocking {
        schedule.start()
        store.update(ACCOUNT) { StoreReminderRecord(enabled = true, spots = listOf(StoreReminderSamples.spot)) }
        eventually("registered") { gateway.registered.map { it.storeId } == listOf(STORE) }

        store.update(ACCOUNT) { it.copy(spots = listOf(StoreReminderSamples.spot.copy(radiusMeters = 350))) }
        eventually("the new radius") { gateway.registered.singleOrNull()?.radiusMeters == 350 }

        sources.source.value = ActiveSource.Demo
        eventually("removed in demo mode") { gateway.registered.isEmpty() }

        sources.source.value = StoreReminderSamples.remote()
        eventually("back with the account") { gateway.registered.isNotEmpty() }

        store.update(ACCOUNT) { it.copy(enabled = false) }
        eventually("removed when off") { gateway.registered.isEmpty() }
    }

    @Test
    fun aVisitDoesNotRegisterAgain() = runBlocking {
        schedule.start()
        store.update(ACCOUNT) { StoreReminderRecord(enabled = true, spots = listOf(StoreReminderSamples.spot)) }
        eventually("registered") { gateway.registered.isNotEmpty() }
        val calls = gateway.calls.size
        store.update(ACCOUNT) { it.copy(visits = listOf(StoreVisitRecord(STORE, inside = true))) }
        delay(300)
        assertEquals(calls, gateway.calls.size)
    }

    @Test
    fun anAccountThatLeavesTakesItsPlacesAlong() = runBlocking {
        store.update(ACCOUNT) { StoreReminderRecord(enabled = true, spots = listOf(StoreReminderSamples.spot)) }
        store.update(OTHER_ACCOUNT) { StoreReminderRecord(spots = listOf(StoreReminderSamples.spot)) }
        schedule.start()
        accounts.remove(OTHER_ACCOUNT)
        eventually("the other account's places are gone") { store.records.value.keys == setOf(ACCOUNT) }

        // Logging out of the last one leaves nothing.
        accounts.clearAll()
        sources.source.value = ActiveSource.None
        eventually("nothing left") { store.records.value.isEmpty() && gateway.registered.isEmpty() }
    }
}
