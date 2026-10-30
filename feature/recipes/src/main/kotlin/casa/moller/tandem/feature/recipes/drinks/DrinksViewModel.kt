package casa.moller.tandem.feature.recipes.drinks

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import casa.moller.tandem.core.data.drink.DrinkRepository
import casa.moller.tandem.core.data.permissions.SpacePermissionsRepository
import casa.moller.tandem.core.data.settings.SettingsRepository
import casa.moller.tandem.core.model.drink.Drink
import casa.moller.tandem.core.model.drink.DrinkKeywords
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.sync.isTransientSyncStop
import casa.moller.tandem.feature.recipes.R
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
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
 * "Drycker" (#43, plan §8.8): every drink of the household, filtered by type and search, offline
 * from the cached recipes. [types] are the types in use plus the suggested ones, sorted by the shown
 * label in the UI's language ([DrinkTypeLabels]).
 *
 * @property drinks the drinks shown (type and search applied), by name; each drink's types by their label.
 * @property totalCount drinks before the filters (empty state vs "nothing matches").
 * @property selectedType the type filter (server name, "Vin"), null = all.
 * @property readOnly a guest: no "Ny dryck".
 * @property systembolagetShown "Sök på Systembolaget" is on (the menu's switch).
 */
@Immutable
data class DrinksUiState(
    val loading: Boolean = true,
    val drinks: List<Drink> = emptyList(),
    val totalCount: Int = 0,
    val types: List<String> = emptyList(),
    val selectedType: String? = null,
    val query: String = "",
    val readOnly: Boolean = false,
    val systembolagetShown: Boolean = false,
    val refreshing: Boolean = false,
)

sealed interface DrinksAction {
    data class Search(val text: String) : DrinksAction

    data class SelectType(val type: String?) : DrinksAction

    data object Refresh : DrinksAction

    data class SetSystembolaget(val shown: Boolean) : DrinksAction

    /** A drink created offline was tapped: it cannot be opened yet. */
    data object OpenPending : DrinksAction
}

sealed interface DrinksEffect {
    data class Message(@StringRes val text: Int) : DrinksEffect
}

@HiltViewModel(assistedFactory = DrinksViewModel.Factory::class)
class DrinksViewModel @AssistedInject constructor(
    @Assisted initialType: String?,
    private val drinks: DrinkRepository,
    permissions: SpacePermissionsRepository,
    private val settings: SettingsRepository,
    private val labels: DrinkTypeLabels,
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(initialType: String?): DrinksViewModel
    }

    private val effectChannel = Channel<DrinksEffect>(Channel.BUFFERED)
    val effects: Flow<DrinksEffect> = effectChannel.receiveAsFlow()

    private val filter = MutableStateFlow(Filter(type = initialType))
    private val refreshing = MutableStateFlow(false)
    private val language = Locale.getDefault().language

    private val sources = combine(
        drinks.observeDrinks(),
        drinks.observeDrinkTypes(),
        permissions.permissions.map { it.readOnly },
        settings.settings.map { it.systembolagetLinkShown(language) },
    ) { all, types, readOnly, systembolaget ->
        // Fas S komplettering: in the order of the shown labels, for the UI's language.
        Sources(labels.sortedDrinks(all), labels.sortedTypes(types), readOnly, systembolaget)
    }

    val uiState: StateFlow<DrinksUiState> = combine(sources, filter, refreshing) { sources, filter, refreshing ->
        val words = searchWords(filter.query)
        val shown = sources.all
            .filter { drink -> filter.type == null || drink.types.any { DrinkKeywords.key(it) == DrinkKeywords.key(filter.type) } }
            .filter { drink -> matches(words, drink.name, drink.note.orEmpty(), drink.types.joinToString(" ")) }
        DrinksUiState(
            loading = false,
            drinks = shown,
            totalCount = sources.all.size,
            types = sources.types,
            selectedType = filter.type,
            query = filter.query,
            readOnly = sources.readOnly,
            systembolagetShown = sources.systembolaget,
            refreshing = refreshing,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), DrinksUiState(selectedType = initialType))

    fun onAction(action: DrinksAction) {
        when (action) {
            is DrinksAction.Search -> filter.value = filter.value.copy(query = action.text)
            is DrinksAction.SelectType -> filter.value = filter.value.copy(type = action.type)
            DrinksAction.Refresh -> refresh()
            is DrinksAction.SetSystembolaget -> viewModelScope.launch { settings.setSystembolagetLink(action.shown) }
            DrinksAction.OpenPending -> effectChannel.trySend(DrinksEffect.Message(R.string.drinks_pending_message))
        }
    }

    private fun refresh() {
        if (refreshing.value) return
        refreshing.value = true
        viewModelScope.launch {
            val result = drinks.refresh()
            refreshing.value = false
            if (result is Outcome.Failure && !result.error.isTransientSyncStop) {
                effectChannel.trySend(DrinksEffect.Message(R.string.drinks_refresh_failed))
            }
        }
    }

    private data class Filter(val type: String? = null, val query: String = "")

    private data class Sources(val all: List<Drink>, val types: List<String>, val readOnly: Boolean, val systembolaget: Boolean)

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
