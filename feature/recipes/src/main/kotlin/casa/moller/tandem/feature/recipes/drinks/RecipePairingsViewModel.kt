package casa.moller.tandem.feature.recipes.drinks

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import casa.moller.tandem.core.data.drink.DrinkRepository
import casa.moller.tandem.core.data.permissions.SpacePermissionsRepository
import casa.moller.tandem.core.data.recipe.RecipeRepository
import casa.moller.tandem.core.data.settings.SettingsRepository
import casa.moller.tandem.core.data.shopping.ShoppingRepository
import casa.moller.tandem.core.model.drink.Drink
import casa.moller.tandem.core.model.drink.Pairing
import casa.moller.tandem.core.model.drink.RecipePairings
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.recipe.RecipeOverview
import casa.moller.tandem.core.model.recipe.RecipeQuery
import casa.moller.tandem.core.model.shopping.ShoppingEntryDraft
import casa.moller.tandem.feature.recipes.R
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The recipe detail's "Passar med" (a dish: its drinks and kinds of drink) or "Passar till" (a
 * drink: its dishes), plan §8.8.
 *
 * @property drinks a dish's drinks, each with the pairing keywords that link it to this dish.
 * @property kinds a dish's pairings with a kind of drink ("Kraftigt rött"), shown as text.
 * @property dishes a drink's dishes.
 * @property systembolagetUrl "Sök på Systembolaget" for a drink when the setting is on.
 */
@Immutable
data class RecipePairingsUiState(
    val loaded: Boolean = false,
    val isDrink: Boolean = false,
    val name: String = "",
    val drinks: List<PairedDrink> = emptyList(),
    val kinds: List<Pairing> = emptyList(),
    val dishes: List<RecipeOverview> = emptyList(),
    val readOnly: Boolean = false,
    val systembolagetUrl: String? = null,
) {
    val isEmpty: Boolean
        get() = drinks.isEmpty() && kinds.isEmpty() && dishes.isEmpty()
}

/** A drink paired with the dish and the keywords that link them (removed together). */
@Immutable
data class PairedDrink(val drink: Drink, val keywordIds: List<Long>)

sealed interface RecipePairingsAction {
    data class RemoveDrink(val drink: PairedDrink) : RecipePairingsAction

    data class RemoveKind(val pairing: Pairing) : RecipePairingsAction

    data class RemoveDish(val dishId: Long) : RecipePairingsAction

    /** "Lägg på inköpslistan" for a drink without ingredients: the drink itself goes on the list. */
    data object AddToList : RecipePairingsAction
}

sealed interface RecipePairingsEffect {
    data class Message(@StringRes val text: Int, val argument: String? = null) : RecipePairingsEffect
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = RecipePairingsViewModel.Factory::class)
class RecipePairingsViewModel @AssistedInject constructor(
    @Assisted private val recipeId: Long,
    private val drinks: DrinkRepository,
    recipes: RecipeRepository,
    permissions: SpacePermissionsRepository,
    settings: SettingsRepository,
    private val shopping: ShoppingRepository,
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(recipeId: Long): RecipePairingsViewModel
    }

    private val effectChannel = Channel<RecipePairingsEffect>(Channel.BUFFERED)
    val effects: Flow<RecipePairingsEffect> = effectChannel.receiveAsFlow()

    private val language = Locale.getDefault().language

    /** A dish's drinks with the keywords that link each one to the dish. */
    private val pairings: Flow<Pair<RecipePairings, List<PairedDrink>>> = drinks.observeRecipePairings(recipeId).flatMapLatest { own ->
        if (own.drinks.isEmpty()) return@flatMapLatest flowOf(own to emptyList())
        val ownIds = own.pairings.map { it.keywordId }.toSet()
        combine(own.drinks.map { drink -> drinks.observeRecipePairings(drink.id).map { drink to it.pairings } }) { linked ->
            own to linked.map { (drink, theirs) -> PairedDrink(drink, theirs.map { it.keywordId }.filter { it in ownIds }) }
        }
    }

    val uiState: StateFlow<RecipePairingsUiState> = combine(
        pairings,
        drinks.observeIsDrink(recipeId),
        recipes.observeRecipes(RecipeQuery.All),
        permissions.permissions.map { it.readOnly },
        settings.settings.map { it.systembolagetLinkShown(language) },
    ) { (own, paired), isDrink, all, readOnly, systembolaget ->
        val name = all.firstOrNull { it.id == recipeId }?.name.orEmpty()
        RecipePairingsUiState(
            loaded = true,
            isDrink = isDrink,
            name = name,
            drinks = if (isDrink) emptyList() else paired,
            kinds = if (isDrink) emptyList() else own.pairings.filter { it.isTypeOnly },
            dishes = if (isDrink) own.dishIds.mapNotNull { id -> all.firstOrNull { it.id == id } } else emptyList(),
            readOnly = readOnly,
            systembolagetUrl = if (isDrink && systembolaget && name.isNotBlank()) systembolagetUrl(name) else null,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), RecipePairingsUiState())

    fun onAction(action: RecipePairingsAction) {
        viewModelScope.launch {
            when (action) {
                is RecipePairingsAction.RemoveDrink -> action.drink.keywordIds.forEach { report(drinks.removePairing(recipeId, it)) }

                is RecipePairingsAction.RemoveKind -> report(drinks.removePairing(recipeId, action.pairing.keywordId))

                is RecipePairingsAction.RemoveDish -> drinks.observeRecipePairings(recipeId).first().pairings.forEach {
                    report(drinks.removePairing(action.dishId, it.keywordId))
                }

                RecipePairingsAction.AddToList -> addToList()
            }
        }
    }

    private suspend fun addToList() {
        val name = uiState.value.name.ifBlank { return }
        when (shopping.add(ShoppingEntryDraft(foodName = name))) {
            is Outcome.Success -> effectChannel.trySend(RecipePairingsEffect.Message(R.string.drinks_added_to_list, name))
            is Outcome.Failure -> effectChannel.trySend(RecipePairingsEffect.Message(R.string.drinks_add_to_list_failed))
        }
    }

    private fun report(outcome: Outcome<*>) {
        if (outcome is Outcome.Failure) effectChannel.trySend(RecipePairingsEffect.Message(drinkErrorMessage(outcome.error)))
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
