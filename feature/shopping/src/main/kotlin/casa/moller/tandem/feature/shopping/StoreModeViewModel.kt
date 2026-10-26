package casa.moller.tandem.feature.shopping

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import casa.moller.tandem.core.data.shopping.ShoppingRepository
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

    data object Close : StoreModeAction
}

/**
 * Store mode (plan 6.2 "Butiksläge", feature #13): large rows, only unchecked entries, the
 * screen stays on (the screen does that). Polls like the list while it is open, so a partner's
 * changes show up in the store. The time store mode opened is kept in [SavedStateHandle], so the
 * progress survives rotation and process death.
 */
@HiltViewModel(assistedFactory = StoreModeViewModel.Factory::class)
class StoreModeViewModel @AssistedInject internal constructor(
    @Assisted key: StoreModeKey,
    savedStateHandle: SavedStateHandle,
    source: ShoppingSource,
    private val shopping: ShoppingRepository,
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

    val uiState: StateFlow<StoreModeUiState> = source.observe(MutableStateFlow(key.listId), MutableStateFlow(key.supermarketId))
        .map { snapshot ->
            val grouped = snapshot.grouped()
            StoreModeUiState(
                loading = false,
                readOnly = snapshot.permissions.readOnly,
                store = snapshot.store,
                sections = grouped.sections,
                inCart = grouped.checked.count { (it.completedAt ?: openedAt) >= openedAt },
                amountStyle = AmountStyle.of(snapshot.preferences),
                pendingCount = snapshot.pendingCount,
                syncNotice = ShoppingSyncNotice.of(snapshot.pause),
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), StoreModeUiState())

    /** Changes by other household members, as on the list ([ShoppingViewModel.otherChanges]). */
    val otherChanges: Flow<ShoppingEffect.OtherChange> = shopping.observeChanges().inBursts().map { ShoppingEffect.OtherChange(it) }

    fun onAction(action: StoreModeAction) {
        when (action) {
            is StoreModeAction.Check -> check(action.id)
            StoreModeAction.Undo -> takeBack()
            StoreModeAction.Share, StoreModeAction.Close -> Unit // Handled by the route.
        }
    }

    private fun check(id: Long) {
        val state = uiState.value
        if (state.loading || state.readOnly) return
        val entry = state.sections.flatMap { it.entries }.firstOrNull { it.id == id } ?: return
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

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val KEY_OPENED_AT = "store-mode.openedAt"
    }
}
