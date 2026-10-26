package casa.moller.tandem.feature.shopping.recipe

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import casa.moller.tandem.core.data.permissions.SpacePermissionsRepository
import casa.moller.tandem.core.data.preferences.PreferencesRepository
import casa.moller.tandem.core.data.recipe.RecipeRepository
import casa.moller.tandem.core.data.shopping.ShoppingRepository
import casa.moller.tandem.core.domain.format.NumberStyle
import casa.moller.tandem.core.domain.format.toNumberStyle
import casa.moller.tandem.core.domain.recipe.IngredientRow
import casa.moller.tandem.core.domain.recipe.RecipeDisplay
import casa.moller.tandem.core.domain.scaling.ServingsScaling
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.preferences.UserPreferences
import casa.moller.tandem.core.model.recipe.Recipe
import casa.moller.tandem.core.model.shopping.RecipeShoppingRequest
import casa.moller.tandem.core.model.shopping.ShoppingEntry
import casa.moller.tandem.core.model.shopping.ShoppingList
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
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
 * One ingredient in "Lägg på inköpslistan" (feature #12, Stitch l_gg_p_listan_ark).
 *
 * @property amount the scaled amount with its unit ("300 g"), empty when the recipe gives none.
 * @property onHand the food is marked "har hemma" (Tandoor `food_onhand`): unticked at first and
 *   labelled "Finns hemma" (plan 6.2).
 */
data class RecipeShoppingRow(
    val id: Long,
    val food: String,
    val amount: String,
    val note: String = "",
    val onHand: Boolean = false,
    val chosen: Boolean = !onHand,
)

/**
 * "Lägg på inköpslistan" from a recipe or cook mode (feature #12, plan 6.2 "Receptdetalj:
 * Åtgärder", Stitch l_gg_p_listan_ark).
 *
 * @property available the recipe is stored with ingredients that have a food, so it can go on the
 *   list (also offline); false hides the button.
 * @property open the sheet is shown.
 * @property servings the servings the amounts are scaled to (the recipe detail's at first).
 * @property toBuy ingredients not on hand; [atHome] the "Finns hemma" ones.
 * @property lists Tandoor's named lists; [listId] the one the rows go on, null = no list.
 * @property onList unchecked rows of this recipe already on the list (not from a planned meal).
 * @property onListRecipeIds their list recipes ("Ta bort från listan" removes these).
 */
data class RecipeShoppingUiState(
    val loading: Boolean = true,
    val readOnly: Boolean = false,
    val available: Boolean = false,
    val open: Boolean = false,
    val recipeName: String = "",
    val servings: Double = 1.0,
    val servingsText: String = "",
    val toBuy: List<RecipeShoppingRow> = emptyList(),
    val atHome: List<RecipeShoppingRow> = emptyList(),
    val lists: List<ShoppingList> = emptyList(),
    val listId: Long? = null,
    val onList: Int = 0,
    val onListRecipeIds: List<Long> = emptyList(),
    val adding: Boolean = false,
) {
    val chosenCount: Int
        get() = toBuy.count { it.chosen } + atHome.count { it.chosen }

    val canDecreaseServings: Boolean
        get() = servings > MIN_SERVINGS

    /** Every row ticked ("Markera alla" is done). */
    val allChosen: Boolean
        get() = (toBuy + atHome).all { it.chosen }

    /** Exactly what is missing is ticked ("Bara det som saknas"). */
    val onlyMissing: Boolean
        get() = toBuy.all { it.chosen } && atHome.none { it.chosen }

    /** The sheet and the button may change the list. */
    val canAdd: Boolean
        get() = available && !readOnly

    companion object {
        const val MIN_SERVINGS: Double = 1.0
    }
}

sealed interface RecipeShoppingAction {
    /** "Lägg på inköpslistan": opens the sheet at the recipe detail's or cook mode's [servings]. */
    data class Open(val servings: Double?) : RecipeShoppingAction

    data object Dismiss : RecipeShoppingAction

    data class Toggle(val id: Long) : RecipeShoppingAction

    data object SelectAll : RecipeShoppingAction

    data object OnlyMissing : RecipeShoppingAction

    data object IncreaseServings : RecipeShoppingAction

    data object DecreaseServings : RecipeShoppingAction

    data class SelectList(val listId: Long?) : RecipeShoppingAction

    data object Add : RecipeShoppingAction

    /** "Ta bort från listan": the recipe's rows (not those of planned meals) go, Tandoor deletes them with the list recipe. */
    data object RemoveFromList : RecipeShoppingAction

    data class SetLocale(val locale: Locale) : RecipeShoppingAction
}

sealed interface RecipeShoppingEffect {
    /** [count] rows of [recipeName] are on the list now. */
    data class Added(val count: Int, val recipeName: String) : RecipeShoppingEffect

    data class Removed(val count: Int, val recipeName: String) : RecipeShoppingEffect

    data class Failed(val error: TandemError) : RecipeShoppingEffect
}

/**
 * "Lägg på inköpslistan" (feature #12): the recipe's ingredients at the chosen servings, scaled
 * like the recipe detail (core:domain [RecipeDisplay]), each with a checkbox; foods on hand start
 * unticked. "Lägg till" creates ONE list recipe with the chosen entries ([ShoppingRepository.addRecipe]:
 * stored at once and queued, so it works offline); the list shows the recipe on each row, and
 * "Ta bort från listan" removes the recipe with its rows ([ShoppingRepository.removeListRecipe]).
 * Guests see no button and every write is ignored here too (#42).
 */
@HiltViewModel(assistedFactory = RecipeShoppingViewModel.Factory::class)
class RecipeShoppingViewModel @AssistedInject internal constructor(
    @Assisted private val recipeId: Long,
    private val savedStateHandle: SavedStateHandle,
    recipes: RecipeRepository,
    private val shopping: ShoppingRepository,
    preferences: PreferencesRepository,
    permissions: SpacePermissionsRepository,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(recipeId: Long): RecipeShoppingViewModel
    }

    private val effectChannel = Channel<RecipeShoppingEffect>(Channel.BUFFERED)

    /** One-off messages (a snackbar on the recipe or cook mode). */
    val effects: Flow<RecipeShoppingEffect> = effectChannel.receiveAsFlow()

    private val style = MutableStateFlow(Locale.getDefault().toNumberStyle())
    private val adding = MutableStateFlow(false)

    private val choices: Flow<Choices> = combine(
        savedStateHandle.getStateFlow(KEY_OPEN, false),
        savedStateHandle.getStateFlow(KEY_SERVINGS, NO_SERVINGS),
        savedStateHandle.getStateFlow(KEY_TOGGLED, LongArray(0)),
        savedStateHandle.getStateFlow(KEY_LIST, NO_LIST),
    ) { open, servings, toggled, list -> Choices(open, servings.takeIf { it > 0.0 }, toggled.toSet(), list.takeIf { it != NO_LIST }) }

    private val sources: Flow<Sources> = combine(
        recipes.observeRecipe(recipeId).map { it.recipe },
        preferences.observeUserPreferences(),
        permissions.permissions.map { it.readOnly },
        shopping.observeLists(),
        shopping.observeEntries().map { entries -> entries.filter(::isOwnRow) },
    ) { recipe, prefs, readOnly, lists, onList -> Sources(recipe, prefs, readOnly, lists, onList) }

    val uiState: StateFlow<RecipeShoppingUiState> = combine(sources, choices, style, adding) { sources, choices, style, busy ->
        build(sources, choices, style, busy)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), RecipeShoppingUiState())

    /** Rows of the list recipe(s) of this recipe that are not for a planned meal, still to buy. */
    private fun isOwnRow(entry: ShoppingEntry): Boolean {
        val label = entry.recipe ?: return false
        return label.recipeId == recipeId && label.mealPlanId == null && !entry.checked
    }

    fun onAction(action: RecipeShoppingAction) {
        when (action) {
            is RecipeShoppingAction.Open -> open(action.servings)
            RecipeShoppingAction.Dismiss -> savedStateHandle[KEY_OPEN] = false
            is RecipeShoppingAction.Toggle -> toggled { it.toggle(action.id) }
            RecipeShoppingAction.SelectAll -> toggled { uiState.value.atHome.mapTo(HashSet()) { row -> row.id } }
            RecipeShoppingAction.OnlyMissing -> toggled { emptySet() }
            RecipeShoppingAction.IncreaseServings -> servings { floor(it) + 1.0 }
            RecipeShoppingAction.DecreaseServings -> servings { (ceil(it) - 1.0).coerceAtLeast(RecipeShoppingUiState.MIN_SERVINGS) }
            is RecipeShoppingAction.SelectList -> savedStateHandle[KEY_LIST] = action.listId ?: NO_LIST
            RecipeShoppingAction.Add -> add()
            RecipeShoppingAction.RemoveFromList -> removeFromList()
            is RecipeShoppingAction.SetLocale -> style.value = action.locale.toNumberStyle()
        }
    }

    private fun open(servings: Double?) {
        if (!uiState.value.canAdd) return
        savedStateHandle[KEY_SERVINGS] = servings?.takeIf { it > 0.0 } ?: NO_SERVINGS
        savedStateHandle[KEY_TOGGLED] = LongArray(0)
        savedStateHandle[KEY_OPEN] = true
    }

    private fun toggled(change: (Set<Long>) -> Set<Long>) {
        val current = savedStateHandle.get<LongArray>(KEY_TOGGLED)?.toSet().orEmpty()
        savedStateHandle[KEY_TOGGLED] = change(current).toLongArray()
    }

    private fun servings(next: (Double) -> Double) {
        savedStateHandle[KEY_SERVINGS] = next(uiState.value.servings).coerceAtLeast(RecipeShoppingUiState.MIN_SERVINGS)
    }

    private fun add() {
        val state = uiState.value
        if (!state.canAdd || !state.open || state.adding || state.chosenCount == 0) return
        val chosen = (state.toBuy + state.atHome).filter { it.chosen }.mapTo(LinkedHashSet()) { it.id }
        adding.value = true
        viewModelScope.launch {
            val request = RecipeShoppingRequest(
                recipeId = recipeId,
                servings = state.servings,
                ingredientIds = chosen,
                listIds = state.listId?.let { setOf(it) }.orEmpty(),
            )
            val result = shopping.addRecipe(request)
            adding.value = false
            when (result) {
                is Outcome.Success -> {
                    savedStateHandle[KEY_OPEN] = false
                    effectChannel.send(RecipeShoppingEffect.Added(chosen.size, state.recipeName))
                }

                is Outcome.Failure -> effectChannel.send(RecipeShoppingEffect.Failed(result.error))
            }
        }
    }

    private fun removeFromList() {
        val state = uiState.value
        if (state.readOnly || state.onListRecipeIds.isEmpty()) return
        viewModelScope.launch {
            for (group in state.onListRecipeIds) {
                val removed = shopping.removeListRecipe(group)
                if (removed is Outcome.Failure) {
                    effectChannel.send(RecipeShoppingEffect.Failed(removed.error))
                    return@launch
                }
            }
            effectChannel.send(RecipeShoppingEffect.Removed(state.onList, state.recipeName))
        }
    }

    private fun build(sources: Sources, choices: Choices, style: NumberStyle, busy: Boolean): RecipeShoppingUiState {
        val recipe = sources.recipe
        val readOnly = sources.readOnly
        val onListRecipeIds = sources.onList.mapNotNull { it.recipe?.listRecipeId }.distinct()
        if (recipe == null) {
            return RecipeShoppingUiState(
                loading = false,
                readOnly = readOnly,
                lists = sources.lists,
                onList = sources.onList.size,
                onListRecipeIds = onListRecipeIds,
            )
        }
        val servings = choices.servings ?: ServingsScaling.initialServings(recipe, null)
        val rows = rows(recipe, servings, sources.preferences, style, choices.toggled)
        return RecipeShoppingUiState(
            loading = false,
            readOnly = readOnly,
            available = rows.isNotEmpty(),
            open = choices.open && rows.isNotEmpty() && !readOnly,
            recipeName = recipe.name,
            servings = servings,
            servingsText = recipe.servingsText,
            toBuy = rows.filterNot { it.onHand },
            atHome = rows.filter { it.onHand },
            lists = sources.lists,
            listId = choices.listId?.takeIf { id -> sources.lists.any { it.id == id } },
            onList = sources.onList.size,
            onListRecipeIds = onListRecipeIds,
            adding = busy,
        )
    }

    private fun rows(
        recipe: Recipe,
        servings: Double,
        preferences: UserPreferences,
        style: NumberStyle,
        toggled: Set<Long>,
    ): List<RecipeShoppingRow> {
        // Only ingredients with a food can become a list entry (Tandoor needs `food`).
        val withFood = recipe.steps.flatMap { it.ingredients }
            .filter { !it.isHeader && !it.food?.name.isNullOrBlank() }
            .mapTo(HashSet()) { it.id }
        return RecipeDisplay.build(recipe, servings, preferences, style).ingredients
            .filterIsInstance<IngredientRow.Item>()
            .filter { it.id in withFood }
            .distinctBy { it.id }
            .map { item ->
                val amount = listOf(item.amount, item.unit).filter { it.isNotEmpty() }.joinToString(" ")
                RecipeShoppingRow(
                    id = item.id,
                    food = item.food,
                    amount = amount,
                    note = item.note,
                    onHand = item.onHand,
                    chosen = item.onHand == (item.id in toggled),
                )
            }
    }

    private fun Set<Long>.toggle(id: Long): Set<Long> = if (id in this) this - id else this + id

    private data class Choices(val open: Boolean, val servings: Double?, val toggled: Set<Long>, val listId: Long?)

    private data class Sources(
        val recipe: Recipe?,
        val preferences: UserPreferences,
        val readOnly: Boolean,
        val lists: List<ShoppingList>,
        val onList: List<ShoppingEntry>,
    )

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val KEY_OPEN = "recipe-shopping-open"
        const val KEY_SERVINGS = "recipe-shopping-servings"
        const val KEY_TOGGLED = "recipe-shopping-toggled"
        const val KEY_LIST = "recipe-shopping-list"
        const val NO_SERVINGS = -1.0
        const val NO_LIST = -1L
    }
}
