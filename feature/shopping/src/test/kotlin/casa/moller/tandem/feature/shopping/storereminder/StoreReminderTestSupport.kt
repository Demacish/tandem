package casa.moller.tandem.feature.shopping.storereminder

import casa.moller.tandem.core.data.shopping.StoreReminderRepository
import casa.moller.tandem.core.domain.storereminder.GeoText
import casa.moller.tandem.core.domain.storereminder.StoreReminderRadius
import casa.moller.tandem.core.model.shopping.CurrentLocationResult
import casa.moller.tandem.core.model.shopping.GeoPoint
import casa.moller.tandem.core.model.shopping.StoreReminderAvailability
import casa.moller.tandem.core.model.shopping.StoreReminderPermissions
import casa.moller.tandem.core.model.shopping.StoreReminderProblem
import casa.moller.tandem.core.model.shopping.StoreReminderState
import casa.moller.tandem.core.model.shopping.StoreSpot
import casa.moller.tandem.core.model.shopping.StoreSpotSource
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** "Påminnelse nära butiken" in memory; [permissions] is what the phone allows. */
internal class FakeStoreReminderRepository(initial: StoreReminderState = StoreReminderSamples.off) : StoreReminderRepository {
    override val state = MutableStateFlow(initial)
    var permissions: StoreReminderPermissions = StoreReminderSamples.allowed
    var location: CurrentLocationResult = CurrentLocationResult.Saved(15)
    val calls = mutableListOf<String>()

    override suspend fun setEnabled(enabled: Boolean) {
        calls += "enabled $enabled"
        state.update { it.copy(enabled = enabled) }
    }

    override suspend fun useCurrentLocation(storeId: Long): CurrentLocationResult {
        calls += "locate $storeId"
        if (location is CurrentLocationResult.Saved) save(storeId, StoreReminderSamples.point, StoreSpotSource.CURRENT_LOCATION)
        return location
    }

    override suspend fun setPlaceFromText(storeId: Long, text: String): Boolean {
        val point = GeoText.parse(text) ?: return false
        calls += "paste $storeId"
        save(storeId, point, StoreSpotSource.PASTED)
        return true
    }

    override suspend fun setStoreEnabled(storeId: Long, enabled: Boolean) = change(storeId) { it.copy(enabled = enabled) }

    override suspend fun setRadius(storeId: Long, meters: Int) =
        change(storeId) { it.copy(radiusMeters = StoreReminderRadius.snap(meters)) }

    override suspend fun forgetPlace(storeId: Long) {
        calls += "forget $storeId"
        state.update { it.copy(spots = it.spots - storeId) }
    }

    override fun refreshPermissions() {
        calls += "refresh"
        state.update { it.copy(permissions = permissions) }
    }

    override fun currentPermissions(): StoreReminderPermissions = permissions

    private fun save(storeId: Long, point: GeoPoint, source: StoreSpotSource) {
        val spot = StoreSpot(storeId, point, 200, source = source, savedAt = StoreReminderSamples.savedAt)
        state.update { it.copy(spots = it.spots + (storeId to spot)) }
    }

    private fun change(storeId: Long, transform: (StoreSpot) -> StoreSpot) {
        calls += "change $storeId"
        state.update { current ->
            current.copy(spots = current.spots.mapValues { (id, spot) -> if (id == storeId) transform(spot) else spot })
        }
    }
}

/** The states the page, its screenshots and its ATF checks show. The places are made up, out at sea. */
internal object StoreReminderSamples {
    const val STORE = 1L
    const val OTHER_STORE = 2L

    val point = GeoPoint(12.3456, -45.6789)

    /** Saturday 14 November 2026. */
    val savedAt: Instant = Instant.parse("2026-11-14T10:00:00Z")

    val allowed = StoreReminderPermissions(precise = true, background = true, notifications = true, locationOn = true)

    val spot = StoreSpot(STORE, point, 200, source = StoreSpotSource.CURRENT_LOCATION, savedAt = savedAt)

    val off = StoreReminderState(availability = StoreReminderAvailability.AVAILABLE, permissions = StoreReminderPermissions())

    val on = StoreReminderState(
        availability = StoreReminderAvailability.AVAILABLE,
        enabled = true,
        spots = mapOf(
            STORE to spot,
            OTHER_STORE to spot.copy(storeId = OTHER_STORE, radiusMeters = 350, enabled = false, source = StoreSpotSource.PASTED),
        ),
        permissions = allowed,
        watched = 1,
    )

    val locationOff = on.copy(permissions = allowed.copy(locationOn = false), problem = StoreReminderProblem.LOCATION_OFF, watched = 0)

    val lost = on.copy(permissions = allowed.copy(background = false), problem = StoreReminderProblem.PERMISSION, watched = 0)

    val demo = StoreReminderState(availability = StoreReminderAvailability.DEMO)

    val noPlayServices = StoreReminderState(availability = StoreReminderAvailability.NO_PLAY_SERVICES)

    val stores = listOf(
        StoreReminderStoreRow(STORE, "Testbutiken"),
        StoreReminderStoreRow(OTHER_STORE, "Hörnbutiken"),
        StoreReminderStoreRow(3, "Torghallen"),
    )

    /** The page as the ViewModel would show [state] with the three stores. */
    fun ui(
        state: StoreReminderState,
        sheet: StoreReminderSheetState? = null,
        denied: PermissionStep? = null,
        rationale: Boolean = false,
        canOpenMaps: Boolean = true,
        stores: List<StoreReminderStoreRow> = this.stores,
    ) = StoreReminderUiState(
        loading = false,
        availability = state.availability,
        enabled = state.enabled,
        stores = stores.map { it.copy(spot = state.spots[it.id]) },
        permissions = state.permissions,
        problem = state.problem,
        watched = state.watched,
        sheet = sheet,
        rationale = rationale,
        denied = denied,
        canOpenMaps = canOpenMaps,
    )

    val sheetWithPlace = StoreReminderSheetState(storeId = STORE, name = "Testbutiken", spot = spot)

    val sheetEmpty = StoreReminderSheetState(storeId = 3, name = "Torghallen", pasteText = "Torghallen, Storgatan", pasteError = true)
}
