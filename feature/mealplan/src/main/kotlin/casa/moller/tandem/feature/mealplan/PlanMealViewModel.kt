package casa.moller.tandem.feature.mealplan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import casa.moller.tandem.core.data.book.RecipeBookRepository
import casa.moller.tandem.core.data.mealplan.MealPlanRepository
import casa.moller.tandem.core.data.permissions.SpacePermissionsRepository
import casa.moller.tandem.core.data.preferences.PreferencesRepository
import casa.moller.tandem.core.data.recipe.RecipeRepository
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.mealplan.MealPlanDraft
import casa.moller.tandem.core.model.mealplan.MealPlanEntry
import casa.moller.tandem.core.model.mealplan.MealPlanUpdate
import casa.moller.tandem.core.model.mealplan.MealType
import casa.moller.tandem.core.model.mealplan.MealTypeDraft
import casa.moller.tandem.core.model.preferences.UserPreferences
import casa.moller.tandem.core.model.recipe.RecipeOverview
import casa.moller.tandem.core.model.recipe.RecipeQuery
import casa.moller.tandem.feature.mealplan.navigation.PlanMealKey
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlin.time.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** Plan a meal from a recipe, or a meal with only a title ("Rester", "Äta ute"). */
enum class MealKind { RECIPE, TITLE }

/**
 * The plan-meal page (Stitch planera_m_ltid_ark, plan 6.2 "+ per dag/måltid → välj recept (sök) eller
 * bara en titel"): new meal or change of one.
 *
 * @property editing changing an existing meal (its id in the key); false for a new one.
 * @property gone the meal being changed no longer exists (deleted elsewhere).
 * @property showAddToShopping "Lägg på listan" (new meals with a recipe only: Tandoor's `addshopping`).
 * @property kinds which of [MealKind] can be chosen (a meal with a recipe cannot lose it here).
 * @property wantToCook the household's "Vill laga" queue and [favorites] everyone's favourites,
 *   offered while the search is empty (fix-ui, plan 6.2 "välj recept (sök, Vill laga, Favoriter)").
 * @property confirmDelete "Ta bort från matsedeln?" is asked (fix-ui: no removal by a mis-tap);
 *   [onShoppingList] says that the meal's rows on the list go with it.
 */
data class PlanMealUiState(
    val loading: Boolean = true,
    val editing: Boolean = false,
    val gone: Boolean = false,
    val readOnly: Boolean = false,
    val date: LocalDate = LocalDate(1970, 1, 5),
    val today: LocalDate = LocalDate(1970, 1, 5),
    val mealTypes: List<MealType> = emptyList(),
    val mealTypeId: Long? = null,
    val kind: MealKind = MealKind.RECIPE,
    val kinds: List<MealKind> = MealKind.entries,
    val recipe: RecipeOverview? = null,
    val query: String = "",
    val results: List<RecipeOverview> = emptyList(),
    val title: String = "",
    val servings: Double = DEFAULT_SERVINGS,
    val note: String = "",
    val addToShopping: Boolean = false,
    val newMealType: String? = null,
    val saving: Boolean = false,
    val showErrors: Boolean = false,
    val wantToCook: List<RecipeOverview> = emptyList(),
    val favorites: List<RecipeOverview> = emptyList(),
    val confirmDelete: Boolean = false,
    val onShoppingList: Boolean = false,
) {
    val showAddToShopping: Boolean
        get() = !editing && kind == MealKind.RECIPE && recipe != null

    val titleMissing: Boolean
        get() = kind == MealKind.TITLE && title.isBlank()

    val recipeMissing: Boolean
        get() = kind == MealKind.RECIPE && recipe == null

    val typeMissing: Boolean
        get() = mealTypeId == null

    val canSave: Boolean
        get() = !loading && !readOnly && !gone && !saving

    /** "Vill laga" and "Favoriter" are offered while no recipe is chosen and nothing is searched. */
    val showCollections: Boolean
        get() = kind == MealKind.RECIPE && recipe == null && query.isBlank() && (wantToCook.isNotEmpty() || favorites.isNotEmpty())

    companion object {
        const val DEFAULT_SERVINGS: Double = 4.0
    }
}

