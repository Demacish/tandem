package casa.moller.tandem.feature.cook

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import casa.moller.tandem.core.data.cooklog.CookLogRepository
import casa.moller.tandem.core.data.preferences.PreferencesRepository
import casa.moller.tandem.core.data.recipe.RecipeRepository
import casa.moller.tandem.core.data.settings.SettingsRepository
import casa.moller.tandem.core.data.sync.SyncRepository
import casa.moller.tandem.core.data.timer.TimerController
import casa.moller.tandem.core.domain.format.NumberStyle
import casa.moller.tandem.core.domain.format.toNumberStyle
import casa.moller.tandem.core.domain.recipe.IngredientRow
import casa.moller.tandem.core.domain.recipe.RecipeDisplay
import casa.moller.tandem.core.domain.recipe.ServingsText
import casa.moller.tandem.core.domain.scaling.ServingsScaling
import casa.moller.tandem.core.domain.timer.StepTimerSuggestions
import casa.moller.tandem.core.model.cooklog.CookLogDraft
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.preferences.UserPreferences
import casa.moller.tandem.core.model.recipe.Recipe
import casa.moller.tandem.core.model.recipe.RecipeDetailState
import casa.moller.tandem.core.model.recipe.ServingsChoice
import casa.moller.tandem.core.model.settings.AppSettings
import casa.moller.tandem.core.model.settings.CookTextScale
import casa.moller.tandem.core.model.sync.SyncPause
import casa.moller.tandem.core.model.sync.isTransientSyncStop
import casa.moller.tandem.core.model.timer.CookTimer
import casa.moller.tandem.core.model.timer.TimerCapabilities
import casa.moller.tandem.core.model.timer.TimerMode
import casa.moller.tandem.core.model.timer.TimerStartResult
import casa.moller.tandem.core.model.timer.TimerSuggestion
import casa.moller.tandem.feature.cook.navigation.CookKey
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Cook mode (plan 6.2 "Kokläge", 8.1, features #6–#8): one step per page with the step's scaled
 * ingredients and timer chips, the device's timers, and "Klart!" with the cook log.
 *
 * - **Amounts** come from core:domain's [RecipeDisplay] at the chosen servings, the same rules as
 *   the detail screen and Tandoor's web (scaled amounts inside the step text too).
 * - **Servings** start as [CookKey.servings] (the detail's choice) or the recipe's remembered
 *   choice; a change here is remembered for the recipe like on the detail screen.
 * - **Survives** rotation, resize and process death: page, servings, open ingredient section,
 *   struck rows, units, rating and comment live in [SavedStateHandle]. Timers live in the
 *   [TimerController] (they survive anyway).
 * - **Notifications in context** (plan 8.7): the first timer asks first ([CookUiState.notificationPrompt]),
 *   then the system dialog ([CookEffect.RequestNotificationPermission]); the timer starts whatever
 *   the answer.
 */
@HiltViewModel(assistedFactory = CookViewModel.Factory::class)
class CookViewModel @AssistedInject constructor(
    @Assisted private val key: CookKey,
    private val savedStateHandle: SavedStateHandle,
    private val recipes: RecipeRepository,
    preferencesRepository: PreferencesRepository,
    private val settingsRepository: SettingsRepository,
    private val timerController: TimerController,
    private val cookLog: CookLogRepository,
    syncRepository: SyncRepository,
    private val clock: Clock,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(key: CookKey): CookViewModel
    }

    private val recipeId = key.recipeId
    private val suggestions = StepTimerSuggestions()

    private val effectChannel = Channel<CookEffect>(Channel.BUFFERED)

    /** One-off events: messages, the notification dialog, leaving after "Klart!". */
    val effects: Flow<CookEffect> = effectChannel.receiveAsFlow()

    /** Until the screen reports its locale: the process default (the app's language at start). */
    private val locale = MutableStateFlow(LocaleInfo(Locale.getDefault().toNumberStyle(), Locale.getDefault().toLanguageTag()))

    private val saving = MutableStateFlow(false)

    /**
     * "Not now" on the notification question: not asked again while this cook mode is open (also
     * after a rotation), and cook mode warns that the alarm may be silent.
     */
    private val notificationPromptDeclined: Boolean
        get() = savedStateHandle.get<Boolean>(KEY_PROMPT_DECLINED) == true

    private val settings: StateFlow<AppSettings> =
        settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings.Default)

    private val recipeSources: Flow<RecipeSources> = combine(
        recipes.observeRecipe(recipeId),
        recipes.observeServingsChoice(recipeId),
        preferencesRepository.observeUserPreferences(),
        locale,
    ) { detail, choice, preferences, locale -> RecipeSources(detail, choice, preferences, locale) }

    private val timerSources: Flow<TimerSources> = combine(
        timerController.timers,
        timerController.capabilities,
        syncRepository.status.map { it.pause == SyncPause.OFFLINE }.distinctUntilChanged(),
        saving,
        savedStateHandle.getStateFlow(KEY_PROMPT_DECLINED, false),
    ) { timers, capabilities, offline, saving, declined -> TimerSources(timers, capabilities, offline, saving, declined) }

    private val sessionState: Flow<SessionState> = combine(
        savedStateHandle.getStateFlow(KEY_PAGE, key.startStep),
        savedStateHandle.getStateFlow(KEY_SERVINGS, NO_SERVINGS),
        savedStateHandle.getStateFlow(KEY_INGREDIENTS_OPEN, true),
        combine(
            savedStateHandle.getStateFlow(KEY_STRUCK, LongArray(0)),
            savedStateHandle.getStateFlow(KEY_UNITS, LongArray(0)),
        ) { struck, units -> struck.toSet() to units.toUnitChoices() },
        combine(
            savedStateHandle.getStateFlow(KEY_RATING, NO_RATING),
            savedStateHandle.getStateFlow(KEY_COMMENT, ""),
            savedStateHandle.getStateFlow(KEY_LOG_SERVINGS, 0),
            savedStateHandle.getStateFlow<String?>(KEY_PENDING, null),
        ) { rating, comment, logServings, pending ->
            DoneInputs(rating.takeIf { it != NO_RATING }, comment, logServings, pending?.let(PendingTimer::decode))
        },
    ) { page, servings, open, rows, done ->
        SessionState(page, servings.takeIf { it > 0.0 }, open, rows.first, rows.second, done)
    }

    val uiState: StateFlow<CookUiState> = combine(recipeSources, timerSources, sessionState, settings) { recipe, timers, session, app ->
        buildState(recipe, timers, session, app)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), CookUiState(recipeId = recipeId, page = key.startStep))

    fun onAction(action: CookAction) {
        when (action) {
            CookAction.Next -> goToPage(uiState.value.page + 1)
            CookAction.Previous -> goToPage(uiState.value.page - 1)
            is CookAction.GoToPage -> goToPage(action.page)
            CookAction.IncreaseServings -> changeServings { floor(it) + 1.0 }
            CookAction.DecreaseServings -> changeServings { ceil(it) - 1.0 }
            CookAction.ToggleIngredients -> savedStateHandle[KEY_INGREDIENTS_OPEN] = !uiState.value.ingredientsExpanded
            is CookAction.ToggleIngredient -> toggleIngredient(action.id)
            is CookAction.SwitchUnit -> switchUnit(action.id)
            CookAction.LargerText -> setTextScale(CookTextScale.larger(uiState.value.textScale))
            CookAction.SmallerText -> setTextScale(CookTextScale.smaller(uiState.value.textScale))
            is CookAction.StartTimer -> startTimer(action.stepIndex, action.suggestion)
            is CookAction.PauseTimer -> viewModelScope.launch { timerController.pause(action.id) }
            is CookAction.ResumeTimer -> viewModelScope.launch { timerController.resume(action.id) }
            is CookAction.AddMinute -> viewModelScope.launch { timerController.addOneMinute(action.id) }
            is CookAction.CancelTimer -> viewModelScope.launch { timerController.cancel(action.id) }
            is CookAction.StopRinging -> viewModelScope.launch { timerController.stopRinging(action.id) }
            CookAction.AcceptNotificationPrompt -> effectChannel.trySend(CookEffect.RequestNotificationPermission)
            CookAction.DeclineNotificationPrompt -> declineNotificationPrompt()
            is CookAction.NotificationPermissionResult -> onPermissionResult(action.granted)
            CookAction.DismissExactAlarmPrompt -> viewModelScope.launch { settingsRepository.setExactAlarmPromptDismissed(true) }
            CookAction.RefreshCapabilities -> timerController.refreshCapabilities()
            is CookAction.SetRating -> setRating(action.stars)
            is CookAction.SetComment -> savedStateHandle[KEY_COMMENT] = action.text.take(MAX_COMMENT_LENGTH)
            CookAction.IncreaseLogServings -> savedStateHandle[KEY_LOG_SERVINGS] = uiState.value.done.servings + 1
            CookAction.DecreaseLogServings -> savedStateHandle[KEY_LOG_SERVINGS] = (uiState.value.done.servings - 1).coerceAtLeast(1)
            CookAction.SaveCookLog -> saveCookLog()
            CookAction.Retry -> viewModelScope.launch { recipes.refreshRecipe(recipeId) }
            is CookAction.SetLocale -> locale.value = LocaleInfo(action.style, action.languageTag)
        }
    }

    private fun goToPage(page: Int) {
        val state = uiState.value
        // Before the recipe is loaded the number of pages is unknown; the requested page is kept
        // and clamped when the steps arrive.
        val last = if (state.hasDetail) state.donePage else Int.MAX_VALUE
        savedStateHandle[KEY_PAGE] = page.coerceIn(0, last)
    }

    private fun changeServings(next: (Double) -> Double) {
        val servings = uiState.value.servings ?: return
        val target = next(servings.current).coerceAtLeast(CookServings.MIN_SERVINGS)
        savedStateHandle[KEY_SERVINGS] = target
        viewModelScope.launch {
            // Like the detail screen: the recipe's own servings are not stored as a choice.
            recipes.setServingsChoice(recipeId, target.takeUnless { it == servings.original.toDouble() })
        }
    }

    private fun toggleIngredient(id: Long) {
        val struck = savedStateHandle.get<LongArray>(KEY_STRUCK)?.toSet().orEmpty()
        savedStateHandle[KEY_STRUCK] = (if (id in struck) struck - id else struck + id).toLongArray()
    }

    private fun switchUnit(id: Long) {
        val item = uiState.value.steps
            .flatMap { it.ingredients }
            .filterIsInstance<IngredientRow.Item>()
            .firstOrNull { it.id == id } ?: return
        if (!item.canCycleUnits) return
        val choices = savedStateHandle.get<LongArray>(KEY_UNITS)?.toUnitChoices().orEmpty()
        val next = ((choices[id] ?: 0) + 1) % item.units.size
        savedStateHandle[KEY_UNITS] = (choices + (id to next)).toLongArray()
    }

    private fun setTextScale(scale: Float) {
        viewModelScope.launch {
            if (settingsRepository.setCookTextScale(scale) is Outcome.Failure) {
                effectChannel.trySend(CookEffect.Message(R.string.cook_setting_not_saved))
            }
        }
    }

    private fun startTimer(stepIndex: Int, suggestion: TimerSuggestion) {
        val state = uiState.value
        val step = state.steps.getOrNull(stepIndex) ?: return
        val pending = PendingTimer(
            stepIndex = stepIndex,
            label = timerLabel(step.name, state.recipeName, suggestion),
            durationSeconds = suggestion.duration.inWholeSeconds,
        )
        val askFirst = settings.value.timerMode == TimerMode.IN_APP &&
            timerController.capabilities.value.shouldAskForNotifications &&
            !notificationPromptDeclined
        if (askFirst) {
            savedStateHandle[KEY_PENDING] = pending.encode()
        } else {
            launchTimer(pending)
        }
    }

    private fun declineNotificationPrompt() {
        savedStateHandle[KEY_PROMPT_DECLINED] = true
        takePending()?.let(::launchTimer)
    }

    private fun onPermissionResult(granted: Boolean) {
        viewModelScope.launch {
            timerController.onNotificationPermissionResult(granted)
            takePending()?.let(::launchTimer)
        }
    }

    private fun takePending(): PendingTimer? {
        val pending = savedStateHandle.get<String>(KEY_PENDING)?.let(PendingTimer::decode)
        savedStateHandle[KEY_PENDING] = null
        return pending
    }

    private fun launchTimer(pending: PendingTimer) {
        viewModelScope.launch {
            val result = timerController.start(
                label = pending.label,
                duration = pending.durationSeconds.seconds,
                recipeId = recipeId,
                stepIndex = pending.stepIndex,
            )
            val message = when (result) {
                is TimerStartResult.Started, TimerStartResult.Invalid -> null
                TimerStartResult.HandedToClockApp -> R.string.cook_timer_clock_app
                TimerStartResult.ClockAppUnavailable -> R.string.cook_timer_no_clock_app
                is TimerStartResult.TooManyTimers -> R.string.cook_timer_too_many
            }
            message?.let { effectChannel.trySend(CookEffect.Message(it)) }
        }
    }

    private fun setRating(stars: Int) {
        if (stars !in CookLogDraft.RATING_RANGE) return
        val current = uiState.value.done.rating
        savedStateHandle[KEY_RATING] = if (current == stars) NO_RATING else stars
    }

    private fun saveCookLog() {
        val state = uiState.value
        if (saving.value || !state.hasDetail) return
        saving.value = true
        val draft = CookLogDraft(
            recipeId = recipeId,
            servings = state.done.servings,
            rating = state.done.rating,
            comment = state.done.comment,
            cookedAt = clock.now(),
        )
        viewModelScope.launch {
            when (cookLog.logCooked(draft)) {
                is Outcome.Success -> {
                    // The rung and stopped timers of this meal are done with too (TimerController.clearFinished).
                    timerController.clearFinished()
                    val message = when {
                        state.demo -> R.string.cook_done_saved_demo
                        state.offline -> R.string.cook_done_saved_offline
                        else -> R.string.cook_done_saved
                    }
                    effectChannel.trySend(CookEffect.Finished(message))
                }

                is Outcome.Failure -> {
                    saving.value = false
                    effectChannel.trySend(CookEffect.Message(R.string.cook_done_save_failed))
                }
            }
        }
    }

    private fun buildState(recipe: RecipeSources, timers: TimerSources, session: SessionState, settings: AppSettings): CookUiState {
        val detail = recipe.detail
        val stored = detail.recipe
        val servings = stored?.let { buildServings(it, session.servings ?: key.servings ?: recipe.choice?.servings, recipe.locale.style) }
        val steps = stored?.let { buildSteps(it, servings?.current ?: 0.0, recipe.preferences, recipe.locale) }.orEmpty()
        val page = if (stored != null) session.page.coerceIn(0, steps.size) else session.page
        val done = session.done
        return CookUiState(
            recipeId = recipeId,
            loading = false,
            recipeName = stored?.name ?: detail.overview?.name.orEmpty(),
            imageUrl = stored?.imageUrl ?: detail.overview?.imageUrl,
            hasDetail = stored != null,
            // A fetch overtaken by a logout or account switch (SyncStop) is no error to show.
            error = detail.error?.takeUnless { it.isTransientSyncStop },
            steps = steps,
            page = page,
            servings = servings,
            ingredientsExpanded = session.ingredientsOpen,
            struckIngredientIds = session.struck,
            unitChoices = session.units,
            timers = timers.timers,
            capabilities = timers.capabilities,
            textScale = CookTextScale.coerce(settings.cookTextScale),
            alwaysDark = settings.cookModeAlwaysDark,
            dynamicColor = settings.dynamicColor,
            exactAlarmPromptDismissed = settings.exactAlarmPromptDismissed,
            notificationPrompt = done.pending,
            notificationPromptDeclined = timers.promptDeclined,
            done = DoneState(
                servings = done.logServings.takeIf { it > 0 } ?: CookLogDraft.servingsOf(servings?.current) ?: 1,
                rating = done.rating,
                comment = done.comment,
                saving = timers.saving,
            ),
            offline = timers.offline,
            demo = settings.demoMode,
        )
    }

    private fun buildServings(recipe: Recipe, chosen: Double?, style: NumberStyle): CookServings {
        val current = ServingsScaling.initialServings(recipe, chosen)
        return CookServings(
            current = current,
            original = ServingsScaling.baseServings(recipe.servings),
            text = recipe.servingsText,
            amountText = ServingsText.number(current, style),
        )
    }

    private fun buildSteps(recipe: Recipe, servings: Double, preferences: UserPreferences, locale: LocaleInfo): List<CookStep> {
        val display = RecipeDisplay.build(recipe, servings, preferences, locale.style)
        if (display.steps.isEmpty()) {
            // A recipe without steps still gets one page with all its ingredients, then "Klart!".
            return listOf(
                CookStep(index = 0, number = 1, name = "", instruction = "", ingredients = display.ingredients, suggestions = emptyList()),
            )
        }
        return display.steps.mapIndexed { index, step ->
            CookStep(
                index = index,
                number = step.number,
                name = step.name,
                instruction = step.instruction,
                ingredients = step.ingredients,
                suggestions = suggestions.suggest(step.instruction, step.timeMinutes, locale.languageTag),
                hasBrokenTemplate = step.hasBrokenTemplate,
                stepRecipeName = step.stepRecipe?.let { it.name ?: "" },
            )
        }
    }

    private data class LocaleInfo(val style: NumberStyle, val languageTag: String)

    private data class RecipeSources(
        val detail: RecipeDetailState,
        val choice: ServingsChoice?,
        val preferences: UserPreferences,
        val locale: LocaleInfo,
    )

    private data class TimerSources(
        val timers: List<CookTimer>,
        val capabilities: TimerCapabilities,
        val offline: Boolean,
        val saving: Boolean,
        val promptDeclined: Boolean,
    )

    private data class DoneInputs(val rating: Int?, val comment: String, val logServings: Int, val pending: PendingTimer?)

    private data class SessionState(
        val page: Int,
        val servings: Double?,
        val ingredientsOpen: Boolean,
        val struck: Set<Long>,
        val units: Map<Long, Int>,
        val done: DoneInputs,
    )

    internal companion object {
        const val KEY_PAGE = "cook_page"
        const val KEY_SERVINGS = "cook_servings"
        const val KEY_INGREDIENTS_OPEN = "cook_ingredients_open"
        const val KEY_STRUCK = "cook_struck"
        const val KEY_UNITS = "cook_units"
        const val KEY_RATING = "cook_rating"
        const val KEY_COMMENT = "cook_comment"
        const val KEY_LOG_SERVINGS = "cook_log_servings"
        const val KEY_PENDING = "cook_pending_timer"
        const val KEY_PROMPT_DECLINED = "cook_notification_prompt_declined"

        private const val NO_SERVINGS = 0.0
        private const val NO_RATING = -1
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        /** Tandoor's cook log comment is free text; a sane cap for a phone keyboard. */
        const val MAX_COMMENT_LENGTH = 2_000

        /** "Svampen · 5 min": the step's name (or the recipe's), then the duration as the step says it. */
        fun timerLabel(stepName: String, recipeName: String, suggestion: TimerSuggestion): String {
            val owner = stepName.ifBlank { recipeName }.trim()
            return if (owner.isEmpty()) suggestion.label else "$owner · ${suggestion.label}"
        }

        /** Ingredient id / unit index pairs, flattened (a Bundle-friendly LongArray). */
        fun LongArray.toUnitChoices(): Map<Long, Int> = (0 until size / 2).associate { this[it * 2] to this[it * 2 + 1].toInt() }

        fun Map<Long, Int>.toLongArray(): LongArray = flatMap { (id, index) -> listOf(id, index.toLong()) }.toLongArray()
    }
}
