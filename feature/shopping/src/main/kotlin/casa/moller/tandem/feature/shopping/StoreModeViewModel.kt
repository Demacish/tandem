package casa.moller.tandem.feature.shopping

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import casa.moller.tandem.core.data.shopping.ShoppingRepository
import casa.moller.tandem.core.data.shopping.StoreTripRepository
import casa.moller.tandem.core.data.shopping.SupermarketRepository
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.errorOrNull
import casa.moller.tandem.core.model.shopping.StoreTrip
import casa.moller.tandem.core.model.shopping.Supermarket
import casa.moller.tandem.feature.shopping.navigation.StoreModeKey
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What store mode shows (plan 6.2 "Butiksläge", feature #13, Stitch butiksl_ge): only what is
 * still to buy, by aisle in the store's order.
 *
 * @property sections the unchecked entries that are not delayed, by aisle.
 * @property inCart entries checked since store mode opened, on this phone or another (the progress
 *   "5 av 13 i korgen"; fix-ui: not the ones checked on earlier trips, which Tandoor keeps showing
 *   for days).
 * @property learnedOrder Fas 4: the aisle order learned from this trip and the earlier ones in the
 *   store, offered to be saved as the store's order when three trips agree and it differs
 *   ([LearnedAisleOrder]); null when there is nothing to offer, the offer was declined, or a guest
 *   is shopping.
 */
data class StoreModeUiState(
    val loading: Boolean = true,
    val readOnly: Boolean = false,
    val store: Supermarket? = null,
    val sections: List<ShoppingSection> = emptyList(),
    val inCart: Int = 0,
    val amountStyle: AmountStyle = AmountStyle(),
    val pendingCount: Int = 0,
    val syncNotice: ShoppingSyncNotice? = null,
    val learnedOrder: LearnedOrder? = null,
) {
    /** Rows still to buy (a merged row is one, Fas 5). */
    val toBuy: Int
        get() = sections.sumOf { it.rows.size }

    val total: Int
        get() = toBuy + inCart
}

/** What store mode can ask for. */
sealed interface StoreModeAction {
    /** Tap or swipe: into the cart (checked); "Ångra" takes it back. */
    data class Check(val id: Long) : StoreModeAction

    data object Undo : StoreModeAction

    data object Share : StoreModeAction

    /** Fas 4: saves [StoreModeUiState.learnedOrder] as the store's aisle order. */
    data object SaveOrder : StoreModeAction

    /** Fas 4: "Inte nu" to the learned order, for the rest of this trip. */
    data object DismissOrder : StoreModeAction

    data object Close : StoreModeAction
}

/**
 * Store mode (plan 6.2 "Butiksläge", feature #13): large rows, only unchecked entries, the
 * screen stays on (the screen does that). Polls like the list while it is open, so a partner's
 * changes show up in the store. The time store mode opened is kept in [SavedStateHandle], so the
 * progress survives rotation and process death.
 *
 * Fas 4 "Inlärd butiksordning": the categories are remembered in the order their first entry was
 * checked here (also in [SavedStateHandle]) and recorded as a trip ([StoreTripRepository], on this
 * device); when three trips in the store (this one included) agree on an order other than the
 * store's, the screen offers to save it ([LearnedAisleOrder], plan §10 Fas 4 "Klart när").
 *
 * One visit is one trip however often store mode is left (to add something, to take a call):
 * opened within [SAME_TRIP_WINDOW] of the newest trip in the store it continues that trip, which
 * keeps its start and categories; only "i korgen" goes by the real opening time. A check taken back
 * with "Ångra" leaves no trace of the aisle it was the first check in.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = StoreModeViewModel.Factory::class)
class StoreModeViewModel @AssistedInject internal constructor(
    @Assisted key: StoreModeKey,
    private val savedStateHandle: SavedStateHandle,
    source: ShoppingSource,
    private val shopping: ShoppingRepository,
    private val supermarkets: SupermarketRepository,
    private val trips: StoreTripRepository,
    clock: Clock,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(key: StoreModeKey): StoreModeViewModel
    }

    private val effectChannel = Channel<ShoppingEffect>(Channel.BUFFERED)

    /** One-off effects; collect them once. */
    val effects: Flow<ShoppingEffect> = effectChannel.receiveAsFlow()

    private var undo: (suspend () -> Outcome<*>)? = null

    /** When store mode opened: only what was checked from then on is "i korgen". */
    private val openedAt: Instant = savedStateHandle.get<Long>(KEY_OPENED_AT)?.let { Instant.fromEpochMilliseconds(it) }
        ?: clock.now().also { savedStateHandle[KEY_OPENED_AT] = it.toEpochMilliseconds() }

    /** The categories checked on this trip, first check first (Fas 4, [LearnedAisleOrder]). */
    private val visited = savedStateHandle.getStateFlow(KEY_VISITED, longArrayOf())

    /** The start of the earlier trip this one continues; null: none, this trip starts at [openedAt]. */
    private val continuedTrip = savedStateHandle.getStateFlow<Long?>(KEY_CONTINUED_TRIP, null)

    /** The trip is worked out at the first check (the store comes with the snapshots); checks may overlap. */
    private val tripLock = Mutex()

    private val orderDismissed = savedStateHandle.getStateFlow(KEY_ORDER_DISMISSED, false)

    private val snapshots = source.observe(MutableStateFlow(key.listId), MutableStateFlow(key.supermarketId))
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), replay = 1)

    /** The earlier trips in the shown store, newest first (this trip's own record left out). */
    private val earlierTrips: Flow<List<StoreTrip>> = snapshots.map { it.store?.id }
        .distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else trips.observeTrips(id) }
        .combine(continuedTrip) { list, continued ->
            list.filterNot { it.startedAt == (continued ?: openedAt.toEpochMilliseconds()) }
        }

    val uiState: StateFlow<StoreModeUiState> = combine(
        snapshots,
        visited,
        orderDismissed,
        earlierTrips,
    ) { snapshot, visitedIds, dismissed, earlier ->
        val grouped = snapshot.grouped()
        val readOnly = snapshot.permissions.readOnly
        StoreModeUiState(
            loading = false,
            readOnly = readOnly,
            store = snapshot.store,
            sections = grouped.sections,
            // Fas 5: rows, as the list counts them (a merged row once, when one of its entries went in).
            inCart = grouped.checkedRows.count { row -> row.entries.any { (it.completedAt ?: openedAt) >= openedAt } },
            amountStyle = AmountStyle.of(snapshot.preferences),
            pendingCount = snapshot.pendingCount,
            syncNotice = ShoppingSyncNotice.of(snapshot.pause),
            learnedOrder = if (readOnly || dismissed) {
                null
            } else {
                LearnedAisleOrder.of(snapshot.store, listOf(visitedIds.toList()) + earlier.map { it.categoryIds })
            },
        )
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), StoreModeUiState())

    /** Changes by other household members, as on the list ([ShoppingViewModel.otherChanges]). */
    val otherChanges: Flow<ShoppingEffect.OtherChange> = shopping.observeChanges().inBursts().map { ShoppingEffect.OtherChange(it) }

    fun onAction(action: StoreModeAction) {
        when (action) {
            is StoreModeAction.Check -> check(action.id)
            StoreModeAction.Undo -> takeBack()
            StoreModeAction.SaveOrder -> saveOrder()
            StoreModeAction.DismissOrder -> savedStateHandle[KEY_ORDER_DISMISSED] = true
            StoreModeAction.Share, StoreModeAction.Close -> Unit // Handled by the route.
        }
    }

    /** Into the cart: the row [id] belongs to, every entry behind it (Fas 5, plan §8.2). */
    private fun check(id: Long) {
        val state = uiState.value
        if (state.loading || state.readOnly) return
        val section = state.sections.firstOrNull { section -> section.rows.any { id in it } } ?: return
        val row = section.rows.first { id in it }
        val ids = row.ids
        val storeId = state.store?.id
        viewModelScope.launch {
            // The category this check walked first, if any: undo is single-level, so a check that
            // did not add it cannot take it away, and one that did has no other check in it.
            val added = section.categoryId?.let { walk(it, storeId) }
            when (val result = shopping.setChecked(ids, true)) {
                is Outcome.Success -> {
                    undo = {
                        shopping.setChecked(ids, false).also { outcome ->
                            if (outcome is Outcome.Success && added != null) unwalk(added, storeId)
                        }
                    }
                    effectChannel.send(ShoppingEffect.Show(ShoppingMessage.Checked(row.first.food.name, 1), undoable = true))
                }

                is Outcome.Failure -> {
                    added?.let { unwalk(it, storeId) }
                    effectChannel.send(ShoppingEffect.Failed(result.error))
                }
            }
        }
    }

    /**
     * Remembers [categoryId] as walked and records the trip once it says something (two aisles).
     * Returns [categoryId] when this check was the first in it, null when it was walked already.
     */
    private suspend fun walk(categoryId: Long, storeId: Long?): Long? = tripLock.withLock {
        continueRecentTrip(storeId)
        if (categoryId in visited.value) return@withLock null
        val walked = visited.value + categoryId
        savedStateHandle[KEY_VISITED] = walked
        if (walked.size >= MIN_RECORDED) record(storeId, walked)
        categoryId
    }

    /**
     * An "Ångra" or a failed check: [categoryId] was never walked. A trip that was recorded is
     * recorded again also below [MIN_RECORDED] aisles, or the old copy would stay; the learning
     * ignores a trip with one aisle.
     */
    private suspend fun unwalk(categoryId: Long, storeId: Long?) = tripLock.withLock {
        val before = visited.value
        val walked = before.filter { it != categoryId }.toLongArray()
        savedStateHandle[KEY_VISITED] = walked
        if (before.size >= MIN_RECORDED) record(storeId, walked)
    }

    private suspend fun record(storeId: Long?, walked: LongArray) {
        if (storeId == null) return
        trips.record(StoreTrip(storeId, continuedTrip.value ?: openedAt.toEpochMilliseconds(), walked.toList()))
    }

    /** At the first check: a trip in this store started within [SAME_TRIP_WINDOW] is this visit, go on with it. */
    private suspend fun continueRecentTrip(storeId: Long?) {
        if (storeId == null || visited.value.isNotEmpty() || continuedTrip.value != null) return
        val latest = trips.observeTrips(storeId).first().firstOrNull() ?: return
        val age = openedAt - Instant.fromEpochMilliseconds(latest.startedAt)
        if (!age.isPositive() || age >= SAME_TRIP_WINDOW) return
        savedStateHandle[KEY_CONTINUED_TRIP] = latest.startedAt
        savedStateHandle[KEY_VISITED] = latest.categoryIds.toLongArray()
    }

    private fun takeBack() {
        val action = undo ?: return
        undo = null
        viewModelScope.launch { action().errorOrNull()?.let { effectChannel.send(ShoppingEffect.Failed(it)) } }
    }

    /**
     * Fas 4: the walked order becomes the store's aisle order (queued offline like other changes).
     * It replaces the order for the whole household, so "Ångra" puts the earlier one back.
     */
    private fun saveOrder() {
        val state = uiState.value
        val order = state.learnedOrder ?: return
        val store = state.store ?: return
        val before = store.aisles.sortedBy { it.order }.map { it.relationId }
        viewModelScope.launch {
            when (val result = supermarkets.reorderAisles(order.supermarketId, order.relationIds)) {
                is Outcome.Success -> {
                    savedStateHandle[KEY_ORDER_DISMISSED] = true
                    undo = { supermarkets.reorderAisles(order.supermarketId, before) }
                    effectChannel.send(ShoppingEffect.Show(ShoppingMessage.OrderSaved(store.name), undoable = true))
                }

                is Outcome.Failure -> effectChannel.send(ShoppingEffect.Failed(result.error))
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val MIN_RECORDED = 2

        /** Opening store mode this soon after a trip's start continues that trip (plan §10 Fas 4: "3 handlingsturer"). */
        val SAME_TRIP_WINDOW = 4.hours
        const val KEY_OPENED_AT = "store-mode.openedAt"
        const val KEY_VISITED = "store-mode.visited"
        const val KEY_CONTINUED_TRIP = "store-mode.continuedTrip"
        const val KEY_ORDER_DISMISSED = "store-mode.orderDismissed"
    }
}
