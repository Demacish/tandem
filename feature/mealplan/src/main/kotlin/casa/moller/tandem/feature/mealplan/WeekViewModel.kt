package casa.moller.tandem.feature.mealplan

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import casa.moller.tandem.core.data.mealplan.MealPlanRepository
import casa.moller.tandem.core.data.pantry.MakeNowRepository
import casa.moller.tandem.core.data.permissions.SpacePermissionsRepository
import casa.moller.tandem.core.data.recipe.RecipeRepository
import casa.moller.tandem.core.data.shopping.ShoppingRepository
import casa.moller.tandem.core.data.sync.ForegroundSync
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.errorOrNull
import casa.moller.tandem.core.model.makenow.ExpiringFood
import casa.moller.tandem.core.model.makenow.MakeNowFilter
import casa.moller.tandem.core.model.makenow.MakeNowState
import casa.moller.tandem.core.model.mealplan.MealPlanDraft
import casa.moller.tandem.core.model.mealplan.MealPlanEntry
import casa.moller.tandem.core.model.mealplan.MealPlanUpdate
import casa.moller.tandem.core.model.mealplan.MealPlanWeek
import casa.moller.tandem.core.model.recipe.RecipeOverview
import casa.moller.tandem.core.model.recipe.RecipeQuery
import casa.moller.tandem.core.model.shopping.RecipeShoppingRequest
import casa.moller.tandem.core.model.shopping.ShoppingEntry
import casa.moller.tandem.core.model.sync.ForegroundSyncState
import casa.moller.tandem.core.model.sync.ForegroundTarget
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlin.time.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/**
 * The Veckan tab (plan 6.2 "Veckan", feature #15; Stitch veckan_matsedel*).
 *
 * - **Offline first:** the week comes from the stored plan ([MealPlanRepository.observeWeek]); a
 *   change is stored at once and queued, so the screen works the same offline.
 * - **Fresh while visible:** collecting the state keeps the shown week fresh
 *   ([ForegroundSync.keepFresh], at most every 60 s; never in demo mode).
 * - **Undo:** removing and moving a meal show a snackbar with "Ångra". Removing plans the meal
 *   again with the same values; fix-ui: a meal with rows on the shopping list asks first (Tandoor
 *   deletes them with the meal), and "Ångra" puts the rows that were still to buy back, linked to
 *   the new meal. 0.3.0 komplettering: the same for a meal removed on its own page ([RemovedMeals]).
 * - **Long press** on a meal opens "Flytta" (plan 6.2; fix-ui), as the menu does.
 * - **Guest** (#42): read-only; actions that write are ignored (the repository refuses them too).
 * - **Survives** rotation and process death: the shown week and the selected day live in
 *   [SavedStateHandle].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = WeekViewModel.Factory::class)
class WeekViewModel @AssistedInject internal constructor(
    @Assisted weekStart: String?,
    private val savedStateHandle: SavedStateHandle,
    private val mealPlans: MealPlanRepository,
    permissions: SpacePermissionsRepository,
    private val foregroundSync: ForegroundSync,
    private val clock: Clock,
    private val shopping: ShoppingRepository,
    // 0.3.0 komplettering: meals removed on their own page get "Ångra" here.
    private val removedMeals: RemovedMeals,
    // Fas 4: the recipes "Föreslå matsedel" picks from.
    private val recipes: RecipeRepository,
    // Fas 4 (fas4-kvar): "Använd upp", so suggestions prefer recipes that use what expires soon.
    private val makeNow: MakeNowRepository,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(weekStart: String?): WeekViewModel
    }

    private val effectChannel = Channel<WeekEffect>(Channel.BUFFERED)

    /** One-off effects; collect them once (e.g. in a LaunchedEffect). */
    val effects: Flow<WeekEffect> = effectChannel.receiveAsFlow()

    private val transient = MutableStateFlow(Transient())

    /** The last change that "Ångra" takes back. */
    private var undo: (suspend () -> Outcome<*>)? = null

    init {
        if (!savedStateHandle.contains(KEY_WEEK)) {
            val start = weekStart?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: today()
            savedStateHandle[KEY_WEEK] = MealPlanWeek.startOf(start).toString()
        }
        // 0.3.0 komplettering: a meal removed on the plan-meal page gets the same "Ångra" as one removed here.
        viewModelScope.launch {
            removedMeals.removals.filterNotNull().collect {
                val removal = removedMeals.take() ?: return@collect
                undo = { restore(removal.meal, removal.rows) }
                effectChannel.send(WeekEffect.Show(WeekMessage.Removed(removal.meal.displayTitle)))
            }
        }
    }

    private val shownWeek: Flow<LocalDate> = savedStateHandle.getStateFlow(KEY_WEEK, MealPlanWeek.startOf(today()).toString())
        .map { LocalDate.parse(it) }
        .distinctUntilChanged()

    private val selectedDay: Flow<LocalDate?> = savedStateHandle.getStateFlow<String?>(KEY_DAY, null)
        .map { day -> day?.let { runCatching { LocalDate.parse(it) }.getOrNull() } }

    /** The shown week, kept fresh while collected (foreground polling stops with the last collector). */
    private val week: Flow<MealPlanWeek> = shownWeek.flatMapLatest { start ->
        combine(
            mealPlans.observeWeek(start),
            foregroundSync.keepFresh(ForegroundTarget.MealPlan(start)).onStart { emit(ForegroundSyncState()) },
        ) { week, _ -> week }
    }

    val uiState: StateFlow<WeekUiState> = combine(
        week,
        mealPlans.observeMealTypes(),
        permissions.permissions,
        combine(mealPlans.observeConflicts(), selectedDay) { conflicts, day -> conflicts to day },
        transient,
    ) { week, types, perms, (conflicts, day), local ->
        val readOnly = perms.readOnly
        val meals = week.days.flatMap { it.meals }
        WeekUiState(
            loading = false,
            weekStart = week.start,
            today = today(),
            days = week.days,
            mealTypes = types,
            readOnly = readOnly,
            householdMissing = perms.householdMissing,
            sharedWith = perms.sharedWith,
            refreshing = local.refreshing,
            selectedDay = day,
            move = local.move.takeUnless { readOnly },
            deleting = local.deleting.takeUnless { readOnly },
            suggestions = local.suggestions.takeUnless { readOnly },
            conflict = conflicts.firstOrNull()?.takeUnless { readOnly }?.let { conflict ->
                ConflictState(conflict, meals.firstOrNull { it.id == conflict.mealPlanId }?.displayTitle.orEmpty())
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), WeekUiState())

    fun onAction(action: WeekAction) {
        when (action) {
            WeekAction.PreviousWeek -> showWeek(currentWeek().minus(MealPlanWeek.DAYS, DateTimeUnit.DAY))

            WeekAction.NextWeek -> showWeek(currentWeek().plus(MealPlanWeek.DAYS, DateTimeUnit.DAY))

            WeekAction.ThisWeek -> {
                showWeek(MealPlanWeek.startOf(today()))
                savedStateHandle[KEY_DAY] = today().toString()
            }

            WeekAction.Refresh -> refresh()

            is WeekAction.SelectDay -> savedStateHandle[KEY_DAY] = action.date.toString()

            is WeekAction.Delete -> askDelete(action.meal)

            is WeekAction.ConfirmDelete -> {
                val deleting = transient.value.deleting
                transient.update { it.copy(deleting = null) }
                if (action.confirmed && deleting != null) delete(deleting.meal)
            }

            WeekAction.Undo -> takeBack()

            is WeekAction.StartMove -> if (canWrite()) {
                transient.update { it.copy(move = MoveMealState(action.meal, action.meal.fromDate, action.meal.mealType.id)) }
            }

            is WeekAction.MoveDate -> transient.update { state -> state.copy(move = state.move?.copy(date = action.date)) }

            is WeekAction.MoveMealType -> transient.update { state -> state.copy(move = state.move?.copy(mealTypeId = action.mealTypeId)) }

            WeekAction.ConfirmMove -> confirmMove()

            is WeekAction.DropMeal -> dropMeal(action)

            WeekAction.CancelMove -> transient.update { it.copy(move = null) }

            is WeekAction.ResolveConflict -> resolveConflict(action.keepMine)

            WeekAction.Suggest -> suggest(seed = 0)

            WeekAction.Reshuffle -> suggest(seed = transient.value.suggestionSeed + 1)

            is WeekAction.SwapSuggestion -> swapSuggestion(action.date)

            is WeekAction.RemoveSuggestion -> removeSuggestion(action.date)

            WeekAction.AcceptSuggestions -> acceptSuggestions()

            is WeekAction.SuggestMealType -> {
                transient.update { it.copy(suggestMealTypeId = action.id) }
                suggest(seed = 0)
            }

            is WeekAction.ToggleSuggestKeyword -> {
                transient.update { state ->
                    val ids = state.suggestKeywordIds
                    state.copy(suggestKeywordIds = if (action.id in ids) ids - action.id else ids + action.id)
                }
                suggest(seed = 0)
            }

            WeekAction.CancelSuggestions -> transient.update {
                it.copy(suggestions = null, candidates = emptyList(), expiring = emptyMap())
            }

            else -> Unit // Navigation (plan, edit, recipe, cook, "Handla för veckan") is the route's.
        }
    }

    private fun today(): LocalDate = clock.now().toLocalDateTime(TimeZone.currentSystemDefault()).date

    private fun currentWeek(): LocalDate =
        savedStateHandle.get<String>(KEY_WEEK)?.let { LocalDate.parse(it) } ?: MealPlanWeek.startOf(today())

    private fun showWeek(start: LocalDate) {
        savedStateHandle[KEY_WEEK] = start.toString()
    }

    private fun canWrite(): Boolean = !uiState.value.loading && !uiState.value.readOnly

    private fun refresh() {
        if (transient.value.refreshing) return
        transient.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            val error = mealPlans.refresh().errorOrNull()
            transient.update { it.copy(refreshing = false) }
            if (error != null) effectChannel.send(WeekEffect.Failed(error))
        }
    }

    /** A meal with rows on the shopping list asks first (fix-ui); one without is removed with "Ångra". */
    private fun askDelete(meal: MealPlanEntry) {
        if (!canWrite()) return
        viewModelScope.launch {
            val rows = rowsOf(meal)
            if (rows.isNotEmpty() || meal.onShoppingList) {
                transient.update { it.copy(deleting = DeleteMealState(meal, rows.size)) }
            } else {
                delete(meal)
            }
        }
    }

    private fun delete(meal: MealPlanEntry) {
        if (!canWrite()) return
        viewModelScope.launch {
            // What "Ångra" puts back: the meal's rows still to buy (Tandoor deletes them with it).
            val rows = rowsOf(meal).filterNot { it.checked }
            write(WeekMessage.Removed(meal.displayTitle), undo = { restore(meal, rows) }) { mealPlans.delete(meal.id) }
        }
    }

    private suspend fun rowsOf(meal: MealPlanEntry): List<ShoppingEntry> =
        shopping.observeEntries().first().filter { it.recipe?.mealPlanId == meal.id }

    /** Plans [meal] again and puts its [rows] back on the list, per list recipe, linked to the new meal. */
    private suspend fun restore(meal: MealPlanEntry, rows: List<ShoppingEntry>): Outcome<*> {
        val created = mealPlans.create(meal.toDraft())
        if (created !is Outcome.Success || rows.isEmpty()) return created
        val requests = rows.groupBy { it.recipe?.listRecipeId }.values.mapNotNull { group -> requestFor(meal, group, created.value) }
        for (request in requests) {
            val added = shopping.addRecipe(request)
            if (added is Outcome.Failure) return added
        }
        return created
    }

    /** The request that puts one list recipe's [group] of rows back, linked to [mealPlanId]; null without a recipe or ingredients. */
    private fun requestFor(meal: MealPlanEntry, group: List<ShoppingEntry>, mealPlanId: Long): RecipeShoppingRequest? {
        val label = group.first().recipe ?: return null
        val recipeId = label.recipeId ?: meal.recipe?.id ?: return null
        val ingredients = group.mapNotNull { it.ingredientId }.toSet()
        if (ingredients.isEmpty()) return null
        return RecipeShoppingRequest(
            recipeId = recipeId,
            servings = label.servings,
            ingredientIds = ingredients,
            mealPlanId = mealPlanId,
            listIds = group.first().listIds,
        )
    }

    private fun confirmMove() {
        val move = transient.value.move ?: return
        transient.update { it.copy(move = null) }
        if (!move.changed) return
        moveMeal(move.meal, move.date, move.mealTypeId)
    }

    /** Fas 3 (dra och släpp): the dropped meal, found in the shown week, moved like the sheet moves it. */
    private fun dropMeal(action: WeekAction.DropMeal) {
        val meal = uiState.value.days.flatMap { it.meals }.firstOrNull { it.id == action.mealId } ?: return
        val mealTypeId = action.mealTypeId ?: meal.mealType.id
        if (action.date == meal.fromDate && mealTypeId == meal.mealType.id) return
        moveMeal(meal, action.date, mealTypeId)
    }

    /** Moves [meal] to [date] (keeping its length) and [mealTypeId], with "Ångra"; nothing for a guest. */
    private fun moveMeal(meal: MealPlanEntry, date: LocalDate, mealTypeId: Long) {
        if (!canWrite()) return
        val length = meal.fromDate.daysUntil(meal.toDate).coerceAtLeast(0)
        val update = MealPlanUpdate(
            fromDate = date,
            toDate = date.plus(length, DateTimeUnit.DAY),
            mealTypeId = mealTypeId.takeIf { it != meal.mealType.id },
        )
        val back = MealPlanUpdate(
            fromDate = meal.fromDate,
            toDate = meal.toDate,
            mealTypeId = meal.mealType.id.takeIf { it != mealTypeId },
        )
        write(WeekMessage.Moved(meal.displayTitle, date), undo = { mealPlans.update(meal.id, back) }) {
            mealPlans.update(meal.id, update)
        }
    }

    // Fas 4 "Förslag på veckans matsedel".

    /**
     * Opens (or reshuffles, or redoes after a new meal type or keyword) the sheet: a suggestion per
     * free day from the stored recipes with the chosen meal type and keywords, the ones that use
     * soon-expiring pantry foods first ("Använd upp"; read once per sheet like the recipes).
     */
    private fun suggest(seed: Int) {
        if (!canWrite()) return
        viewModelScope.launch {
            val before = transient.value
            val candidates = before.candidates.ifEmpty { recipes.observeRecipes(RecipeQuery.All).first() }
            val expiring = if (before.candidates.isEmpty()) expiringFoods() else before.expiring
            val state = uiState.value
            val chosen = transient.value
            val suggestions = WeekSuggestions.suggest(
                days = state.days,
                mealTypes = state.mealTypes,
                candidates = candidates,
                today = today(),
                now = clock.now(),
                seed = seed,
                mealTypeId = chosen.suggestMealTypeId,
                keywordIds = chosen.suggestKeywordIds,
                expiring = expiring,
            )
            transient.update { it.copy(suggestions = suggestions, candidates = candidates, expiring = expiring, suggestionSeed = seed) }
        }
    }

    /** The soon-expiring pantry foods per recipe, from "Kan laga nu" as stored (empty when unknown). */
    private suspend fun expiringFoods(): Map<Long, List<ExpiringFood>> =
        makeNow.observe(MakeNowFilter(), today())
            .catch { emit(MakeNowState()) }
            .first()
            .groups.values.flatten()
            .filter { it.expiring.isNotEmpty() }
            .associate { it.recipe.id to it.expiring }

    private fun swapSuggestion(date: LocalDate) {
        transient.update { state ->
            val suggestions = state.suggestions ?: return@update state
            val swapped = WeekSuggestions.swap(suggestions, date, uiState.value.days, state.candidates, clock.now(), state.expiring)
            state.copy(suggestions = swapped)
        }
    }

    private fun removeSuggestion(date: LocalDate) {
        transient.update { state ->
            state.copy(suggestions = state.suggestions?.let { it.copy(meals = it.meals.filterNot { meal -> meal.date == date }) })
        }
    }

    /** Plans every suggestion left, with servings like the week's other meals; "Ångra" removes them again. */
    private fun acceptSuggestions() {
        val suggestions = transient.value.suggestions ?: return
        if (!canWrite() || suggestions.saving || suggestions.meals.isEmpty()) return
        transient.update { it.copy(suggestions = suggestions.copy(saving = true)) }
        val servings = usualServings(suggestions.mealType?.id)
        viewModelScope.launch {
            val created = mutableListOf<Long>()
            var failure: Outcome.Failure? = null
            for (meal in suggestions.meals) {
                val draft = MealPlanDraft(date = meal.date, mealTypeId = meal.mealType.id, recipeId = meal.recipe.id, servings = servings)
                when (val result = mealPlans.create(draft)) {
                    is Outcome.Success -> created += result.value

                    is Outcome.Failure -> {
                        failure = result
                        break
                    }
                }
            }
            transient.update { it.copy(suggestions = null, candidates = emptyList(), expiring = emptyMap()) }
            if (created.isNotEmpty()) {
                undo = { removeAll(created) }
                effectChannel.send(WeekEffect.Show(WeekMessage.Suggested(created.size)))
            }
            failure?.let { effectChannel.send(WeekEffect.Failed(it.error)) }
        }
    }

    private suspend fun removeAll(ids: List<Long>): Outcome<*> {
        var last: Outcome<*> = Outcome.Success(Unit)
        for (id in ids) {
            val result = mealPlans.delete(id)
            if (result is Outcome.Failure) return result
            last = result
        }
        return last
    }

    /** The servings most of the week's meals of [mealTypeId] have, else [DEFAULT_SERVINGS]. */
    private fun usualServings(mealTypeId: Long?): Double {
        val servings = uiState.value.days.flatMap { it.meals }.filter { it.mealType.id == mealTypeId }.map { it.servings }
        return servings.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: DEFAULT_SERVINGS
    }

    private fun resolveConflict(keepMine: Boolean) {
        val conflict = uiState.value.conflict?.conflict ?: return
        viewModelScope.launch {
            mealPlans.resolveConflict(conflict, keepMine).errorOrNull()?.let { effectChannel.send(WeekEffect.Failed(it)) }
        }
    }

    private fun write(message: WeekMessage, undo: suspend () -> Outcome<*>, change: suspend () -> Outcome<*>) {
        viewModelScope.launch {
            when (val result = change()) {
                is Outcome.Success -> {
                    this@WeekViewModel.undo = undo
                    effectChannel.send(WeekEffect.Show(message))
                }

                is Outcome.Failure -> effectChannel.send(WeekEffect.Failed(result.error))
            }
        }
    }

    private fun takeBack() {
        val action = undo ?: return
        undo = null
        viewModelScope.launch { action().errorOrNull()?.let { effectChannel.send(WeekEffect.Failed(it)) } }
    }

    private data class Transient(
        val refreshing: Boolean = false,
        val move: MoveMealState? = null,
        val deleting: DeleteMealState? = null,
        val suggestions: WeekSuggestionsState? = null,
        /** The recipes the open suggestion sheet draws from (read once per sheet). */
        val candidates: List<RecipeOverview> = emptyList(),
        val suggestionSeed: Int = 0,
        /** fas4-kvar: the soon-expiring foods per recipe of the open sheet (read once per sheet). */
        val expiring: Map<Long, List<ExpiringFood>> = emptyMap(),
        /** fas4-kvar: the chosen meal type and keywords (kept while the week is open). */
        val suggestMealTypeId: Long? = null,
        val suggestKeywordIds: Set<Long> = emptySet(),
    )

    private companion object {
        const val KEY_WEEK = "week.start"
        const val KEY_DAY = "week.day"
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val DEFAULT_SERVINGS = 2.0
    }
}

/** The values to plan [this] again ("Ångra" after removing it). */
internal fun MealPlanEntry.toDraft(): MealPlanDraft = MealPlanDraft(
    date = fromDate,
    mealTypeId = mealType.id,
    recipeId = recipe?.id,
    title = title,
    servings = servings,
    note = note,
    toDate = toDate.takeIf { it != fromDate },
)
