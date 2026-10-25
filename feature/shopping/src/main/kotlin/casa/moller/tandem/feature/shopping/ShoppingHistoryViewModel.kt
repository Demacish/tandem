package casa.moller.tandem.feature.shopping

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import casa.moller.tandem.core.data.permissions.SpacePermissionsRepository
import casa.moller.tandem.core.data.shopping.ShoppingRepository
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.shopping.ShoppingEntryDraft
import casa.moller.tandem.core.model.shopping.ShoppingHistoryItem
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * "Köpt tidigare" (plan 8.2 "Lokal historik"): entries that aged out of Tandoor's list, kept on
 * the device (newest first).
 */
data class ShoppingHistoryUiState(
    val loading: Boolean = true,
    val readOnly: Boolean = false,
    val items: List<ShoppingHistoryItem> = emptyList(),
)

/** The local shopping history; "Lägg till igen" puts a food back on the list. */
@HiltViewModel
class ShoppingHistoryViewModel @Inject constructor(private val shopping: ShoppingRepository, permissions: SpacePermissionsRepository) :
    ViewModel() {

    private val effectChannel = Channel<ShoppingEffect>(Channel.BUFFERED)

    val effects: Flow<ShoppingEffect> = effectChannel.receiveAsFlow()

    val uiState: StateFlow<ShoppingHistoryUiState> = combine(shopping.observeHistory(), permissions.permissions) { items, perms ->
        ShoppingHistoryUiState(loading = false, readOnly = perms.readOnly, items = items)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), ShoppingHistoryUiState())

    /** Adds [item]'s food again, with its last amount and unit. */
    fun addAgain(item: ShoppingHistoryItem) {
        if (uiState.value.readOnly || uiState.value.loading) return
        val draft = ShoppingEntryDraft(foodName = item.foodName, amount = item.amount, unitName = item.unitName, foodId = item.foodId)
        viewModelScope.launch {
            when (val result = shopping.add(draft)) {
                is Outcome.Success -> effectChannel.send(ShoppingEffect.Show(ShoppingMessage.Added(item.foodName, 1)))
                is Outcome.Failure -> effectChannel.send(ShoppingEffect.Failed(result.error))
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