sealed interface PlanMealAction {
    data class SetDate(val date: LocalDate) : PlanMealAction

    data class SetMealType(val id: Long) : PlanMealAction

    data class SetKind(val kind: MealKind) : PlanMealAction

    data class Query(val text: String) : PlanMealAction

    data class PickRecipe(val recipe: RecipeOverview) : PlanMealAction

    data object ClearRecipe : PlanMealAction

    data class Title(val text: String) : PlanMealAction

    data class Note(val text: String) : PlanMealAction

    data object MoreServings : PlanMealAction

    data object FewerServings : PlanMealAction

    data class AddToShopping(val add: Boolean) : PlanMealAction

    data object OpenNewMealType : PlanMealAction

    data class NewMealTypeName(val name: String) : PlanMealAction

    data object CreateMealType : PlanMealAction

    data object CancelNewMealType : PlanMealAction

    data object Save : PlanMealAction

    /** "Ta bort från matsedeln": asks first (fix-ui). */
    data object Delete : PlanMealAction

    /** The answer to "Ta bort från matsedeln?". */
    data class ConfirmDelete(val confirmed: Boolean) : PlanMealAction

    data object Close : PlanMealAction
}

sealed interface PlanMealEffect {
    /** Saved or removed: close the page. */
    data object Done : PlanMealEffect

    data class Failed(val error: TandemError) : PlanMealEffect
}

