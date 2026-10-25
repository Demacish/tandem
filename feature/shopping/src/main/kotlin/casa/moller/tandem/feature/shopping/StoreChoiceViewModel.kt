package casa.moller.tandem.feature.shopping

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import casa.moller.tandem.core.data.shopping.SupermarketRepository
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.shopping.Supermarket
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
 * The store chooser (#10, plan 6.2 "Chips för listor och butiker", Stitch butiksl_ge / the
 * tablet's "Butiksgångar"): the stores from Tandoor; the choice is remembered on this device.
 *
 * @property selectedId the store the list is sorted by, null = by category name.
 */
data class StoreChoiceUiState(
    val loading: Boolean = true,
    val stores: List<Supermarket> = emptyList(),
    val selectedId: Long? = null,
)

/** One-off effects of [StoreChoiceViewModel]. */
sealed interface StoreChoiceEffect {
    /** The choice is saved: back to the list. */
    data object Chosen : StoreChoiceEffect

    data class Failed(val error: TandemError) : StoreChoiceEffect
}

/** Choosing the store the list is sorted by (a per-device choice, not a write to the server: guests may choose too). */
@HiltViewModel
class StoreChoiceViewModel @Inject constructor(private val supermarkets: SupermarketRepository) : ViewModel() {

    private val effectChannel = Channel<StoreChoiceEffect>(Channel.BUFFERED)

    val effects: Flow<StoreChoiceEffect> = effectChannel.receiveAsFlow()

    val uiState: StateFlow<StoreChoiceUiState> = combine(
        supermarkets.observeSupermarkets(),
        supermarkets.observeSelectedSupermarket(),
    ) { stores, selected ->
        StoreChoiceUiState(loading = false, stores = stores.sortedBy { it.name.lowercase() }, selectedId = selected)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), StoreChoiceUiState())

    /** Chooses [id] (null = by category name) and closes. */
    fun choose(id: Long?) {
        viewModelScope.launch {
            when (val result = supermarkets.selectSupermarket(id)) {
                is Outcome.Success -> effectChannel.send(StoreChoiceEffect.Chosen)
                is Outcome.Failure -> effectChannel.send(StoreChoiceEffect.Failed(result.error))
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
