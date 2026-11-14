package casa.moller.tandem.core.sync.storereminder

import casa.moller.tandem.core.data.di.ApplicationScope
import casa.moller.tandem.core.data.shopping.StoreReminderRepository
import casa.moller.tandem.core.datastore.StoreReminderRecord
import casa.moller.tandem.core.datastore.StoreReminderStore
import casa.moller.tandem.core.datastore.StoreSpotRecord
import casa.moller.tandem.core.domain.storereminder.GeoText
import casa.moller.tandem.core.domain.storereminder.StoreFences
import casa.moller.tandem.core.domain.storereminder.StoreReminderRadius
import casa.moller.tandem.core.model.shopping.CurrentLocationResult
import casa.moller.tandem.core.model.shopping.GeoPoint
import casa.moller.tandem.core.model.shopping.StoreReminderAvailability
import casa.moller.tandem.core.model.shopping.StoreReminderPermissions
import casa.moller.tandem.core.model.shopping.StoreReminderProblem
import casa.moller.tandem.core.model.shopping.StoreReminderState
import casa.moller.tandem.core.model.shopping.StoreSpotSource
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.account.ActiveSources
import casa.moller.tandem.core.sync.platform.SyncClock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * [StoreReminderRepository] (Fas 5, butik lane, plan §7 #34): the account's record in
 * [StoreReminderStore], the geofences through [StoreReminderRegistrar] (after every change, so the
 * page sees a problem at once), positions through [CurrentLocationGateway].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
internal class DefaultStoreReminderRepository @Inject constructor(
    private val sources: ActiveSources,
    private val store: StoreReminderStore,
    private val registrar: StoreReminderRegistrar,
    private val location: CurrentLocationGateway,
    private val permissions: StoreReminderPermissionReader,
    private val play: PlayServicesCheck,
    private val clock: SyncClock,
    @ApplicationScope private val scope: CoroutineScope,
) : StoreReminderRepository {
    private val permissionReads = MutableStateFlow(0)

    override val state: Flow<StoreReminderState> = combine(sources.source, permissionReads) { source, _ -> source }
        .flatMapLatest { source ->
            when (source) {
                ActiveSource.Loading -> flowOf(StoreReminderState(availability = StoreReminderAvailability.LOADING))
                ActiveSource.None -> flowOf(StoreReminderState(availability = StoreReminderAvailability.NO_ACCOUNT))
                ActiveSource.Demo -> flowOf(StoreReminderState(availability = StoreReminderAvailability.DEMO))
                is ActiveSource.Remote -> remoteState(source.accountId)
            }
        }
        .catch { emit(StoreReminderState(availability = StoreReminderAvailability.NO_ACCOUNT)) }
        .distinctUntilChanged()

    /** The page's state for a logged-in account (Play services first, then the stored record). */
    private fun remoteState(accountId: String): Flow<StoreReminderState> {
        if (!play.isAvailable()) return flowOf(StoreReminderState(availability = StoreReminderAvailability.NO_PLAY_SERVICES))
        val granted = permissions.read()
        return combine(store.observe(accountId), store.registration) { record, registration ->
            val spots = record.spots.mapNotNull { it.toSpot() }
            val fences = StoreFences.wanted(record.enabled, spots)
            StoreReminderState(
                availability = StoreReminderAvailability.AVAILABLE,
                enabled = record.enabled,
                spots = spots.associateBy { it.storeId },
                permissions = granted,
                problem = record.problem?.let { name -> StoreReminderProblem.entries.firstOrNull { it.name == name } },
                watched = if (registration?.accountId == accountId) fences.size else 0,
            )
        }
    }

    override suspend fun setEnabled(enabled: Boolean) {
        val accountId = accountId() ?: return
        store.update(accountId) { it.copy(enabled = enabled, problem = null) }
        registrar.sync(force = enabled)
    }

    override suspend fun useCurrentLocation(storeId: Long): CurrentLocationResult {
        val accountId = accountId()
        if (accountId == null || !play.isAvailable()) return CurrentLocationResult.NotAvailable
        val granted = permissions.read()
        if (!granted.precise) return CurrentLocationResult.NoPermission
        if (!granted.locationOn) return CurrentLocationResult.LocationOff
        val (point, accuracy) = location.current() ?: return CurrentLocationResult.Unavailable
        // Another account became active while waiting for the position: the place is not saved for it.
        if (accountId() != accountId) return CurrentLocationResult.NotAvailable
        savePlace(accountId, storeId, point, StoreSpotSource.CURRENT_LOCATION)
        return CurrentLocationResult.Saved(accuracy)
    }

    override suspend fun setPlaceFromText(storeId: Long, text: String): Boolean {
        val point = GeoText.parse(text) ?: return false
        val accountId = accountId() ?: return false
        savePlace(accountId, storeId, point, StoreSpotSource.PASTED)
        return true
    }

    override suspend fun setStoreEnabled(storeId: Long, enabled: Boolean) = change { record ->
        record.copy(spots = record.spots.map { if (it.storeId == storeId) it.copy(enabled = enabled) else it })
    }

    override suspend fun setRadius(storeId: Long, meters: Int) = change { record ->
        val radius = StoreReminderRadius.snap(meters)
        record.copy(spots = record.spots.map { if (it.storeId == storeId) it.copy(radiusMeters = radius) else it })
    }

    override suspend fun forgetPlace(storeId: Long) = change { record ->
        record.copy(spots = record.spots.filterNot { it.storeId == storeId }, visits = record.visits.filterNot { it.storeId == storeId })
    }

    override fun refreshPermissions() {
        permissionReads.update { it + 1 }
        // Back from the system settings with "Allow all the time" (or without it): bring the geofences in line.
        scope.launch { registrar.sync() }
    }

    override fun currentPermissions(): StoreReminderPermissions = permissions.read()

    /** Saves [point] as [storeId]'s place: a new place keeps the store's radius, is turned on and starts a fresh visit. */
    private suspend fun savePlace(accountId: String, storeId: Long, point: GeoPoint, source: StoreSpotSource) {
        store.update(accountId) { record ->
            val old = record.spots.firstOrNull { it.storeId == storeId }
            val spot = StoreSpotRecord(
                storeId = storeId,
                latitude = point.latitude,
                longitude = point.longitude,
                radiusMeters = old?.radiusMeters ?: StoreReminderRadius.DEFAULT_METERS,
                enabled = true,
                source = source.name,
                savedAtMillis = clock.nowMillis(),
            )
            record.copy(
                spots = (record.spots.filterNot { it.storeId == storeId } + spot).sortedBy { it.storeId },
                visits = record.visits.filterNot { it.storeId == storeId },
            )
        }
        registrar.sync()
    }

    private suspend fun change(transform: (StoreReminderRecord) -> StoreReminderRecord) {
        val accountId = accountId() ?: return
        store.update(accountId, transform)
        registrar.sync()
    }

    /** The active real account (never demo mode). */
    private suspend fun accountId(): String? = (sources.current() as? ActiveSource.Remote)?.accountId
}
