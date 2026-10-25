package casa.moller.tandem.feature.shopping

import casa.moller.tandem.core.data.permissions.SpacePermissionsRepository
import casa.moller.tandem.core.data.preferences.PreferencesRepository
import casa.moller.tandem.core.data.shopping.ShoppingRepository
import casa.moller.tandem.core.data.shopping.SupermarketRepository
import casa.moller.tandem.core.data.sync.ForegroundSync
import casa.moller.tandem.core.data.sync.SyncRepository
import casa.moller.tandem.core.model.account.SpacePermissions
import casa.moller.tandem.core.model.preferences.UserPreferences
import casa.moller.tandem.core.model.shopping.ShoppingEntry
import casa.moller.tandem.core.model.shopping.ShoppingFilter
import casa.moller.tandem.core.model.shopping.ShoppingList
import casa.moller.tandem.core.model.shopping.Supermarket
import casa.moller.tandem.core.model.shopping.SupermarketCategory
import casa.moller.tandem.core.model.sync.ForegroundTarget
import casa.moller.tandem.core.model.sync.SyncPause
import javax.inject.Inject
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/**
 * Everything the list and the store mode show, read from the stored list (offline first).
 *
 * @property now the time "köp senare" is judged at; moves on when a delayed entry falls due.
 * @property pause why the list may be out of date: foreground polling's reason first, then the sync's.
 */
internal data class ShoppingSnapshot(
    val entries: List<ShoppingEntry>,
    val now: Instant,
    val lists: List<ShoppingList>,
    val supermarkets: List<Supermarket>,
    val categories: List<SupermarketCategory>,
    val store: Supermarket?,
    val permissions: SpacePermissions,
    val preferences: UserPreferences,
    val pendingCount: Int,
    val pause: SyncPause?,
) {
    fun grouped(): GroupedShopping = ShoppingGrouping.group(entries, categories, store, now)
}

/**
 * The shared data source of the shopping screens. Collecting it also keeps the list fresh
 * ([ForegroundSync.keepFresh], plan 3.5: polling while the list is visible, never in demo mode);
 * the ViewModels collect it only while their screen is STARTED (`WhileSubscribed`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class ShoppingSource @Inject constructor(
    private val shopping: ShoppingRepository,
    private val supermarkets: SupermarketRepository,
    private val permissions: SpacePermissionsRepository,
    private val foregroundSync: ForegroundSync,
    private val sync: SyncRepository,
    private val preferences: PreferencesRepository,
    private val clock: Clock,
) {
    /**
     * The list of [listId] (null = every entry) sorted by [storeOverride], or by the store chosen
     * on this device when the override is null.
     */
    fun observe(listId: Flow<Long?>, storeOverride: Flow<Long?>): Flow<ShoppingSnapshot> {
        val storeId = storeOverride.flatMapLatest { id -> if (id != null) flowOf(id) else supermarkets.observeSelectedSupermarket() }
        val entries = combine(listId, storeId) { list, store -> ShoppingFilter(list, store) }
            .distinctUntilChanged()
            .flatMapLatest { filter -> shopping.observeEntries(filter) }
            .flatMapLatest { list -> withNow(list) }
        val reference = combine(
            shopping.observeLists(),
            supermarkets.observeSupermarkets(),
            supermarkets.observeCategories(),
            storeId,
            preferences.observeUserPreferences(),
        ) { lists, stores, categories, store, prefs -> Reference(lists, stores, categories, stores.firstOrNull { it.id == store }, prefs) }
        val polling = foregroundSync.keepFresh(ForegroundTarget.ShoppingList).map { it.paused }.onStart { emit(null) }
        val status = combine(
            permissions.permissions,
            shopping.observePendingCount(),
            sync.status.map { it.pause },
            polling,
        ) { perms, pending, syncPause, pollPause -> Status(perms, pending, pollPause ?: syncPause) }
            .distinctUntilChanged()
        return combine(entries, reference, status) { (list, now), ref, state ->
            ShoppingSnapshot(
                entries = list,
                now = now,
                lists = ref.lists,
                supermarkets = ref.supermarkets,
                categories = ref.categories,
                store = ref.store,
                permissions = state.permissions,
                preferences = ref.preferences,
                pendingCount = state.pending,
                pause = state.pause,
            )
        }
    }

    /**
     * Emits [list] with the current time, and again whenever a "köp senare" entry falls due, so
     * it moves back to its aisle without a database change.
     */
    private fun withNow(list: List<ShoppingEntry>): Flow<Pair<List<ShoppingEntry>, Instant>> = flow {
        var now = clock.now()
        emit(list to now)
        while (true) {
            val next = list.filter { !it.checked }.mapNotNull { it.delayUntil }.filter { it > now }.minOrNull() ?: break
            delay(next - now)
            now = maxOf(clock.now(), next)
            emit(list to now)
        }
    }

    private data class Reference(
        val lists: List<ShoppingList>,
        val supermarkets: List<Supermarket>,
        val categories: List<SupermarketCategory>,
        val store: Supermarket?,
        val preferences: UserPreferences,
    )

    private data class Status(val permissions: SpacePermissions, val pending: Int, val pause: SyncPause?)
}
