package casa.moller.tandem.feature.pantry.makenow

import androidx.compose.runtime.getValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import casa.moller.tandem.core.data.mealplan.MealPlanRepository
import casa.moller.tandem.core.data.pantry.MakeNowRepository
import casa.moller.tandem.core.data.pantry.PantryRepository
import casa.moller.tandem.core.data.pantry.StaplesRepository
import casa.moller.tandem.core.data.permissions.SpacePermissionsRepository
import casa.moller.tandem.core.data.recipe.RecipeRepository
import casa.moller.tandem.core.data.time.CalendarDays
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.makenow.ExpiringFood
import casa.moller.tandem.core.model.makenow.MakeNowFilter
import casa.moller.tandem.core.model.makenow.MakeNowGroup
import casa.moller.tandem.core.model.makenow.MakeNowRecipe
import casa.moller.tandem.core.model.makenow.MakeNowState
import casa.moller.tandem.core.model.makenow.MissingFood
import casa.moller.tandem.core.model.mealplan.MealType
import casa.moller.tandem.core.model.pantry.InventoryEntry
import casa.moller.tandem.core.model.recipe.Keyword
import casa.moller.tandem.core.model.recipe.RecipeOverview
import casa.moller.tandem.feature.pantry.isDrink
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

/**
 * One recipe of "Kan laga nu".
 *
 * @property missing the foods it lacks, null until they were worked out (when the row is shown).
 * @property expiring soon-expiring foods of the pantry it uses ("Använd upp", #28).
 */
data class MakeNowRow(
    val recipe: RecipeOverview,
    val group: MakeNowGroup,
    val missing: List<MissingFood>?,
    val expiring: List<ExpiringFood>,
) {
    val usesUp: Boolean
        get() = expiring.isNotEmpty()
}

/** The time filter of "Kan laga nu" (plan §8.8 "tid"). */
enum class TimeFilter(val maxMinutes: Int?) {
    ANY(null),
    QUICK(30),
    HOUR(60),
}

/**
 * "Kan laga nu" (#23) with "Använd upp" (#28), plan §8.8.
 *
 * @property groups Har allt / Saknar 1 / Saknar 2 (the server's `makenow`, drinks left out: a
 *   recipe without ingredients always "has all"); recipes using soon-expiring foods first.
 * @property selected the group shown on a phone (tabs); wide windows show them side by side.
 * @property expiring the pantry's entries within three days of their day, for the "Använd upp" line.
 * @property stale the last refresh failed: what is shown was fetched earlier (offline).
 * @property staplesPrompt the staples were never offered on this device ("annars saknas nästan
 *   alltid salt och olja").
 */
data class MakeNowUiState(
    val loading: Boolean = true,
    val readOnly: Boolean = false,
    val today: LocalDate? = null,
    val groups: Map<MakeNowGroup, List<MakeNowRow>> = emptyMap(),
    val selected: MakeNowGroup = MakeNowGroup.HAS_ALL,
    val filter: MakeNowFilter = MakeNowFilter(),
    val mealTypes: List<MealType> = emptyList(),
    val keywords: List<Keyword> = emptyList(),
    val expiring: List<InventoryEntry> = emptyList(),
    val refreshing: Boolean = false,
    val stale: Boolean = false,
    val staplesPrompt: Boolean = false,
    val keywordPickerOpen: Boolean = false,
    val surprising: Boolean = false,
) {
    fun rows(group: MakeNowGroup): List<MakeNowRow> = groups[group].orEmpty()

    val isEmpty: Boolean
        get() = MakeNowGroup.entries.all { rows(it).isEmpty() }

    val time: TimeFilter
        get() = TimeFilter.entries.firstOrNull { it.maxMinutes == filter.maxMinutes } ?: TimeFilter.ANY

    val filtered: Boolean
        get() = filter != MakeNowFilter()
}

/** What "Kan laga nu" can ask for. */
sealed interface MakeNowAction {
    data object Refresh : MakeNowAction