/**
 * The plan-meal page (plan 6.2, Stitch planera_m_ltid_ark).
 *
 * - **New meal** on the key's day and meal type (else the user's default meal type), optionally
 *   with a recipe ("Planera" on a recipe); "Lägg på listan" starts as the user's
 *   `mealplan_autoadd_shopping` and sends `addshopping` exactly when ticked (FACTS "Fas 2 f").
 * - **Changing a meal** ([PlanMealKey.mealPlanId]): the fields start as the meal's; only what
 *   changed is saved (a new day keeps the meal's length).
 * - **Recipes** are searched in the stored recipes (offline), or picked from "Vill laga" and
 *   "Favoriter" (fix-ui); the servings start as the recipe's when its detail is stored (read
 *   without a refresh of its own, fix-ui).
 * - **Removing** a meal asks first and says when its rows on the shopping list go too (fix-ui;
 *   Tandoor deletes them with the meal, FACTS "Fas 2 f").
 * - **Guest** (#42): nothing can be saved.
 * - The form survives rotation (the ViewModel) but not process death: the page then starts again
 *   from its key.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel(assistedFactory = PlanMealViewModel.Factory::class)
class PlanMealViewModel @AssistedInject internal constructor(
    @Assisted private val key: PlanMealKey,
    private val mealPlans: MealPlanRepository,
    private val recipes: RecipeRepository,
    private val preferences: PreferencesRepository,
    permissions: SpacePermissionsRepository,
    clock: Clock,
    books: RecipeBookRepository,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(key: PlanMealKey): PlanMealViewModel
    }

    private val effectChannel = Channel<PlanMealEffect>(Channel.BUFFERED)

    /** One-off effects; collect them once (e.g. in a LaunchedEffect). */
    val effects: Flow<PlanMealEffect> = effectChannel.receiveAsFlow()

    private val form = MutableStateFlow(
        PlanMealUiState(
            date = LocalDate.parse(key.date),
            today = clock.now().toLocalDateTime(TimeZone.currentSystemDefault()).date,
            editing = key.mealPlanId != null,
            mealTypeId = key.mealTypeId,
        ),
    )

    /** The meal being changed, as it was when the page opened. */
    private var original: MealPlanEntry? = null

    private val results: Flow<List<RecipeOverview>> = form.map { it.query.trim() }
        .distinctUntilChanged()
        .debounce(SEARCH_DEBOUNCE_MILLIS)
        .flatMapLatest { text -> recipes.observeRecipes(RecipeQuery(text = text)) }
        .map { it.take(MAX_RESULTS) }
        .catch { emit(emptyList()) }

    /** "Vill laga" and the favourites (fix-ui), a few of each; empty if they cannot be read. */
    private val collections: Flow<Pair<List<RecipeOverview>, List<RecipeOverview>>> = combine(
        books.observeWantToCook().map { it.take(MAX_COLLECTION) }.catch { emit(emptyList()) },
        books.observeFavorites().map { it.take(MAX_COLLECTION) }.catch { emit(emptyList()) },
    ) { wantToCook, favorites -> wantToCook to favorites }

    val uiState: StateFlow<PlanMealUiState> = combine(
        form,
        mealPlans.observeMealTypes(),
        permissions.permissions,
        results,
        collections,
    ) { form, types, perms, found, (wantToCook, favorites) ->
        form.copy(
            mealTypes = types,
            mealTypeId = form.mealTypeId?.takeIf { id -> types.any { it.id == id } } ?: form.mealTypeId.takeIf { types.isEmpty() },
            readOnly = perms.readOnly,
            results = found,
            wantToCook = wantToCook,
            favorites = favorites,
            confirmDelete = form.confirmDelete && !perms.readOnly,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), form.value)

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val types = mealPlans.observeMealTypes().first()
        val prefs = withTimeoutOrNull(RECIPE_WAIT_MILLIS) { preferences.observeUserPreferences().first() } ?: UserPreferences()
        val mealId = key.mealPlanId
        if (mealId != null) {
            val meal = mealPlans.observeMeal(mealId).first()
            original = meal
            form.update { state ->
                if (meal == null) {
                    state.copy(loading = false, gone = true)
                } else {
                    state.copy(
                        loading = false,
                        date = meal.fromDate,
                        mealTypeId = meal.mealType.id,
                        kind = if (meal.recipe != null) MealKind.RECIPE else MealKind.TITLE,
                        kinds = if (meal.recipe != null) listOf(MealKind.RECIPE) else MealKind.entries,
                        recipe = meal.recipe,
                        title = meal.title,
                        servings = meal.servings,
                        note = meal.note,
                        onShoppingList = meal.onShoppingList,
                    )
                }
            }
            return
        }
        val typeId = key.mealTypeId ?: prefs.defaultMealTypeId?.takeIf { id -> types.any { it.id == id } } ?: types.firstOrNull()?.id
        form.update { it.copy(loading = false, mealTypeId = typeId, addToShopping = prefs.mealPlanAutoAddShopping) }
        key.recipeId?.let { id -> pickRecipe(id) }
    }

    /** Preselects a recipe ("Planera" on a recipe): its stored overview and, when stored, its servings. */
    private suspend fun pickRecipe(id: Long) {
        val detail = withTimeoutOrNull(RECIPE_WAIT_MILLIS) { recipes.observeStoredRecipe(id).firstOrNull { !it.isEmpty } }
        val overview = detail?.overview ?: detail?.recipe?.let { RecipeOverview(it.id, it.name, imageUrl = it.imageUrl) } ?: return
        val servings = detail?.recipe?.servings?.toDouble()
        form.update { state -> state.copy(recipe = overview, kind = MealKind.RECIPE, servings = servings ?: state.servings) }
    }

    fun onAction(action: PlanMealAction) {
        when (action) {
            is PlanMealAction.SetDate -> form.update { it.copy(date = action.date) }

            is PlanMealAction.SetMealType -> form.update { it.copy(mealTypeId = action.id) }

            is PlanMealAction.SetKind -> form.update { state -> if (action.kind in state.kinds) state.copy(kind = action.kind) else state }

            is PlanMealAction.Query -> form.update { it.copy(query = action.text) }

            is PlanMealAction.PickRecipe -> {
                form.update { it.copy(recipe = action.recipe, query = "") }
                viewModelScope.launch { servingsOf(action.recipe.id)?.let { servings -> form.update { it.copy(servings = servings) } } }
            }

            PlanMealAction.ClearRecipe -> form.update { it.copy(recipe = null) }

            is PlanMealAction.Title -> form.update { it.copy(title = action.text) }

            is PlanMealAction.Note -> form.update { it.copy(note = action.text) }

            PlanMealAction.MoreServings -> form.update { it.copy(servings = it.servings + 1) }

            PlanMealAction.FewerServings -> form.update { it.copy(servings = (it.servings - 1).coerceAtLeast(MIN_SERVINGS)) }

            is PlanMealAction.AddToShopping -> form.update { it.copy(addToShopping = action.add) }

            PlanMealAction.OpenNewMealType -> form.update { it.copy(newMealType = "") }

            is PlanMealAction.NewMealTypeName -> form.update { it.copy(newMealType = action.name) }

            PlanMealAction.CreateMealType -> createMealType()

            PlanMealAction.CancelNewMealType -> form.update { it.copy(newMealType = null) }

            PlanMealAction.Save -> save()

            PlanMealAction.Delete -> if (original != null && !uiState.value.readOnly) form.update { it.copy(confirmDelete = true) }

            is PlanMealAction.ConfirmDelete -> {
                form.update { it.copy(confirmDelete = false) }
                if (action.confirmed) delete()
            }

            PlanMealAction.Close -> Unit // The route closes the page.
        }
    }

    /** The recipe's own servings when its detail is stored (no request is waited for). */
    private suspend fun servingsOf(recipeId: Long): Double? =
        withTimeoutOrNull(RECIPE_WAIT_MILLIS) { recipes.observeStoredRecipe(recipeId).first() }?.recipe?.servings?.toDouble()

    private fun createMealType() {
        val name = form.value.newMealType?.trim().orEmpty()
        if (name.isEmpty() || uiState.value.readOnly) return
        viewModelScope.launch {
            when (val created = mealPlans.createMealType(MealTypeDraft(name))) {
                is Outcome.Success -> form.update { it.copy(mealTypeId = created.value, newMealType = null) }
                is Outcome.Failure -> effectChannel.send(PlanMealEffect.Failed(created.error))
            }
        }
    }

    private fun save() {
        val state = uiState.value
        if (!state.canSave) return
        if (state.titleMissing || state.recipeMissing || state.typeMissing) {
            form.update { it.copy(showErrors = true) }
            return
        }
        val typeId = state.mealTypeId ?: return
        form.update { it.copy(saving = true) }
        viewModelScope.launch {
            val meal = original
            val result = if (meal == null) {
                mealPlans.create(
                    MealPlanDraft(
                        date = state.date,
                        mealTypeId = typeId,
                        recipeId = state.recipe?.id.takeIf { state.kind == MealKind.RECIPE },
                        title = state.title.trim(),
                        servings = state.servings,
                        note = state.note.trim(),
                        addToShopping = state.showAddToShopping && state.addToShopping,
                    ),
                )
            } else {
                mealPlans.update(meal.id, changes(meal, state, typeId))
            }
            form.update { it.copy(saving = false) }
            when (result) {
                is Outcome.Success -> effectChannel.send(PlanMealEffect.Done)
                is Outcome.Failure -> effectChannel.send(PlanMealEffect.Failed(result.error))
            }
        }
    }

    private fun delete() {
        val meal = original ?: return
        if (uiState.value.readOnly) return
        viewModelScope.launch {
            when (val result = mealPlans.delete(meal.id)) {
                is Outcome.Success -> effectChannel.send(PlanMealEffect.Done)
                is Outcome.Failure -> effectChannel.send(PlanMealEffect.Failed(result.error))
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val SEARCH_DEBOUNCE_MILLIS = 150L
        const val MAX_RESULTS = 30
        const val MAX_COLLECTION = 6
        const val MIN_SERVINGS = 1.0
        const val RECIPE_WAIT_MILLIS = 1_000L
    }
}

/** Only the fields that changed (plan 3.5: a PATCH carries the changed fields). */
internal fun changes(meal: MealPlanEntry, state: PlanMealUiState, typeId: Long): MealPlanUpdate = MealPlanUpdate(
    fromDate = state.date.takeIf { it != meal.fromDate },
    mealTypeId = typeId.takeIf { it != meal.mealType.id },
    recipeId = state.recipe?.id?.takeIf { it != meal.recipe?.id && state.kind == MealKind.RECIPE },
    title = state.title.trim().takeIf { it != meal.title },
    servings = state.servings.takeIf { it != meal.servings },
    note = state.note.trim().takeIf { it != meal.note },
)
