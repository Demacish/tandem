package casa.moller.tandem.core.sync.storereminder

import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.datastore.StoreReminderRecord
import casa.moller.tandem.core.model.shopping.StoreReminderProblem
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.storereminder.StoreReminderSamples.ACCOUNT
import casa.moller.tandem.core.sync.storereminder.StoreReminderSamples.OTHER_STORE
import casa.moller.tandem.core.sync.storereminder.StoreReminderSamples.STORE
import casa.moller.tandem.core.sync.testing.FakeSources
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fas 5 (butik lane, plan §7 #34): the geofences follow what the active account wants. Registered
 * once (not again at every start), again after a reboot, an app update or a lost set; removed when
 * the reminder is turned off, in demo mode and when logged out; a problem is stored for the page.
 */
@RunWith(AndroidJUnit4::class)
class StoreReminderRegistrarTest {
    private val sources = FakeSources(StoreReminderSamples.remote())
    private val store = FakeStoreReminderStore()
    private val gateway = FakeGeofenceGateway()
    private val permissions = FakePermissionReader()
    private val play = FakePlayServices()
    private val device = FakeDeviceKey()
    private val systemEvents = FakeSystemEventSwitch()
    private val registrar = StoreReminderRegistrar(sources, store, gateway, permissions, play, device, systemEvents)

    private fun want(enabled: Boolean = true) = runBlocking {
        store.update(ACCOUNT) {
            StoreReminderRecord(
                enabled = enabled,
                spots = listOf(StoreReminderSamples.spot, StoreReminderSamples.spot.copy(storeId = OTHER_STORE, enabled = false)),
            )
        }
    }

    private fun sync(force: Boolean = false) = runBlocking { registrar.sync(force) }

    @Test
    fun theEnabledStoresAreRegisteredOnceAndTheSystemReceiverTurnedOn() {
        want()
        assertNull(sync())
        assertEquals(listOf(STORE), gateway.registered.map { it.storeId })
        assertEquals(200, gateway.registered.single().radiusMeters)
        assertEquals(true, systemEvents.enabled)
        assertEquals(ACCOUNT, store.registration.value?.accountId)

        // The next process start (a background sync, a geofence event): nothing to do.
        sync()
        assertEquals(1, gateway.calls.count { it.startsWith("replace") })
    }

    @Test
    fun aRebootAnUpdateOrAForcedSyncRegistersAgain() {
        want()
        sync()
        device.boot = 2
        sync()
        device.version = 10
        sync()
        sync(force = true)
        assertEquals(4, gateway.calls.count { it.startsWith("replace") })
    }

    @Test
    fun aLostSetIsRegisteredAgainAtTheNextSync() = runBlocking {
        want()
        sync()
        registrar.onGeofencesLost()
        assertNull(store.registration.value)
        assertEquals(StoreReminderProblem.LOCATION_OFF.name, store.record().problem)
        sync()
        assertEquals(2, gateway.calls.count { it.startsWith("replace") })
        assertNull(store.record().problem)
    }

    @Test
    fun turningItOffRemovesEveryGeofenceAndTheSystemReceiver() {
        want()
        sync()
        want(enabled = false)
        sync()
        assertTrue(gateway.registered.isEmpty())
        assertEquals("remove", gateway.calls.last())
        assertEquals(false, systemEvents.enabled)
        assertNull(store.registration.value)
    }

    @Test
    fun demoModeAndLoggingOutRemoveThem() {
        want()
        sync()
        sources.source.value = ActiveSource.Demo
        sync()
        assertTrue(gateway.registered.isEmpty())
        want()
        sources.source.value = StoreReminderSamples.remote()
        sync()
        sources.source.value = ActiveSource.None
        sync()
        assertTrue(gateway.registered.isEmpty())
        assertEquals(false, systemEvents.enabled)
    }

    @Test
    fun anotherAccountWithoutTheReminderHasNoGeofences() {
        want()
        sync()
        sources.source.value = StoreReminderSamples.remote(StoreReminderSamples.OTHER_ACCOUNT)
        sync()
        assertTrue(gateway.registered.isEmpty())
    }

    @Test
    fun aMissingPermissionOrLocationOffIsStoredForThePage() {
        want()
        permissions.permissions = FakePermissionReader.ALL.copy(background = false)
        assertEquals(StoreReminderProblem.PERMISSION, sync())
        assertEquals(StoreReminderProblem.PERMISSION.name, store.record().problem)
        assertTrue(gateway.calls.none { it.startsWith("replace") })

        permissions.permissions = FakePermissionReader.ALL
        gateway.result = GeofenceResult.LOCATION_OFF
        assertEquals(StoreReminderProblem.LOCATION_OFF, sync())
        assertNull("Not remembered as registered", store.registration.value)

        gateway.result = GeofenceResult.OK
        assertNull(sync())
        assertNull(store.record().problem)
    }

    @Test
    fun withoutPlayServicesNothingIsRegistered() {
        want()
        play.available = false
        assertEquals(StoreReminderProblem.FAILED, sync())
        assertTrue(gateway.calls.none { it.startsWith("replace") })
    }
}
