package casa.moller.tandem.feature.recipes.drinks

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import casa.moller.tandem.core.data.drink.DrinkRepository
import casa.moller.tandem.core.data.permissions.SpacePermissionsRepository
import casa.moller.tandem.core.data.recipe.RecipeRepository
import casa.moller.tandem.core.model.drink.Drink
import casa.moller.tandem.core.model.drink.DrinkKeywords
import casa.moller.tandem.core.model.drink.Pairing
import casa.moller.tandem.core.model.drink.RecipePairings
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.recipe.RecipeOverview
import casa.moller.tandem.core.model.recipe.RecipeQuery
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The pairing picker (plan §8.8 "Kopplingen görs från båda håll med en väljare"): for a dish its
 * drinks and kinds of drink (and a new kind), for a drink its dishes. Every tap is saved at once
 * (the outbox sends it; the recipe is read fresh before the PATCH).
 */
@Immutable
data class PairingPickerUiState(
    val loaded: Boolean = false,
    val isDrink: Boolean = false,
    val name: String = "",
    val query: String = "",
    val drinks: List<Choice<Drink>> = emptyList(),
    val kinds: List<Choice<Pairing>> = emptyList(),
    val dishes: List<Choice<RecipeOverview>> = emptyList(),
    val hasDrinks: Boolean = false,
    val readOnly: Boolean = false,
    /** The typed name can become a new kind of drink (no drink or kind has it yet). */
    val canCreateKind: Boolean = false,
) {
    val nothingMatches: Boolean
        get() = query.isNotBlank() && drinks.isEmpty() && kinds.isEmpty() && dishes.isEmpty()
}

/** One row of the picker: the item and whether it is paired now. */
@Immutable
data class Choice<T>(val item: T, val selected: Boolean)

sealed interface PairingPickerAction {
    data class Search(val text: String) : PairingPickerAction

    data class ToggleDrink(val drink: Drink, val selected: Boolean) : PairingPickerAction

    data class ToggleKind(val pairing: Pairing, val selected: Boolean) : PairingPickerAction

    data class ToggleDish(val dish: RecipeOverview, val selected: Boolean) : PairingPickerAction

    data class CreateKind(val name: String) : PairingPickerAction
}

sealed interface PairingPickerEffect {
    data class Message(@StringRes val text: Int) : PairingPickerEffect
}

@HiltViewModel(assistedFactory = PairingPickerViewModel.Factory::class)
class PairingPickerViewModel @AssistedInject constructor(
    @Assisted private val recipeId: Long,
    private val drinks: DrinkRepository,
    recipes: RecipeRepository,
    permissions: SpacePermissionsRepository,
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(recipeId: Long): PairingPickerViewModel
    }

    private val effectChannel = Channel<PairingPickerEffect>(Channel.BUFFERED)
    val effects: Flow<PairingPickerEffect> = effectChannel.receiveAsFlow()

    private val query = MutableStateFlow("")

    private val sources = combine(
        drinks.observeRecipePairings(recipeId),
        drinks.observeIsDrink(recipeId),
        drinks.observeDrinks(),
        drinks.observePairings(),
        recipes.observeRecipes(RecipeQuery.All),
    ) { own, isDrink, allDrinks, pairings, allRecipes -> Sources(own, isDrink, allDrinks, pairings, allRecipes) }

    val uiState: StateFlow<PairingPickerUiState> = combine(
        sources,
        query,
        permissions.permissions.map { it.readOnly },
    ) { sources, query, readOnly -> build(sources, query, readOnly) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), PairingPickerUiState())

    private fun build(sources: Sources, query: String, readOnly: Boolean): PairingPickerUiState {
        val words = searchWords(query)
        val own = sources.own
        val ownKeywords = own.pairings.map { it.keywordId }.toSet()
        val drinkIds = sources.drinks.map { it.id }.toSet()
        val name = sources.recipes.firstOrNull { it.id == recipeId }?.name
            ?: sources.drinks.firstOrNull { it.id == recipeId }?.name.orEmpty()
        if (sources.isDrink) {
            val paired = own.dishIds.toSet()
            val dishes = sources.recipes
                .filter { it.id !in drinkIds && matches(words, it.name) }
                .map { Choice(it, it.id in paired) }
                .sortedByDescending { it.selected }
            return PairingPickerUiState(loaded = true, isDrink = true, name = name, query = query, dishes = dishes, readOnly = readOnly)
        }
        val pairedDrinks = own.drinks.map { it.id }.toSet()
        val drinks = sources.drinks
            .filter { it.id != recipeId && matches(words, it.name, it.types.joinToString(" ")) }
            .map { Choice(it, it.id in pairedDrinks) }
        val kinds = sources.pairings
            .filter { it.isTypeOnly && matches(words, it.name) }
            .map { Choice(it, it.keywordId in ownKeywords) }
        val typed = query.trim()
        val taken = sources.pairings.any { DrinkKeywords.key(it.name) == DrinkKeywords.key(typed) } ||
            sources.drinks.any { DrinkKeywords.key(it.name) == DrinkKeywords.key(typed) }
        return PairingPickerUiState(
            loaded = true,
            name = name,
            query = query,
            drinks = drinks,
            kinds = kinds,
            hasDrinks = sources.drinks.any { it.id != recipeId },
            readOnly = readOnly,
            canCreateKind = typed.isNotEmpty() && !taken && !readOnly,
        )
    }

    fun onAction(action: PairingPickerAction) {
        if (action is PairingPickerAction.Search) {
            query.value = action.text
            return
        }
        viewModelScope.launch {
            when (action) {
                is PairingPickerAction.ToggleDrink -> if (action.selected) {
                    report(drinks.pairWithDrink(recipeId, action.drink.id))
                } else {
                    val linking = drinks.observeRecipePairings(action.drink.id).first().pairings.map { it.keywordId }.toSet()
                    drinks.observeRecipePairings(recipeId).first().pairings.filter { it.keywordId in linking }.forEach {
                        report(drinks.removePairing(recipeId, it.keywordId))
                    }
                }

                is PairingPickerAction.ToggleKind -> report(
                    if (action.selected) {
                        drinks.addPairing(recipeId, action.pairing.keywordId)
                    } else {
                        drinks.removePairing(recipeId, action.pairing.keywordId)
                    },
                )

                is PairingPickerAction.ToggleDish -> if (action.selected) {
                    report(drinks.pairWithDrink(action.dish.id, recipeId))
                } else {
                    val own = drinks.observeRecipePairings(recipeId).first().pairings
                    own.forEach { report(drinks.removePairing(action.dish.id, it.keywordId)) }
                }

                is PairingPickerAction.CreateKind -> {
                    val created = drinks.addNewPairing(recipeId, action.name)
                    report(created)
                    if (created is Outcome.Success) query.value = ""
                }

                is PairingPickerAction.Search -> Unit
            }
        }
    }

    private fun report(outcome: Outcome<*>) {
        if (outcome is Outcome.Failure) effectChannel.trySend(PairingPickerEffect.Message(drinkErrorMessage(outcome.error)))
    }

    private data class Sources(
        val own: RecipePairings,
        val isDrink: Boolean,
        val drinks: List<Drink>,
        val pairings: List<Pairing>,
        val recipes: List<RecipeOverview>,
    )

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
