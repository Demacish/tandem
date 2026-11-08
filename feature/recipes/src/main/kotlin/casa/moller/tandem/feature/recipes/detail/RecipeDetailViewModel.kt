package casa.moller.tandem.feature.recipes.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import casa.moller.tandem.core.data.DataProblem
import casa.moller.tandem.core.data.dataProblem
import casa.moller.tandem.core.data.preferences.PreferencesRepository
import casa.moller.tandem.core.data.recipe.PanSizeRepository
import casa.moller.tandem.core.data.recipe.RecipeRepository
import casa.moller.tandem.core.data.session.Session
import casa.moller.tandem.core.data.session.SessionRepository
import casa.moller.tandem.core.data.sync.SyncRepository
import casa.moller.tandem.core.domain.format.NumberStyle
import casa.moller.tandem.core.domain.format.toNumberStyle
import casa.moller.tandem.core.domain.recipe.IngredientRow
import casa.moller.tandem.core.domain.recipe.NutritionEnergy
import casa.moller.tandem.core.domain.recipe.RecipeDisplay
import casa.moller.tandem.core.domain.recipe.RecipeDisplayModel
import casa.moller.tandem.core.domain.recipe.ServingsText
import casa.moller.tandem.core.domain.scaling.PanScaling
import casa.moller.tandem.core.domain.scaling.ServingsScaling
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.preferences.UserPreferences
import casa.moller.tandem.core.model.recipe.Recipe
import casa.moller.tandem.core.model.recipe.RecipeDetailState
import casa.moller.tandem.core.model.recipe.ServingsChoice
import casa.moller.tandem.core.model.sync.SyncPause
import casa.moller.tandem.core.model.sync.isTransientSyncStop
import casa.moller.tandem.feature.recipes.R
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The recipe detail (plan 6.2 "Receptdetalj", feature #4): stored copy first, quiet refresh,
 * servings remembered per recipe, rows struck through and units switched for this session only.
 *
 * Ingredient rows and step texts come from core:domain's [RecipeDisplay.build], the same rules
 * as Tandoor's web (scaling, `use_fractions` / `ingredient_decimals`, plural names, headers,
 * `no_amount`, templates; golden-tested against `scaling-golden.json`), written in the UI's
 * [NumberStyle] (decimal comma in Swedish), which the screen sends with
 * [RecipeDetailAction.SetNumberStyle].
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
    // Fas 5 (kok lane, plan §8.1 "Pannstorlek"): the tin, shared with cook mode.
    private val pans: PanSizeRepository,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(recipeId: Long): RecipeDetailViewModel
    }

    private val effectChannel = Channel<RecipeDetailEffect>(Channel.BUFFERED)

    /** One-off messages for a snackbar. */
    val effects: Flow<RecipeDetailEffect> = effectChannel.receiveAsFlow()

    private val manualRefresh = MutableStateFlow(false)

    /** A share link is on its way: every call makes a new one on the server, so never two (plan 4.1). */
    private val fetchingLink = MutableStateFlow(false)

    /** Until the screen reports its locale: the process default (the app's language at start). */
    private val numberStyle = MutableStateFlow(Locale.getDefault().toNumberStyle())

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
        // Fas 5: the tin shared with cook mode, else this screen's own after a process death.
        combine(pans.observePanSize(recipeId), savedStateHandle.getStateFlow(KEY_PAN, NO_PAN)) { shared, saved ->
            shared ?: saved.takeIf { it != NO_PAN }
        },
    ) { struck, units, pan -> SessionChoices(struck.toSet(), units.toUnitChoices(), pan) }

    // No placeholder values for the servings choice and the preferences: after the screen comes
    // back (stop timeout), the cached state stays until the real values arrive instead of
    // flashing the recipe's default servings and decimals for a frame.
    private val sources: Flow<Sources> = combine(
        recipes.observeRecipe(recipeId),
        recipes.observeServingsChoice(recipeId),
        preferencesRepository.observeUserPreferences(),
        syncRepository.status.map { it.pause == SyncPause.OFFLINE }.distinctUntilChanged(),
        sessionInfo,
    ) { detail, choice, preferences, offline, session -> Sources(detail, choice, preferences, offline, session) }

    private val sharing: Flow<Sharing> = combine(
        recipes.observeShareLinkAvailable().onStart { emit(false) },
        fetchingLink,
    ) { available, fetching -> Sharing(available, fetching) }

    val uiState: StateFlow<RecipeDetailUiState> = combine(
        sources,
        sessionChoices,
        manualRefresh,
        numberStyle,
        sharing,
    ) { current, choices, refreshing, style, sharing ->
        buildState(current, choices, refreshing, style, sharing)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), RecipeDetailUiState(recipeId = recipeId))

    init {
        viewModelScope.launch { recipes.markViewed(recipeId) }
    }

    fun onAction(action: RecipeDetailAction) {
        when (action) {
            RecipeDetailAction.IncreaseServings -> changeServings { floor(it) + 1.0 }
            RecipeDetailAction.DecreaseServings -> changeServings { (ceil(it) - 1.0).coerceAtLeast(ServingsState.MIN_SERVINGS) }
            RecipeDetailAction.ResetServings -> viewModelScope.launch { recipes.setServingsChoice(recipeId, null) }
            RecipeDetailAction.LargerPan -> changePan { PanScaling.larger(it) }
            RecipeDetailAction.SmallerPan -> changePan { PanScaling.smaller(it) }
            RecipeDetailAction.ResetPan -> changePan { null }
            is RecipeDetailAction.ToggleIngredient -> toggleIngredient(action.id)
            is RecipeDetailAction.SwitchUnit -> switchUnit(action.id)
            RecipeDetailAction.Refresh -> refresh()
            RecipeDetailAction.ToggleOffline -> toggleOffline()
            is RecipeDetailAction.SetNumberStyle -> numberStyle.value = action.style
            RecipeDetailAction.ShareLink -> shareLink()
            is RecipeDetailAction.ShareText -> share(action.text)
        }
    }

    private fun share(text: String) {
        effectChannel.trySend(RecipeDetailEffect.Share(uiState.value.header?.name.orEmpty(), text))
    }

    /** Plan §8.6: the name beside the link, since a message shows no subject. */
    private fun shareLink() {
        if (fetchingLink.value) return
        fetchingLink.value = true
        viewModelScope.launch {
            val outcome = recipes.shareLink(recipeId)
            fetchingLink.value = false
            when (outcome) {
                is Outcome.Success -> share(listOfNotNull(uiState.value.header?.name, outcome.value).joinToString("\n"))
                is Outcome.Failure -> effectChannel.trySend(RecipeDetailEffect.Message(shareLinkFailedMessage(outcome.error)))
            }
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

    /** Fas 5 (plan §8.1 "Pannstorlek"): another tin, shared with cook mode; the recipe's own is stored as none. */
    private fun changePan(next: (Int) -> Int?) {
        val pan = uiState.value.pan ?: return
        val target = next(pan.current)?.takeUnless { it == pan.original }
        savedStateHandle[KEY_PAN] = target ?: NO_PAN
        viewModelScope.launch { pans.setPanSize(recipeId, target) }
    }

    private fun toggleIngredient(id: Long) {
        val struck = savedStateHandle.get<LongArray>(KEY_STRUCK)?.toSet().orEmpty()
        savedStateHandle[KEY_STRUCK] = (if (id in struck) struck - id else struck + id).toLongArray()
    }

    private fun switchUnit(id: Long) {
        val state = uiState.value
        val item = (state.ingredients + state.steps.flatMap { it.ingredients })
            .filterIsInstance<IngredientRow.Item>()
            .firstOrNull { it.id == id } ?: return
        if (!item.canCycleUnits) return
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
            // Without a stored detail the screen shows the error state itself. A refresh that was
            // only overtaken by an account change (logout while it ran, SyncStop) is no failure.
            if (result is Outcome.Failure && !result.error.isTransientSyncStop && uiState.value.hasDetail) {
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

    private fun buildState(
        sources: Sources,
        choices: SessionChoices,
        manualRefreshing: Boolean,
        style: NumberStyle,
        sharing: Sharing,
    ): RecipeDetailUiState {
        val detail = sources.detail
        val recipe = detail.recipe
        val overview = detail.overview
        val servings = recipe?.let {
            val current = ServingsScaling.initialServings(it, sources.choice?.servings)
            ServingsState(
                current = current,
                original = ServingsScaling.baseServings(it.servings),
                text = it.servingsText,
                amountText = ServingsText.number(current, style),
            )
        }
        val pan = recipe?.let { panState(it, choices.pan, style) }
        val display = recipe?.let {
            RecipeDisplay.build(it, servings?.current ?: 0.0, sources.preferences, style, extraFactor = PanScaling.factor(it, choices.pan))
        }
        val showOverview = recipe?.showIngredientOverview ?: true
        // SyncStop markers (a fetch overtaken by a logout or account switch) are no error to show.
        val error = detail.error?.takeUnless { it.isTransientSyncStop }
        val offline = sources.offline || error.isConnectionProblem()
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
            pan = pan,
            ingredients = display?.takeIf { showOverview }?.ingredients.orEmpty(),
            steps = display?.toSteps(showOverview, sources.preferences).orEmpty(),
            sourceUrl = recipe?.sourceUrl?.takeIf { it.startsWith("https://") || it.startsWith("http://") },
            preferences = sources.preferences,
            refreshing = detail.refreshing || manualRefreshing,
            error = error,
            offline = offline,
            fetchedAt = detail.fetchedAt,
            availableOffline = overview?.availableOffline == true || recipe != null,
            pinned = overview?.pinned == true,
            canPin = !sources.session.isDemo && (overview != null || recipe != null),
            webUrl = sources.session.baseUrl?.let { "${it.trimEnd('/')}/recipe/$recipeId/" },
            canShareLink = sharing.available,
            fetchingShareLink = sharing.fetching,
            struckIngredientIds = choices.struck,
            unitChoices = choices.units,
            // Fas 3 (näringsvärden): per serving, so they do not move with the servings chooser;
            // energy in kJ or kcal as the user's `use_kj` says (plan §10 "Klart när").
            nutrition = NutritionEnergy.inPreferredUnit(recipe?.nutritionValues.orEmpty(), sources.preferences.useKj)
                .map { NutritionRow(it.name, ServingsText.number(it.amount, style), it.unit) },
        )
    }

    /** Fas 5 (plan §8.1 "Pannstorlek"): the tin picker, null for a recipe without a round tin. */
    private fun panState(recipe: Recipe, chosen: Int?, style: NumberStyle): PanState? {
        if (!PanScaling.hasTin(recipe)) return null
        val current = PanScaling.current(recipe, chosen)
        return PanState(
            original = recipe.diameter,
            current = current,
            unit = PanScaling.unit(recipe),
            factorText = ServingsText.number(PanScaling.areaFactor(recipe.diameter, current), style),
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

    private fun RecipeDisplayModel.toSteps(showIngredientOverview: Boolean, preferences: UserPreferences): List<StepItem> =
        steps.map { step ->
            StepItem(
                id = step.id,
                number = step.number,
                name = step.name,
                instruction = step.instruction,
                minutes = step.timeMinutes,
                ingredients = step.ingredients,
                // Without an overview the steps are the only place the ingredients appear.
                showIngredients = step.ingredients.isNotEmpty() &&
                    (!showIngredientOverview || (step.showIngredientsTable && preferences.showStepIngredients)),
                stepRecipe = step.stepRecipe,
                hasBrokenTemplate = step.hasBrokenTemplate,
            )
        }

    private data class SessionInfo(val isDemo: Boolean = false, val baseUrl: String? = null)

    private data class SessionChoices(val struck: Set<Long>, val units: Map<Long, Int>, val pan: Int?)

    private data class Sharing(val available: Boolean, val fetching: Boolean)

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

        // Fas 5 (kok lane): the tin this screen chose, for after a process death.
        const val KEY_PAN = "pan_size"
        private const val NO_PAN = 0
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        /** Ingredient id / unit index pairs, flattened (a Bundle-friendly LongArray). */
        fun LongArray.toUnitChoices(): Map<Long, Int> = (0 until size / 2).associate { this[it * 2] to this[it * 2 + 1].toInt() }

        fun Map<Long, Int>.toLongArray(): LongArray = flatMap { (id, index) -> listOf(id, index.toLong()) }.toLongArray()

        fun refreshFailedMessage(error: TandemError): Int =
            if (error.isConnectionProblem()) R.string.recipe_detail_refresh_failed_offline else R.string.recipe_detail_refresh_failed

        fun shareLinkFailedMessage(error: TandemError): Int = when {
            error.isConnectionProblem() -> R.string.recipe_detail_share_offline
            error.dataProblem() == DataProblem.SHARING_OFF -> R.string.recipe_detail_share_refused
            else -> R.string.recipe_detail_share_failed
        }
    }
}

/** No connection to the server (as opposed to the server refusing). */
internal fun TandemError?.isConnectionProblem(): Boolean =
    this == TandemError.NetworkUnavailable || this == TandemError.UnknownHost || this == TandemError.Timeout
