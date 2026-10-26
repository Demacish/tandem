package casa.moller.tandem.feature.mealplan

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import casa.moller.tandem.core.data.mealplan.MealPlanRepository
import casa.moller.tandem.core.data.permissions.SpacePermissionsRepository
import casa.moller.tandem.core.data.shopping.SupermarketRepository
import casa.moller.tandem.core.domain.mealplan.WeekShoppingMerge
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.mealplan.MealPlanWeek
import casa.moller.tandem.core.model.mealplan.WeekShoppingLine
import casa.moller.tandem.core.model.mealplan.WeekShoppingMeal
import casa.moller.tandem.core.model.mealplan.WeekShoppingPreview
import casa.moller.tandem.core.model.shopping.SupermarketCategory
import casa.moller.tandem.feature.mealplan.navigation.WeekShoppingKey
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import java.text.Collator
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

/**
 * One planned meal of "Handla för veckan" (Stitch handla_f_r_veckan: "Inkluderade middagar").
 *
 * @property included its ingredients count in the merged list (the user can leave a meal out).
 */
data class WeekShoppingMealState(val meal: WeekShoppingMeal, val included: Boolean) {
    /** Not on the list yet and with stored ingredients: it can be included. */
    val canSelect: Boolean
        get() = WeekShoppingMerge.selectable(meal)
}

/** One merged line ("5 dl vispgrädde") and whether it goes on the list. */
data class WeekShoppingLineState(val line: WeekShoppingLine, val chosen: Boolean)

/**
 * The lines of one aisle (the food's `supermarket_category`), by name; [name] null = "Övrigt".
 */
data class WeekShoppingAisle(val key: String, val name: String?, val lines: List<WeekShoppingLineState>)

/**
 * "Handla för veckan" (feature #16, plan 6.2 steps 1-4, 8.3; Stitch handla_f_r_veckan).
 *
 * @property from the first day of the chosen range; [to] the last (both included).
 * @property meals the planned meals with recipes in the range, in day order.
 * @property aisles the merged ingredients still to buy, by aisle.
 * @property atHome the merged ingredients marked "har hemma" (unticked at first, plan 6.2 step 4).
 * @property autoAdd the user's `mealplan_autoadd_shopping` (explained: meals already on the list
 *   are never added again).
 */
data class WeekShoppingUiState(
    val loading: Boolean = true,
    val from: LocalDate = LocalDate(1970, 1, 5),
    val to: LocalDate = LocalDate(1970, 1, 11),
    val meals: List<WeekShoppingMealState> = emptyList(),
    val aisles: List<WeekShoppingAisle> = emptyList(),
    val atHome: List<WeekShoppingLineState> = emptyList(),
    val autoAdd: Boolean = false,
    val readOnly: Boolean = false,
    val adding: Boolean = false,
) {
    private val lines: List<WeekShoppingLineState>
        get() = aisles.flatMap { it.lines } + atHome

    /** Lines that go on the list ("Lägg till 12 varor"). */
    val chosenCount: Int
        get() = lines.count { it.chosen }

    /** Every merged line ("Alla råvaror (28)"). */
    val lineCount: Int
        get() = lines.size

    /** Lines not on hand ("Bara det som saknas (12)"). */
    val missingCount: Int
        get() = aisles.sumOf { it.lines.size }

    /** Exactly what is missing is ticked. */
    val onlyMissing: Boolean
        get() = aisles.all { aisle -> aisle.lines.all { it.chosen } } && atHome.none { it.chosen }

    /** Every line is ticked. */
    val allChosen: Boolean
        get() = lines.all { it.chosen }

    /** Meals that will get rows on the list. */
    val mealsToAdd: Int
        get() {
            val chosen = lines.filter { it.chosen }.flatMap { line -> line.line.parts.map { it.mealId } }.toSet()
            return meals.count { it.included && it.canSelect && it.meal.meal.id in chosen }
        }

    /** Meals in the range already on the list ("redan på listan"). */
    val alreadyOnListCount: Int
        get() = meals.count { it.meal.alreadyOnList }

    val canAdd: Boolean
        get() = !readOnly && !adding && chosenCount > 0
}

sealed interface WeekShoppingAction {
    /** Includes or leaves out a meal (never one already on the list). */
    data class ToggleMeal(val mealId: Long) : WeekShoppingAction

    /** Ticks or unticks one merged line. */
    data class ToggleLine(val key: String) : WeekShoppingAction

    /** "Bara det som saknas": on-hand lines unticked, the rest ticked. */
    data object OnlyMissing : WeekShoppingAction

