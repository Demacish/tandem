package casa.moller.tandem.core.sync.storereminder

import casa.moller.tandem.core.datastore.StoreFenceRegistration
import casa.moller.tandem.core.datastore.StoreReminderRecord
import casa.moller.tandem.core.datastore.StoreReminderStore
import casa.moller.tandem.core.datastore.StoreSpotRecord
import casa.moller.tandem.core.domain.storereminder.StoreFences
import casa.moller.tandem.core.model.shopping.GeoPoint
import casa.moller.tandem.core.model.shopping.StoreReminderProblem
import casa.moller.tandem.core.model.shopping.StoreSpot
import casa.moller.tandem.core.model.shopping.StoreSpotSource
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.account.ActiveSources
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Fas 5 (butik lane, plan §7 #34): keeps Play services' geofences in line with what the active
 * account wants, one call at a time.
 *
 * - **Wanted:** a logged-in account (never demo mode, never nobody) with the reminder on and its
 *   enabled stores that have a place (core:domain `StoreFences.wanted`).
 * - **Nothing wanted:** every geofence is removed (turning the feature off, logging out, demo mode,
 *   another account without the reminder), and the receiver for reboot, update and location
 *   toggles is switched off, so a phone that never uses the reminder does no work at all.
 * - **Unchanged:** a set already registered for the same account, boot and app version is not
 *   registered again ([StoreFenceRegistration]); a reboot, an app update, a lost set (Play services
 *   said the location went off) or [sync] with `force` registers it again.
 * - **Problems** (location off, a permission taken back, Play services refusing) are stored on the
 *   account's record for the page and tried again at the next start, the next change or the next
 *   system event.
 */
@Singleton
internal class StoreReminderRegistrar @Inject constructor(
    private val sources: ActiveSources,
    private val store: StoreReminderStore,
    private val gateway: GeofenceGateway,
    private val permissions: StoreReminderPermissionReader,
    private val play: PlayServicesCheck,
    private val device: DeviceRegistrationKey,
    private val systemEvents: SystemEventSwitch,
) {
    private val mutex = Mutex()

    /** Brings the geofences in line; [force] registers even a set that looks unchanged. Never throws. */
    suspend fun sync(force: Boolean = false): StoreReminderProblem? = mutex.withLock {
        val accountId = (sources.current() as? ActiveSource.Remote)?.accountId
        val record = accountId?.let { store.observe(it).first() }
        val fences = if (record == null) emptyList() else StoreFences.wanted(record.enabled, record.spots.mapNotNull { it.toSpot() })
        val previous = store.registration.first()
        if (accountId == null || record == null || fences.isEmpty()) {
            removeEverything(accountId, record, previous, force)
            return@withLock null
        }
        systemEvents.setEnabled(true)
        val blocked = blockedBy()
        if (blocked != null) {
            if (previous != null) gateway.removeAll()
            store.setRegistration(null)
            return@withLock recordProblem(accountId, record, blocked)
        }
        val wanted = StoreFenceRegistration(accountId, StoreFences.fingerprint(fences), device.bootCount(), device.versionCode())
        if (!force && previous == wanted && record.problem == null) return@withLock null
        val outcome = gateway.replaceAll(fences).asProblem()
        store.setRegistration(if (outcome == null) wanted else null)
        recordProblem(accountId, record, outcome)
    }

    /** Nothing is wanted: no geofences, no registration, no system-event receiver, no stale problem. */
    private suspend fun removeEverything(
        accountId: String?,
        record: StoreReminderRecord?,
        previous: StoreFenceRegistration?,
        force: Boolean,
    ) {
        if (previous != null || force) gateway.removeAll()
        store.setRegistration(null)
        systemEvents.setEnabled(false)
        if (accountId != null && record?.problem != null) store.update(accountId) { it.copy(problem = null) }
    }

    /** What keeps the geofences from being registered at all (Play services, the two permissions). */
    private fun blockedBy(): StoreReminderProblem? {
        if (!play.isAvailable()) return StoreReminderProblem.FAILED
        val granted = permissions.read()
        return if (granted.precise && granted.background) null else StoreReminderProblem.PERMISSION
    }

    /** Play services dropped every geofence (the location was turned off): registered again at the next sync. */
    suspend fun onGeofencesLost() = mutex.withLock {
        store.setRegistration(null)
        val accountId = (sources.current() as? ActiveSource.Remote)?.accountId ?: return@withLock
        store.update(accountId) { record -> if (record.enabled) record.copy(problem = StoreReminderProblem.LOCATION_OFF.name) else record }
    }

    private suspend fun recordProblem(
        accountId: String,
        record: StoreReminderRecord?,
        problem: StoreReminderProblem?,
    ): StoreReminderProblem? {
        if (record?.problem != problem?.name) store.update(accountId) { it.copy(problem = problem?.name) }
        return problem
    }
}

private fun GeofenceResult.asProblem(): StoreReminderProblem? = when (this) {
    GeofenceResult.OK -> null
    GeofenceResult.LOCATION_OFF -> StoreReminderProblem.LOCATION_OFF
    GeofenceResult.PERMISSION -> StoreReminderProblem.PERMISSION
    GeofenceResult.FAILED -> StoreReminderProblem.FAILED
}

/** The domain's spot for a stored one (null for a place out of range, which is never written). */
internal fun StoreSpotRecord.toSpot(): StoreSpot? {
    val point = runCatching { GeoPoint(latitude, longitude) }.getOrNull() ?: return null
    return StoreSpot(
        storeId = storeId,
        point = point,
        radiusMeters = radiusMeters,
        enabled = enabled,
        source = StoreSpotSource.entries.firstOrNull { it.name == source } ?: StoreSpotSource.PASTED,
        savedAt = savedAtMillis?.let(Instant::fromEpochMilliseconds),
    )
}