    data class SelectGroup(val group: MakeNowGroup) : MakeNowAction

    data class SetMealType(val mealTypeId: Long?) : MakeNowAction

    data class SetTime(val time: TimeFilter) : MakeNowAction

    data class ToggleKeyword(val keywordId: Long) : MakeNowAction

    data object OpenKeywords : MakeNowAction

    data object CloseKeywords : MakeNowAction

    data object ClearFilters : MakeNowAction

    /** "Överraska mig": a random recipe of the group shown ([group] on wide windows). */
    data class Surprise(val group: MakeNowGroup) : MakeNowAction

    /** A row came on screen: its missing foods are worked out (a few at a time). */
    data class RowShown(val recipeId: Long) : MakeNowAction

    /** "Lägg det som saknas på listan": the shopping sheet with only the missing foods ticked. */
    data class AddMissingToList(val recipeId: Long) : MakeNowAction

    /** "Inte nu" on the staples prompt. */
    data object DismissStaples : MakeNowAction

    // Navigation (handled by the route).

    data class OpenRecipe(val recipeId: Long) : MakeNowAction

    data class StartCooking(val recipeId: Long) : MakeNowAction

    data object OpenStaples : MakeNowAction
}

/** One-off effects of "Kan laga nu". */
sealed interface MakeNowEffect {
    /** "Överraska mig" picked [recipeId]. */
    data class OpenRecipe(val recipeId: Long) : MakeNowEffect

    /** Opens the shopping sheet for [recipeId] with exactly [foodIds] ticked. */
    data class AddToList(val recipeId: Long, val foodIds: Set<Long>) : MakeNowEffect

    /** The group had nothing to surprise with. */
    data object NothingToSurprise : MakeNowEffect

    data class Failed(val error: TandemError) : MakeNowEffect
}

