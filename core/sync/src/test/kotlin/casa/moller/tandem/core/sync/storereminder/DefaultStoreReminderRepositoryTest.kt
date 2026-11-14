package casa.moller.tandem.core.sync.storereminder

import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.datastore.StoreVisitRecord
import casa.moller.tandem.core.model.shopping.CurrentLocationResult
import casa.moller.tandem.core.model.shopping.StoreReminderAvailability
import casa.moller.tandem.core.model.shopping.StoreSpotSource
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.storereminder.StoreReminderSamples.OTHER_STORE
import casa.moller.tandem.core.sync.storereminder.StoreReminderSamples.STORE
import casa.moller.tandem.core.sync.testing.FakeClock
import casa.moller.tandem.core.sync.testing.FakeSources
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fas 5 (butik lane, plan §7 #34): the page's data. A place from the position or pasted text is
 * stored for the active account only (with the default 200 m, turned on), the radius snaps to
 * 100–500 m, forgetting a place forgets its visit, and every change brings the geofences in line;
 * demo mode, no account and no Play services are told apart.
 */
@RunWith(AndroidJUnit4::class)
class DefaultStoreReminderRepositoryTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sources = FakeSources(StoreReminderSamples.remote())
    private val store = FakeStoreReminderStore()
    private val gateway = FakeGeofenceGateway()
    private val permissions = FakePermissionReader()
    private val play = FakePlayServices()
    private val location = FakeLocation()
    private val registrar = StoreReminderRegistrar(sources, store, gateway, permissions, play, FakeDeviceKey(), FakeSystemEventSwitch())
    private val repository = DefaultStoreReminderRepository(sources, store, registrar, location, permissions, play, FakeClock(), scope)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun aPositionBecomesTheStoresPlaceWithTheDefaultDistance() = runBlocking {
        assertEquals(CurrentLocationResult.Saved(12), repository.useCurrentLocation(STORE))
        val spot = repository.state.first().spots.getValue(STORE)
        assertEquals(StoreReminderSamples.point, spot.point)
        assertEquals(200, spot.radiusMeters)
        assertTrue(spot.enabled)
        assertEquals(StoreSpotSource.CURRENT_LOCATION, spot.source)
        // Off until the user turns it on: nothing watched yet.
        assertTrue(gateway.registered.isEmpty())
        repository.setEnabled(true)
        assertEquals(listOf(STORE), gateway.registered.map { it.storeId })
        assertEquals(1, repository.state.first().watched)
    }

    @Test
    fun withoutThePreciseLocationOrWithLocationOffNoPositionIsAsked() = runBlocking {
        permissions.permissions = FakePermissionReader.ALL.copy(precise = false, approximateOnly = true)
        assertEquals(CurrentLocationResult.NoPermission, repository.useCurrentLocation(STORE))
        permissions.permissions = FakePermissionReader.ALL.copy(locationOn = false)
        assertEquals(CurrentLocationResult.LocationOff, repository.useCurrentLocation(STORE))
        permissions.permissions = FakePermissionReader.ALL
        location.position = null
        assertEquals(CurrentLocationResult.Unavailable, repository.useCurrentLocation(STORE))
        assertEquals(1, location.calls)
        assertTrue(store.records.value.isEmpty())
    }

    @Test
    fun aPastedPlaceIsReadAndJunkIsRefused() = runBlocking {
        assertFalse(repository.setPlaceFromText(STORE, "Testbutiken"))
        assertTrue(repository.setPlaceFromText(STORE, "geo:12.3456,-45.6789?z=17"))
        val spot = repository.state.first().spots.getValue(STORE)
        assertEquals(StoreSpotSource.PASTED, spot.source)
        assertEquals(-45.6789, spot.point.longitude, 0.0)
    }

    @Test
    fun theRadiusSnapsTheStoreSwitchesAndForgettingTakesTheVisitAlong() = runBlocking {
        repository.setEnabled(true)
        repository.setPlaceFromText(STORE, "12.3456, -45.6789")
        repository.setPlaceFromText(OTHER_STORE, "12.4, -45.7")
        repository.setRadius(STORE, 333)
        assertEquals(350, repository.state.first().spots.getValue(STORE).radiusMeters)
        assertEquals(350, gateway.registered.first { it.storeId == STORE }.radiusMeters)

        repository.setStoreEnabled(OTHER_STORE, false)
        assertEquals(listOf(STORE), gateway.registered.map { it.storeId })

        store.update(StoreReminderSamples.ACCOUNT) { it.copy(visits = listOf(StoreVisitRecord(STORE, inside = true))) }
        repository.forgetPlace(STORE)
        assertFalse(STORE in repository.state.first().spots)
        assertTrue(store.record().visits.isEmpty())
        assertTrue(gateway.registered.isEmpty())
    }

    @Test
    fun whoMayUseItIsToldApart() = runBlocking {
        assertEquals(StoreReminderAvailability.AVAILABLE, repository.state.first().availability)
        sources.source.value = ActiveSource.Demo
        assertEquals(StoreReminderAvailability.DEMO, repository.state.first().availability)
        assertEquals(CurrentLocationResult.NotAvailable, repository.useCurrentLocation(STORE))
        assertFalse(repository.setPlaceFromText(STORE, "12.3456, -45.6789"))
        sources.source.value = ActiveSource.None
        assertEquals(StoreReminderAvailability.NO_ACCOUNT, repository.state.first().availability)
        sources.source.value = StoreReminderSamples.remote()
        play.available = false
        repository.refreshPermissions()
        assertEquals(StoreReminderAvailability.NO_PLAY_SERVICES, repository.state.first().availability)
    }

    @Test
    fun turningItOffRemovesTheGeofencesAndKeepsThePlaces() = runBlocking {
        repository.setPlaceFromText(STORE, "12.3456, -45.6789")
        repository.setEnabled(true)
        repository.setEnabled(false)
        assertTrue(gateway.registered.isEmpty())
        val state = repository.state.first()
        assertFalse(state.enabled)
        assertTrue(STORE in state.spots)
    }
}
