package casa.moller.tandem.feature.cook.session

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import casa.moller.tandem.core.data.recipe.RecipeRepository
import casa.moller.tandem.core.model.recipe.RecipeOverview
import casa.moller.tandem.core.model.recipe.RecipeQuery
import casa.moller.tandem.core.model.recipe.RecipeSort
import casa.moller.tandem.feature.cook.navigation.CookKey
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The recipes of one cook mode and "Laga ett recept till" (Fas 4, two recipes at once, plan 8.1,
 * Kitshn #433). Each recipe has its own `CookViewModel` in the same cook entry (keyed by
 * [CookSessionRecipe.viewModelKey]); this one only knows which recipes are open, which tab is
 * shown, and the recipe picker. Everything lives in [SavedStateHandle], so both recipes and the
 * selected tab survive rotation and process death.
 *
 * The picker lists the recipes stored on the device (works offline and in demo mode), most recently
 * cooked first, or the search's matches by name; the open recipes are left out. Guests may cook a
 * second recipe too (cooking writes nothing).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = CookSessionViewModel.Factory::class)
class CookSessionViewModel @AssistedInject constructor(
    @Assisted key: CookKey,
    private val savedStateHandle: SavedStateHandle,
    recipes: RecipeRepository,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(key: CookKey): CookSessionViewModel
    }

    private val initial = CookSession.of(key)

    val session: StateFlow<CookSession> = savedStateHandle.getStateFlow(KEY_SESSION, initial.encode())
        .map { CookSession.decode(it) ?: initial }
        .stateIn(viewModelScope, SharingStarted.Eagerly, CookSession.decode(savedStateHandle[KEY_SESSION]) ?: initial)

    private val pickerOpen = savedStateHandle.getStateFlow(KEY_PICKER_OPEN, false)
    private val query = savedStateHandle.getStateFlow(KEY_QUERY, "")

    /** The recipe picker; the stored recipes are only read while it is open. */
    val picker: StateFlow<CookPickerState> = combine(pickerOpen, query) { open, text -> open to text }
        .flatMapLatest { (open, text) ->
            if (!open) {
                flowOf(CookPickerState())
            } else {
                val search = RecipeQuery(text = text.trim(), sort = if (text.isBlank()) RecipeSort.LAST_COOKED else RecipeSort.NAME)
                combine(recipes.observeRecipes(search), session) { found, session ->
                    CookPickerState(
                        open = true,
                        query = text,
                        loading = false,
                        results = found.filterNot { it.id in session }.take(MAX_RESULTS),
                    )
                }
            }
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            CookPickerState(open = pickerOpen.value, query = query.value),
        )

    fun onAction(action: CookSessionAction) {
        when (action) {
            CookSessionAction.OpenPicker -> if (session.value.canAdd) savedStateHandle[KEY_PICKER_OPEN] = true

            CookSessionAction.ClosePicker -> closePicker()

            is CookSessionAction.SetQuery -> savedStateHandle[KEY_QUERY] = action.text.take(MAX_QUERY_LENGTH)

            is CookSessionAction.AddRecipe -> {
                update { it.add(action.recipeId) }
                closePicker()
            }

            is CookSessionAction.SelectRecipe -> update { it.select(action.recipeId) }

            is CookSessionAction.OpenTimer -> update { it.select(action.recipeId) }

            // Removing the only recipe is the route's (cook mode closes); the session keeps it.
            is CookSessionAction.CloseRecipe -> {
                val current = session.value
                closed = current.recipes.firstOrNull { it.recipeId == action.recipeId }
                    ?.takeIf { action.undoable && current.isTwo }
                    ?.let { ClosedRecipe(it, current.recipes.indexOf(it)) }
                closedRecipeId.value = closed?.recipe?.recipeId
                update { it.remove(action.recipeId) ?: it }
            }

            CookSessionAction.RestoreClosed -> {
                val restored = closed ?: return
                closed = null
                closedRecipeId.value = null
                update { it.restore(restored.recipe, restored.index) }
            }
        }
    }

    /** What ✕ took away, while "Ångra" or a tapped timer of it can bring it back (not saved: a snackbar's life). */
    private var closed: ClosedRecipe? = null

    private data class ClosedRecipe(val recipe: CookSessionRecipe, val index: Int)

    /** The recipe ✕ closed, for the timers that are still running for it. */
    val closedRecipeId = MutableStateFlow<Long?>(null)

    private fun closePicker() {
        savedStateHandle[KEY_PICKER_OPEN] = false
        savedStateHandle[KEY_QUERY] = ""
    }

    private fun update(change: (CookSession) -> CookSession) {
        savedStateHandle[KEY_SESSION] = change(session.value).encode()
    }

    internal companion object {
        const val KEY_SESSION = "cook_session"
        const val KEY_PICKER_OPEN = "cook_session_picker_open"
        const val KEY_QUERY = "cook_session_query"

        /** A phone keyboard's search, capped like other searches. */
        const val MAX_QUERY_LENGTH = 200

        /** The picker shows at most this many recipes; the search narrows them. */
        const val MAX_RESULTS = 100

        private const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/**
 * "Laga ett recept till" (Fas 4).
 *
 * @property loading the stored recipes are being read (first frame after opening).
 * @property results the stored recipes to pick from, without the open ones.
 */
@Immutable
data class CookPickerState(
    val open: Boolean = false,
    val query: String = "",
    val loading: Boolean = true,
    val results: List<RecipeOverview> = emptyList(),
)

/** What the cook session can do (Fas 4, two recipes at once). */
sealed interface CookSessionAction {
    /** "Laga ett recept till": open the recipe picker. */
    data object OpenPicker : CookSessionAction

    data object ClosePicker : CookSessionAction

    data class SetQuery(val text: String) : CookSessionAction

    /** A recipe was picked: it opens beside the other one and is selected. */
    data class AddRecipe(val recipeId: Long) : CookSessionAction

    /** A tab was tapped. */
    data class SelectRecipe(val recipeId: Long) : CookSessionAction

    /**
     * ✕ of a recipe in two-recipe mode, or its "Klart!" was saved: the other recipe stays.
     *
     * @property undoable ✕: the step, servings and draft can come back ([RestoreClosed]).
     */
    data class CloseRecipe(val recipeId: Long, val undoable: Boolean = false) : CookSessionAction

    /** "Ångra" after ✕, or a tapped timer of the closed recipe: it comes back as it was. */
    data object RestoreClosed : CookSessionAction

    /** A timer of [recipeId] was tapped: its tab opens at [step] (the route also turns that recipe's page). */
    data class OpenTimer(val recipeId: Long, val step: Int) : CookSessionAction
}