/**
 * See [MakeNowUiState]. The groups and the filter come from [MakeNowRepository] (three `makenow`
 * requests on the server); the missing foods of a row are worked out when it is shown, one at a
 * time ([MakeNowRepository.missingFoods], rate limited there). Opened from "Går ut snart"
 * ([useUpFirst]) it starts on the first group with a recipe that uses a soon-expiring food.
 * Read-only, so a guest sees it too (without "Lägg på listan").
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = MakeNowViewModel.Factory::class)
class MakeNowViewModel @AssistedInject internal constructor(
    @Assisted private val useUpFirst: Boolean,
    private val savedStateHandle: SavedStateHandle,
    private val makeNow: MakeNowRepository,
    private val staples: StaplesRepository,
    pantry: PantryRepository,
    recipes: RecipeRepository,
    mealPlan: MealPlanRepository,
    permissions: SpacePermissionsRepository,
    calendar: CalendarDays,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(useUpFirst: Boolean): MakeNowViewModel
    }

    private val effectChannel = Channel<MakeNowEffect>(Channel.BUFFERED)

    /** One-off effects; collect them once. */
    val effects: Flow<MakeNowEffect> = effectChannel.receiveAsFlow()

    private val missing = MutableStateFlow<Map<Long, List<MissingFood>>>(emptyMap())
    private val requested = mutableSetOf<Long>()
    private val queue = Channel<Long>(Channel.UNLIMITED)
    private val transient = MutableStateFlow(Transient())

    private val filter: Flow<MakeNowFilter> = combine(
        savedStateHandle.getStateFlow(KEY_MEAL_TYPE, NONE),
        savedStateHandle.getStateFlow(KEY_TIME, TimeFilter.ANY.name),
        savedStateHandle.getStateFlow(KEY_KEYWORDS, LongArray(0)),
    ) { mealType, time, keywords ->
        MakeNowFilter(
            keywordIds = keywords.toSet(),
            maxMinutes = TimeFilter.entries.firstOrNull { it.name == time }?.maxMinutes,
            mealTypeId = mealType.takeIf { it != NONE },
        )
    }.distinctUntilChanged()

    private val today: Flow<LocalDate> = calendar.observeToday()

    private val groups: Flow<Pair<LocalDate, MakeNowState>> = combine(filter, today) { f, day -> f to day }
        .flatMapLatest { (f, day) -> makeNow.observe(f, day).map { day to it } }

    private val reference: Flow<Reference> = combine(
        mealPlan.observeMealTypes(),
        recipes.observeKeywords(),
        today.flatMapLatest { day -> pantry.observeExpiring(day) },
        staples.observeSuggestionsShown(),
        permissions.permissions,
    ) { types, keywords, expiring, shown, perms -> Reference(types, keywords, expiring, shown, perms.readOnly) }

    val uiState: StateFlow<MakeNowUiState> = combine(
        groups,
        filter,
        reference,
        combine(missing, transient, savedStateHandle.getStateFlow(KEY_GROUP, "")) { found, local, group -> Triple(found, local, group) },
    ) { (day, state), filter, ref, (found, local, group) ->
        build(day, state, filter, ref, found, local, group)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), MakeNowUiState())

    init {
        viewModelScope.launch { makeNow.refresh() }
        viewModelScope.launch {
            for (id in queue) {
                val result = makeNow.missingFoods(id)
                if (result is Outcome.Success) missing.update { it + (id to result.value) }
            }
        }
    }

    fun onAction(action: MakeNowAction) {
        when (action) {
            MakeNowAction.Refresh -> refresh()
            is MakeNowAction.SelectGroup -> savedStateHandle[KEY_GROUP] = action.group.name
            is MakeNowAction.SetMealType -> savedStateHandle[KEY_MEAL_TYPE] = action.mealTypeId ?: NONE
            is MakeNowAction.SetTime -> savedStateHandle[KEY_TIME] = action.time.name
            is MakeNowAction.ToggleKeyword -> toggleKeyword(action.keywordId)
            MakeNowAction.OpenKeywords -> transient.update { it.copy(keywordPicker = true) }
            MakeNowAction.CloseKeywords -> transient.update { it.copy(keywordPicker = false) }
            MakeNowAction.ClearFilters -> clearFilters()
            is MakeNowAction.Surprise -> surprise(action.group)
            is MakeNowAction.RowShown -> request(action.recipeId)
            is MakeNowAction.AddMissingToList -> addMissing(action.recipeId)
            MakeNowAction.DismissStaples -> viewModelScope.launch { staples.markSuggestionsShown() }
            // Navigation is the route's.
            else -> Unit
        }
    }

    private fun build(
        day: LocalDate,
        state: MakeNowState,
        filter: MakeNowFilter,
        ref: Reference,
        found: Map<Long, List<MissingFood>>,
        local: Transient,
        savedGroup: String,
    ): MakeNowUiState {
        val groups = MakeNowGroup.entries.associateWith { group ->
            state.recipes(group)
                .filterNot { it.recipe.isDrink() }
                .map { it.toRow(found) }
                // "Använd upp" first (the repository orders them; kept here for any source): soonest day first.
                .sortedWith(compareBy<MakeNowRow> { !it.usesUp }.thenBy { row -> row.expiring.minOfOrNull { it.expires } })
        }
        val selected = MakeNowGroup.entries.firstOrNull { it.name == savedGroup } ?: initialGroup(groups)
        return MakeNowUiState(
            loading = false,
            readOnly = ref.readOnly,
            today = day,
            groups = groups,
            selected = selected,
            filter = filter,
            mealTypes = ref.mealTypes,
            keywords = ref.keywords,
            expiring = ref.expiring,
            refreshing = local.refreshing || state.refreshing,
            stale = state.stale,
            staplesPrompt = !ref.staplesShown && !ref.readOnly,
            keywordPickerOpen = local.keywordPicker,
            surprising = local.surprising,
        )
    }

    /** From "Går ut snart" the first group with a recipe that uses one up; otherwise the first group with recipes. */
    private fun initialGroup(groups: Map<MakeNowGroup, List<MakeNowRow>>): MakeNowGroup {
        val usingUp = if (useUpFirst) MakeNowGroup.entries.firstOrNull { group -> groups[group].orEmpty().any { it.usesUp } } else null
        return usingUp ?: MakeNowGroup.entries.firstOrNull { groups[it].orEmpty().isNotEmpty() } ?: MakeNowGroup.HAS_ALL
    }

    private fun MakeNowRecipe.toRow(found: Map<Long, List<MissingFood>>): MakeNowRow {
        val known = missingFoods ?: found[recipe.id] ?: if (group == MakeNowGroup.HAS_ALL) emptyList() else null
        return MakeNowRow(recipe = recipe, group = group, missing = known, expiring = expiring)
    }

    private fun refresh() {
        if (transient.value.refreshing) return
        transient.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            val result = makeNow.refresh(force = true)
            transient.update { it.copy(refreshing = false) }
            if (result is Outcome.Failure) effectChannel.send(MakeNowEffect.Failed(result.error))
        }
    }

    private fun toggleKeyword(id: Long) {
        val current = savedStateHandle.get<LongArray>(KEY_KEYWORDS)?.toSet().orEmpty()
        savedStateHandle[KEY_KEYWORDS] = (if (id in current) current - id else current + id).toLongArray()
    }

    private fun clearFilters() {
        savedStateHandle[KEY_MEAL_TYPE] = NONE
        savedStateHandle[KEY_TIME] = TimeFilter.ANY.name
        savedStateHandle[KEY_KEYWORDS] = LongArray(0)
    }

    private fun request(recipeId: Long) {
        val row = uiState.value.groups.values.flatten().firstOrNull { it.recipe.id == recipeId } ?: return
        if (row.missing != null || !requested.add(recipeId)) return
        queue.trySend(recipeId)
    }

    private fun surprise(group: MakeNowGroup) {
        if (transient.value.surprising) return
        transient.update { it.copy(surprising = true) }
        viewModelScope.launch {
            val result = makeNow.surprise(group, uiState.value.filter)
            transient.update { it.copy(surprising = false) }
            when (result) {
                is Outcome.Success -> {
                    val pick = result.value?.takeUnless { it.recipe.isDrink() }
                    effectChannel.send(if (pick != null) MakeNowEffect.OpenRecipe(pick.recipe.id) else MakeNowEffect.NothingToSurprise)
                }

                is Outcome.Failure -> effectChannel.send(MakeNowEffect.Failed(result.error))
            }
        }
    }

    private fun addMissing(recipeId: Long) {
        if (uiState.value.readOnly) return
        val known = uiState.value.groups.values.flatten().firstOrNull { it.recipe.id == recipeId }?.missing
        viewModelScope.launch {
            val foods = known ?: when (val result = makeNow.missingFoods(recipeId)) {
                is Outcome.Success -> result.value.also { value -> missing.update { it + (recipeId to value) } }
                is Outcome.Failure -> {
                    effectChannel.send(MakeNowEffect.Failed(result.error))
                    return@launch
                }
            }
            if (foods.isNotEmpty()) effectChannel.send(MakeNowEffect.AddToList(recipeId, foods.mapTo(LinkedHashSet()) { it.id }))
        }
    }

    private data class Reference(
        val mealTypes: List<MealType>,
        val keywords: List<Keyword>,
        val expiring: List<InventoryEntry>,
        val staplesShown: Boolean,
        val readOnly: Boolean,
    )

    private data class Transient(val refreshing: Boolean = false, val keywordPicker: Boolean = false, val surprising: Boolean = false)

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val NONE = Long.MIN_VALUE
        const val KEY_GROUP = "make-now-group"
        const val KEY_MEAL_TYPE = "make-now-meal-type"
        const val KEY_TIME = "make-now-time"
        const val KEY_KEYWORDS = "make-now-keywords"
    }
}