    /** "Alla råvaror": every line ticked. */
    data object AllIngredients : WeekShoppingAction

    /** Another range of days (the date range picker). */
    data class SetRange(val from: LocalDate, val to: LocalDate) : WeekShoppingAction

    data object Add : WeekShoppingAction

    data object Retry : WeekShoppingAction

    data object Close : WeekShoppingAction
}

sealed interface WeekShoppingEffect {
    /** [meals] meals with [items] lines are on the list now: show the list. */
    data class Added(val meals: Int, val items: Int = 0) : WeekShoppingEffect

    data class Failed(val error: TandemError) : WeekShoppingEffect
}

/**
 * "Handla för veckan" (feature #16): for a range of days (a week at first), the planned meals'
 * ingredients merged per food and unit with the amounts added (core:domain [WeekShoppingMerge]),
 * by aisle; on-hand foods start unticked; meals already on the list (rows linked to the meal,
 * whether the server added them for `mealplan_autoadd_shopping`/`addshopping` or the app did)
 * are shown as "redan på listan" and never added again. "Lägg till" adds the chosen lines per meal
 * ([MealPlanRepository.addWeekToShopping]: one list recipe linked to each meal).
 */
@HiltViewModel(assistedFactory = WeekShoppingViewModel.Factory::class)
class WeekShoppingViewModel @AssistedInject internal constructor(
    @Assisted key: WeekShoppingKey,
    private val savedStateHandle: SavedStateHandle,
    private val mealPlans: MealPlanRepository,
    permissions: SpacePermissionsRepository,
    supermarkets: SupermarketRepository,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(key: WeekShoppingKey): WeekShoppingViewModel
    }

    private val effectChannel = Channel<WeekShoppingEffect>(Channel.BUFFERED)

    /** One-off effects; collect them once (e.g. in a LaunchedEffect). */
    val effects: Flow<WeekShoppingEffect> = effectChannel.receiveAsFlow()

    private val weekStart = runCatching { LocalDate.parse(key.weekStart) }.getOrElse { LocalDate(1970, 1, 5) }
    private val preview = MutableStateFlow<WeekShoppingPreview?>(null)
    private val adding = MutableStateFlow(false)
    private var loadJob: Job? = null
    private val collator: Collator = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.PRIMARY }

    private val choices: Flow<Choices> = combine(
        savedStateHandle.getStateFlow(KEY_FROM, weekStart.toString()),
        savedStateHandle.getStateFlow(KEY_TO, weekStart.plus(MealPlanWeek.DAYS - 1, DateTimeUnit.DAY).toString()),
        savedStateHandle.getStateFlow(KEY_EXCLUDED, LongArray(0)),
        savedStateHandle.getStateFlow(KEY_TOGGLED, ArrayList<String>()),
    ) { from, to, excluded, toggled -> Choices(LocalDate.parse(from), LocalDate.parse(to), excluded.toSet(), toggled.toSet()) }

    private val context: Flow<Pair<Boolean, Map<Long, SupermarketCategory>>> = combine(
        permissions.permissions.map { it.readOnly },
        supermarkets.observeCategories().map { list -> list.associateBy { it.id } }.onStart { emit(emptyMap()) },
    ) { readOnly, categories -> readOnly to categories }

    val uiState: StateFlow<WeekShoppingUiState> = combine(preview, choices, context, adding) { loaded, chosen, permitted, busy ->
        build(loaded, chosen, readOnly = permitted.first, categories = permitted.second, busy = busy)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), WeekShoppingUiState(from = weekStart))

    init {
        load()
    }

    fun onAction(action: WeekShoppingAction) {
        when (action) {
            is WeekShoppingAction.ToggleMeal -> toggleMeal(action.mealId)
            is WeekShoppingAction.ToggleLine -> toggled { if (action.key in it) it - action.key else it + action.key }
            WeekShoppingAction.OnlyMissing -> toggled { emptySet() }
            WeekShoppingAction.AllIngredients -> toggled { uiState.value.atHome.mapTo(HashSet()) { it.line.key } }
            is WeekShoppingAction.SetRange -> setRange(action.from, action.to)
            WeekShoppingAction.Add -> add()
            WeekShoppingAction.Retry -> load()
            WeekShoppingAction.Close -> Unit // The route closes the page.
        }
    }

    private fun load() {
        val from = LocalDate.parse(savedStateHandle[KEY_FROM] ?: weekStart.toString())
        val to = LocalDate.parse(savedStateHandle[KEY_TO] ?: weekStart.plus(MealPlanWeek.DAYS - 1, DateTimeUnit.DAY).toString())
        loadJob?.cancel()
        preview.value = null
        loadJob = viewModelScope.launch {
            when (val result = mealPlans.weekShoppingPreview(from, to)) {
                is Outcome.Success -> preview.value = result.value

                is Outcome.Failure -> {
                    preview.value = WeekShoppingPreview(emptyList())
                    effectChannel.send(WeekShoppingEffect.Failed(result.error))
                }
            }
        }
    }

    private fun setRange(from: LocalDate, to: LocalDate) {
        val first = minOf(from, to)
        val last = maxOf(from, to)
        savedStateHandle[KEY_FROM] = first.toString()
        savedStateHandle[KEY_TO] = last.toString()
        savedStateHandle[KEY_EXCLUDED] = LongArray(0)
        savedStateHandle[KEY_TOGGLED] = ArrayList<String>()
        load()
    }

    private fun toggleMeal(id: Long) {
        val meal = uiState.value.meals.firstOrNull { it.meal.meal.id == id } ?: return
        if (!meal.canSelect) return
        val excluded = savedStateHandle.get<LongArray>(KEY_EXCLUDED)?.toSet().orEmpty()
        savedStateHandle[KEY_EXCLUDED] = (if (id in excluded) excluded - id else excluded + id).toLongArray()
    }

    private fun toggled(change: (Set<String>) -> Set<String>) {
        val current = savedStateHandle.get<ArrayList<String>>(KEY_TOGGLED)?.toSet().orEmpty()
        savedStateHandle[KEY_TOGGLED] = ArrayList(change(current))
    }

    private fun add() {
        val state = uiState.value
        val loaded = preview.value ?: return
        if (state.loading || !state.canAdd) return
        val included = state.meals.filter { it.included }.mapTo(HashSet()) { it.meal.meal.id }
        val chosen = (state.aisles.flatMap { it.lines } + state.atHome).filter { it.chosen }.mapTo(HashSet()) { it.line.key }
        val selection = WeekShoppingMerge.selection(loaded.meals, included, chosen)
        if (selection.ingredientIdsByMeal.isEmpty()) return
        adding.value = true
        viewModelScope.launch {
            val result = mealPlans.addWeekToShopping(selection)
            adding.value = false
            when (result) {
                is Outcome.Success -> effectChannel.send(WeekShoppingEffect.Added(selection.ingredientIdsByMeal.size, chosen.size))
                is Outcome.Failure -> effectChannel.send(WeekShoppingEffect.Failed(result.error))
            }
        }
    }

    private fun build(
        preview: WeekShoppingPreview?,
        choices: Choices,
        readOnly: Boolean,
        categories: Map<Long, SupermarketCategory>,
        busy: Boolean,
    ): WeekShoppingUiState {
        if (preview == null) return WeekShoppingUiState(loading = true, from = choices.from, to = choices.to, readOnly = readOnly)
        val meals = preview.meals.map { meal ->
            WeekShoppingMealState(meal, included = WeekShoppingMerge.selectable(meal) && meal.meal.id !in choices.excluded)
        }
        val included = meals.filter { it.included }.mapTo(HashSet()) { it.meal.meal.id }
        val lines = WeekShoppingMerge.lines(preview.meals, included)
            .sortedWith(compareBy(collator) { it.foodName })
            .map { line -> WeekShoppingLineState(line, chosen = line.onHand == (line.key in choices.toggled)) }
        val aisles = lines.filterNot { it.line.onHand }
            .groupBy { it.line.categoryId?.let(categories::get) }
            .map { (category, list) ->
                WeekShoppingAisle(key = category?.id?.toString() ?: OTHER_AISLE, name = category?.name, lines = list)
            }
            .sortedWith(compareBy<WeekShoppingAisle> { it.name == null }.thenBy(collator) { it.name.orEmpty() })
        return WeekShoppingUiState(
            loading = false,
            from = choices.from,
            to = choices.to,
            meals = meals,
            aisles = aisles,
            atHome = lines.filter { it.line.onHand },
            autoAdd = preview.autoAddOnServer,
            readOnly = readOnly,
            adding = busy,
        )
    }

    private data class Choices(val from: LocalDate, val to: LocalDate, val excluded: Set<Long>, val toggled: Set<String>)

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val KEY_FROM = "week-shopping-from"
        const val KEY_TO = "week-shopping-to"
        const val KEY_EXCLUDED = "week-shopping-excluded"
        const val KEY_TOGGLED = "week-shopping-toggled"
        const val OTHER_AISLE = "other"
    }
}
