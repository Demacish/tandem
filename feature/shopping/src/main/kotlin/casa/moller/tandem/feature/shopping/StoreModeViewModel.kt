package casa.moller.tandem.feature.shopping

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import casa.moller.tandem.core.data.shopping.ShoppingRepository
import casa.moller.tandem.core.data.shopping.SupermarketRepository
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.errorOrNull
import casa.moller.tandem.core.model.shopping.Supermarket
import casa.moller.tandem.feature.shopping.navigation.StoreModeKey
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * What store mode shows (plan 6.2 "Butiksläge", feature #13, Stitch butiksl_ge): only what is
 * still to buy, by aisle in the store's order.
 *
 * @property sections the unchecked entries that are not delayed, by aisle.
 * @property inCart entries checked since store mode opened, on this phone or another (the progress
 *   "5 av 13 i korgen"; fix-ui: not the ones checked on earlier trips, which Tandoor keeps showing
 *   for days).
 * @property learnedOrder Fas 4: the aisle order this trip walked, offered to be saved as the
 *   store's order when it differs ([LearnedAisleOrder]); null when there is nothing to offer, the
 *   offer was declined, or a guest is shopping.
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
    val toBuy: Int
        get() = sections.sumOf { it.entries.size }

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

    /** Fas 4: "Nej tack" to the learned order, for the rest of this trip. */
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
 * checked here (also in [SavedStateHandle]); when at least two of the store's aisles were walked in
 * another order than the store's, the screen offers to save the walked order ([LearnedAisleOrder]).
 */
@HiltViewModel(assistedFactory = StoreModeViewModel.Factory::class)
class StoreModeViewModel @AssistedInject internal constructor(
    @Assisted key: StoreModeKey,
    private val savedStateHandle: SavedStateHandle,
    source: ShoppingSource,
    private val shopping: ShoppingRepository,
    private val supermarkets: SupermarketRepository,
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

    private val orderDismissed = savedStateHandle.getStateFlow(KEY_ORDER_DISMISSED, false)

    val uiState: StateFlow<StoreModeUiState> = combine(
        source.observe(MutableStateFlow(key.listId), MutableStateFlow(key.supermarketId)),
        visited,
        orderDismissed,
    ) { snapshot, visitedIds, dismissed ->
        val grouped = snapshot.grouped()
        val readOnly = snapshot.permissions.readOnly
        StoreModeUiState(
            loading = false,
            readOnly = readOnly,
            store = snapshot.store,
            sections = grouped.sections,
            inCart = grouped.checked.count { (it.completedAt ?: openedAt) >= openedAt },
            amountStyle = AmountStyle.of(snapshot.preferences),
            pendingCount = snapshot.pendingCount,
            syncNotice = ShoppingSyncNotice.of(snapshot.pause),
            learnedOrder = if (readOnly || dismissed) null else LearnedAisleOrder.of(snapshot.store, visitedIds.toList()),
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

    private fun check(id: Long) {
        val state = uiState.value
        if (state.loading || state.readOnly) return
        val section = state.sections.firstOrNull { section -> section.entries.any { it.id == id } } ?: return
        val entry = section.entries.first { it.id == id }
        val categoryId = section.categoryId
        if (categoryId != null && categoryId !in visited.value) savedStateHandle[KEY_VISITED] = visited.value + categoryId
        viewModelScope.launch {
            when (val result = shopping.setChecked(listOf(id), true)) {
                is Outcome.Success -> {
                    undo = { shopping.setChecked(listOf(id), false) }
                    effectChannel.send(ShoppingEffect.Show(ShoppingMessage.Checked(entry.food.name, 1), undoable = true))
                }

                is Outcome.Failure -> effectChannel.send(ShoppingEffect.Failed(result.error))
            }
        }
    }

    private fun takeBack() {
        val action = undo ?: return
        undo = null
        viewModelScope.launch { action().errorOrNull()?.let { effectChannel.send(ShoppingEffect.Failed(it)) } }
    }

    /** Fas 4: the walked order becomes the store's aisle order (queued offline like other changes). */
    private fun saveOrder() {
        val order = uiState.value.learnedOrder ?: return
        val storeName = uiState.value.store?.name ?: return
        viewModelScope.launch {
            when (val result = supermarkets.reorderAisles(order.supermarketId, order.relationIds)) {
                is Outcome.Success -> {
                    savedStateHandle[KEY_ORDER_DISMISSED] = true
                    effectChannel.send(ShoppingEffect.Show(ShoppingMessage.OrderSaved(storeName)))
                }

                is Outcome.Failure -> effectChannel.send(ShoppingEffect.Failed(result.error))
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val KEY_OPENED_AT = "store-mode.openedAt"
        const val KEY_VISITED = "store-mode.visited"
        const val KEY_ORDER_DISMISSED = "store-mode.orderDismissed"
    }
}
