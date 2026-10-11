package casa.moller.tandem.feature.recipes.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import casa.moller.tandem.core.data.preferences.PreferencesRepository
import casa.moller.tandem.core.data.recipe.RecipeRepository
import casa.moller.tandem.core.data.session.Session
import casa.moller.tandem.core.data.session.SessionRepository
import casa.moller.tandem.core.data.sync.SyncRepository
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.preferences.UserPreferences
import casa.moller.tandem.core.model.recipe.Recipe
import casa.moller.tandem.core.model.recipe.RecipeDetailState
import casa.moller.tandem.core.model.recipe.ServingsChoice
import casa.moller.tandem.core.model.sync.SyncPause
import casa.moller.tandem.feature.recipes.R
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlin.math.ceil
import kotlin.math.floor
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The recipe detail (plan 6.2 "Receptdetalj", feature #4): stored copy first, quiet refresh,
 * servings remembered per recipe, amounts scaled and formatted after the user's Tandoor
 * preferences, rows struck through and units switched for this session only.
 *
 * The recipe counts as viewed once per ViewModel, i.e. once per opening (plan 3.3 view log).
 */
@HiltViewModel(assistedFactory = RecipeDetailViewModel.Factory::class)
class RecipeDetailViewModel @AssistedInject constructor(
    @Assisted private val recipeId: Long,
    private val savedStateHandle: SavedStateHandle,
    private val recipes: RecipeRepository,
    preferencesRepository: PreferencesRepository,
    syncRepository: SyncRepository,
    sessionRepository: SessionRepository,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(recipeId: Long): RecipeDetailViewModel
    }

    private val effectChannel = Channel<RecipeDetailEffect>(Channel.BUFFERED)

    /** One-off messages for a snackbar. */
    val effects: Flow<RecipeDetailEffect> = effectChannel.receiveAsFlow()

    private val manualRefresh = MutableStateFlow(false)

    private val sessionInfo: Flow<SessionInfo> = sessionRepository.session
        .map { session ->
            SessionInfo(
                isDemo = session is Session.Demo,
                baseUrl = (session as? Session.LoggedIn)?.account?.baseUrl,
            )
        }
        .onStart { emit(SessionInfo()) }
        .distinctUntilChanged()

    private val sessionChoices: Flow<SessionChoices> = combine(
        savedStateHandle.getStateFlow(KEY_STRUCK, LongArray(0)),
        savedStateHandle.getStateFlow(KEY_UNITS, LongArray(0)),
    ) { struck, units -> SessionChoices(struck.toSet(), units.toUnitChoices()) }

    private val sources: Flow<Sources> = combine(
        recipes.observeRecipe(recipeId),
        recipes.observeServingsChoice(recipeId).onStart { emit(null) },
        preferencesRepository.observeUserPreferences().onStart { emit(UserPreferences.Default) },
        syncRepository.status.map { it.pause == SyncPause.OFFLINE }.distinctUntilChanged(),
        sessionInfo,
    ) { detail, choice, preferences, offline, session -> Sources(detail, choice, preferences, offline, session) }

    val uiState: StateFlow<RecipeDetailUiState> = combine(sources, sessionChoices, manualRefresh) { current, choices, refreshing ->
        buildState(current, choices, refreshing)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), RecipeDetailUiState(recipeId = recipeId))

    init {
        viewModelScope.launch { recipes.markViewed(recipeId) }
    }

    fun onAction(action: RecipeDetailAction) {
        when (action) {
            RecipeDetailAction.IncreaseServings -> changeServings { floor(it) + 1.0 }

            RecipeDetailAction.DecreaseServings -> changeServings { (ceil(it) - 1.0).coerceAtLeast(ServingsState.MIN_SERVINGS) }

            RecipeDetailAction.ResetServings -> viewModelScope.launch { recipes.setServingsChoice(recipeId, null) }

            is RecipeDetailAction.ToggleIngredient -> toggleIngredient(action.id)

            is RecipeDetailAction.SwitchUnit -> switchUnit(action.id)

            RecipeDetailAction.Refresh -> refresh()

            RecipeDetailAction.ToggleOffline -> toggleOffline()
        }
    }

    private fun changeServings(next: (Double) -> Double) {
        val servings = uiState.value.servings ?: return
        val target = next(servings.current).coerceAtLeast(ServingsState.MIN_SERVINGS)
        viewModelScope.launch {
            // The recipe's own servings are not stored as a choice, so a later server change wins.
            recipes.setServingsChoice(recipeId, target.takeUnless { it == servings.original.toDouble() })
        }
    }

    private fun toggleIngredient(id: Long) {
        val struck = savedStateHandle.get<LongArray>(KEY_STRUCK)?.toSet().orEmpty()
        savedStateHandle[KEY_STRUCK] = (if (id in struck) struck - id else struck + id).toLongArray()
    }

    private fun switchUnit(id: Long) {
        val state = uiState.value
        val item = (state.ingredients + state.steps.flatMap { it.ingredients }).firstOrNull { it.id == id } ?: return
        if (!item.canSwitchUnit) return
        val choices = savedStateHandle.get<LongArray>(KEY_UNITS)?.toUnitChoices().orEmpty()
        val next = ((choices[id] ?: 0) + 1) % item.units.size
        savedStateHandle[KEY_UNITS] = (choices + (id to next)).toLongArray()
    }

    private fun refresh() {
        if (manualRefresh.value) return
        manualRefresh.value = true
        viewModelScope.launch {
            val result = recipes.refreshRecipe(recipeId)
            manualRefresh.value = false
            // Without a stored detail the screen shows the error state itself.
            if (result is Outcome.Failure && uiState.value.hasDetail) {
                effectChannel.trySend(RecipeDetailEffect.Message(refreshFailedMessage(result.error)))
            }
        }
    }

    private fun toggleOffline() {
        val state = uiState.value
        if (!state.canPin) return
        val pin = !state.pinned
        viewModelScope.launch {
            val message = when (recipes.setAvailableOffline(recipeId, pin)) {
                is Outcome.Failure -> R.string.recipe_detail_offline_failed

                is Outcome.Success -> when {
                    !pin -> R.string.recipe_detail_offline_removed
                    state.offline -> R.string.recipe_detail_offline_queued
                    else -> R.string.recipe_detail_offline_saved
                }
            }
            effectChannel.trySend(RecipeDetailEffect.Message(message))
        }
    }

    private fun buildState(sources: Sources, choices: SessionChoices, manualRefreshing: Boolean): RecipeDetailUiState {
        val detail = sources.detail
        val recipe = detail.recipe
        val overview = detail.overview
        val servings = recipe?.let {
            ServingsState(
                current = sources.choice?.servings ?: it.servings.toDouble(),
                original = it.servings.coerceAtLeast(1),
                text = it.servingsText,
            )
        }
        val factor = servings?.factor ?: 1.0
        val offline = sources.offline || detail.error.isConnectionProblem()
        return RecipeDetailUiState(
            recipeId = recipeId,
            loading = false,
            header = recipe?.toHeader(detail) ?: overview?.let { o ->
                RecipeHeader(
                    name = o.name,
                    description = o.description,
                    imageUrl = o.imageUrl,
                    workingMinutes = o.workingMinutes,
                    waitingMinutes = o.waitingMinutes,
                    rating = o.rating,
                    keywords = o.keywords,
                    timesCooked = o.timesCooked,
                    lastCooked = o.lastCooked,
                    isPrivate = o.isPrivate,
                )
            },
            hasDetail = recipe != null,
            servings = servings,
            ingredients = recipe?.takeIf { it.showIngredientOverview }?.ingredients?.map { ingredientItem(it, factor) }.orEmpty(),
            steps = recipe?.toSteps(factor, sources.preferences).orEmpty(),
            sourceUrl = recipe?.sourceUrl?.takeIf { it.startsWith("https://") || it.startsWith("http://") },
            preferences = sources.preferences,
            refreshing = detail.refreshing || manualRefreshing,
            error = detail.error,
            offline = offline,
            fetchedAt = detail.fetchedAt,
            availableOffline = overview?.availableOffline == true || recipe != null,
            pinned = overview?.pinned == true,
            canPin = !sources.session.isDemo && (overview != null || recipe != null),
            webUrl = sources.session.baseUrl?.let { "${it.trimEnd('/')}/recipe/$recipeId/" },
            struckIngredientIds = choices.struck,
            unitChoices = choices.units,
        )
    }

    private fun Recipe.toHeader(detail: RecipeDetailState): RecipeHeader = RecipeHeader(
        name = name,
        description = description,
        imageUrl = imageUrl ?: detail.overview?.imageUrl,
        workingMinutes = workingMinutes,
        waitingMinutes = waitingMinutes,
        rating = rating,
        keywords = keywords,
        timesCooked = detail.overview?.timesCooked,
        lastCooked = lastCooked ?: detail.overview?.lastCooked,
        isPrivate = isPrivate,
    )

    private fun Recipe.toSteps(factor: Double, preferences: UserPreferences): List<StepItem> {
        return steps.sortedBy { it.order }.mapIndexed { index, step ->
            StepItem(
                id = step.id,
                number = index + 1,
                name = step.name,
                instruction = step.instruction,
                minutes = step.timeMinutes,
                ingredients = step.ingredients.sortedBy { it.order }.map { ingredientItem(it, factor) },
                // Without an overview the steps are the only place the ingredients appear.
                showIngredients = step.ingredients.isNotEmpty() &&
                    (!showIngredientOverview || step.showIngredientsTable && preferences.showStepIngredients),
                stepRecipe = step.stepRecipe,
            )
        }
    }

    private data class SessionInfo(val isDemo: Boolean = false, val baseUrl: String? = null)

    private data class SessionChoices(val struck: Set<Long>, val units: Map<Long, Int>)

    private data class Sources(
        val detail: RecipeDetailState,
        val choice: ServingsChoice?,
        val preferences: UserPreferences,
        val offline: Boolean,
        val session: SessionInfo,
    )

    internal companion object {
        const val KEY_STRUCK = "struck_ingredients"
        const val KEY_UNITS = "unit_choices"
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        /** Ingredient id / unit index pairs, flattened (a Bundle-friendly LongArray). */
        fun LongArray.toUnitChoices(): Map<Long, Int> = (0 until size / 2).associate { this[it * 2] to this[it * 2 + 1].toInt() }

        fun Map<Long, Int>.toLongArray(): LongArray = flatMap { (id, index) -> listOf(id, index.toLong()) }.toLongArray()

        fun refreshFailedMessage(error: TandemError): Int =
            if (error.isConnectionProblem()) R.string.recipe_detail_refresh_failed_offline else R.string.recipe_detail_refresh_failed
    }
}

/** No connection to the server (as opposed to the server refusing). */
internal fun TandemError?.isConnectionProblem(): Boolean =
    this == TandemError.NetworkUnavailable || this == TandemError.UnknownHost || this == TandemError.Timeout
