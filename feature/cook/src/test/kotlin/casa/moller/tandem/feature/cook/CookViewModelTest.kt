package casa.moller.tandem.feature.cook

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import casa.moller.tandem.core.domain.format.NumberStyle
import casa.moller.tandem.core.domain.recipe.IngredientRow
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.recipe.Recipe
import casa.moller.tandem.core.model.recipe.RecipeDetailState
import casa.moller.tandem.core.model.recipe.Step
import casa.moller.tandem.core.model.settings.AppSettings
import casa.moller.tandem.core.model.sync.SyncPause
import casa.moller.tandem.core.model.sync.SyncStatus
import casa.moller.tandem.core.model.timer.TimerCapabilities
import casa.moller.tandem.core.model.timer.TimerMode
import casa.moller.tandem.core.model.timer.TimerSource
import casa.moller.tandem.core.model.timer.TimerState
import casa.moller.tandem.core.model.timer.TimerSuggestion
import casa.moller.tandem.core.model.timer.TimerSuggestionSource
import casa.moller.tandem.core.testing.MainDispatcherRule
import casa.moller.tandem.core.testing.data.FakeCookLogRepository
import casa.moller.tandem.core.testing.data.FakePreferencesRepository
import casa.moller.tandem.core.testing.data.FakeRecipeRepository
import casa.moller.tandem.core.testing.data.FakeSettingsRepository
import casa.moller.tandem.core.testing.data.FakeSyncRepository
import casa.moller.tandem.core.testing.data.FakeTimerController
import casa.moller.tandem.core.testing.data.SampleRecipes
import casa.moller.tandem.core.testing.time.TestClock
import casa.moller.tandem.feature.cook.navigation.CookKey
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CookViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val clock = TestClock()
    private val repository = FakeRecipeRepository()
    private val preferences = FakePreferencesRepository()
    private val settings = FakeSettingsRepository()
    private val timers = FakeTimerController(clock)
    private val cookLog = FakeCookLogRepository()
    private val sync = FakeSyncRepository()

    private val pasta = SampleRecipes.MushroomPasta

    private fun TestScope.viewModel(key: CookKey = CookKey(pasta.id), handle: SavedStateHandle = SavedStateHandle()): CookViewModel {
        val viewModel = CookViewModel(key, handle, repository, preferences, settings, timers, cookLog, sync, clock)
        viewModel.onAction(CookAction.SetLocale(NumberStyle.English, "sv"))
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()
        return viewModel
    }

    private fun CookUiState.amount(stepIndex: Int, id: Long): String =
        steps[stepIndex].ingredients.filterIsInstance<IngredientRow.Item>().first { it.id == id }.let { "${it.amount} ${it.unit}".trim() }

    @Test
    fun opensWithOnePagePerStepAndDoneLast() = runTest(mainDispatcher.dispatcher) {
        val state = viewModel().uiState.value

        assertTrue(state.hasDetail)
        assertEquals("Krämig svamppasta", state.recipeName)
        assertEquals(3, state.steps.size)
        assertEquals(4, state.pageCount)
        assertEquals(0, state.page)
        assertEquals(3, state.donePage)
        assertEquals(listOf("Svampen", "Pastan", ""), state.steps.map { it.name })
        // Each step carries its own scaled ingredients.
        assertEquals(listOf(10111L, 10112L, 10113L), state.steps[0].ingredients.map { it.id })
        assertEquals("400 g", state.amount(0, 10111))
    }

    @Test
    fun nextAndPreviousStayInsideThePages() = runTest(mainDispatcher.dispatcher) {
        val viewModel = viewModel()

        viewModel.onAction(CookAction.Previous)
        assertEquals(0, viewModel.uiState.value.page)
        repeat(5) { viewModel.onAction(CookAction.Next) }
        assertEquals(3, viewModel.uiState.value.page)
        assertTrue(viewModel.uiState.value.isDonePage)
        viewModel.onAction(CookAction.Previous)
        assertEquals(2, viewModel.uiState.value.page)
        viewModel.onAction(CookAction.GoToPage(1))
        assertEquals(1, viewModel.uiState.value.page)
        viewModel.onAction(CookAction.GoToPage(99))
        assertEquals(3, viewModel.uiState.value.page)
    }

    @Test
    fun startStepFromTheKey() = runTest(mainDispatcher.dispatcher) {
        assertEquals(2, viewModel(CookKey(pasta.id, startStep = 2)).uiState.value.page)
        // A step that no longer exists opens the last page instead of crashing.
        assertEquals(3, viewModel(CookKey(pasta.id, startStep = 10)).uiState.value.page)
    }

    @Test
    fun servingsFromTheKeyScaleTheStepIngredientsAndTheText() = runTest(mainDispatcher.dispatcher) {
        val templated = pasta.copy(
            id = 900,
            steps = listOf(Step(id = 1, instruction = "Stek {{ scale(250) }} g svamp.", ingredients = pasta.steps[0].ingredients)),
        )
        repository.details.value = repository.details.value + (templated.id to templated)

        val state = viewModel(CookKey(templated.id, servings = 8.0)).uiState.value

        assertEquals(8.0, state.servings?.current)
        assertEquals("800 g", state.amount(0, 10111))
        assertEquals("Stek 500 g svamp.", state.steps[0].instruction)
    }

    @Test
    fun theRememberedChoiceIsUsedWithoutServingsInTheKey() = runTest(mainDispatcher.dispatcher) {
        repository.setServingsChoice(pasta.id, 2.0)

        val state = viewModel().uiState.value

        assertEquals(2.0, state.servings?.current)
        assertEquals("200 g", state.amount(0, 10111))
    }

    @Test
    fun changingServingsRescalesAndIsRemembered() = runTest(mainDispatcher.dispatcher) {
        val viewModel = viewModel()

        viewModel.onAction(CookAction.IncreaseServings)
        assertEquals(5.0, viewModel.uiState.value.servings?.current)
        assertEquals("500 g", viewModel.uiState.value.amount(0, 10111))
        assertEquals(5.0, repository.servingsChoices.value[pasta.id])

        viewModel.onAction(CookAction.DecreaseServings)
        // Back at the recipe's own servings: no choice is stored (like the detail screen).
        assertEquals(4.0, viewModel.uiState.value.servings?.current)
        assertNull(repository.servingsChoices.value[pasta.id])
        repeat(5) { viewModel.onAction(CookAction.DecreaseServings) }
        assertEquals(1.0, viewModel.uiState.value.servings?.current)
        assertFalse(viewModel.uiState.value.servings!!.canDecrease)
    }

    @Test
    fun timerSuggestionsComeFromTheStepTimeAndTheText() = runTest(mainDispatcher.dispatcher) {
        val steps = viewModel().uiState.value.steps

        // Step 1: the text says "ca 5 minuter", the same as Step.time, so one chip from the text.
        assertEquals(1, steps[0].suggestions.size)
        assertEquals(TimerSuggestionSource.TEXT, steps[0].suggestions[0].source)
        assertEquals(5.minutes, steps[0].suggestions[0].duration)
        // Step 2: "8–10 minuter" runs for the longer end.
        assertEquals(10.minutes, steps[1].suggestions.single().duration)
        assertTrue(steps[1].suggestions.single().isRange)
        assertTrue(steps[2].suggestions.isEmpty())
    }

    @Test
    fun aChipStartsATimerForTheRecipeAndStep() = runTest(mainDispatcher.dispatcher) {
        val viewModel = viewModel()
        val suggestion = viewModel.uiState.value.steps[1].suggestions.single()

        viewModel.onAction(CookAction.StartTimer(1, suggestion))
        runCurrent()

        val timer = timers.timers.value.single()
        assertEquals("Pastan · ${suggestion.label}", timer.label)
        assertEquals(10.minutes, timer.duration)
        assertEquals(pasta.id, timer.recipeId)
        assertEquals(1, timer.stepIndex)
        assertEquals(TimerState.RUNNING, timer.state)
        assertEquals(listOf(timer), viewModel.uiState.value.timers)
        assertEquals(listOf(timer), viewModel.uiState.value.activeTimers)
    }

    @Test
    fun twoTimersRunAndRingTogether() = runTest(mainDispatcher.dispatcher) {
        val viewModel = viewModel()
        val steps = viewModel.uiState.value.steps
        viewModel.onAction(CookAction.StartTimer(0, steps[0].suggestions.single()))
        viewModel.onAction(CookAction.StartTimer(1, steps[1].suggestions.single()))
        runCurrent()

        timers.advanceTimeBy(10.minutes)
        runCurrent()

        assertEquals(listOf(TimerState.RINGING, TimerState.RINGING), viewModel.uiState.value.timers.map { it.state })
        val first = viewModel.uiState.value.timers.first().id
        viewModel.onAction(CookAction.StopRinging(first))
        viewModel.onAction(CookAction.AddMinute(viewModel.uiState.value.timers.last().id))
        runCurrent()
        assertEquals(listOf(TimerState.FINISHED, TimerState.RUNNING), viewModel.uiState.value.timers.map { it.state })
    }

    @Test
    fun timerButtonsPauseResumeAndCancel() = runTest(mainDispatcher.dispatcher) {
        val viewModel = viewModel()
        viewModel.onAction(CookAction.StartTimer(0, viewModel.uiState.value.steps[0].suggestions.single()))
        runCurrent()
        val id = timers.timers.value.single().id

        viewModel.onAction(CookAction.PauseTimer(id))
        runCurrent()
        assertEquals(TimerState.PAUSED, timers.timers.value.single().state)
        viewModel.onAction(CookAction.ResumeTimer(id))
        runCurrent()
        assertEquals(TimerState.RUNNING, timers.timers.value.single().state)
        viewModel.onAction(CookAction.CancelTimer(id))
        runCurrent()
        assertTrue(timers.timers.value.isEmpty())
    }

    @Test
    fun theFirstTimerAsksForNotificationsInContext() = runTest(mainDispatcher.dispatcher) {
        timers.setCapabilities(TimerCapabilities.FreshInstall)
        val viewModel = viewModel()
        val suggestion = viewModel.uiState.value.steps[0].suggestions.single()

        viewModel.effects.test {
            viewModel.onAction(CookAction.StartTimer(0, suggestion))
            runCurrent()
            // The question first, no timer yet.
            assertEquals(0, viewModel.uiState.value.notificationPrompt?.stepIndex)
            assertTrue(timers.timers.value.isEmpty())

            viewModel.onAction(CookAction.AcceptNotificationPrompt)
            assertEquals(CookEffect.RequestNotificationPermission, awaitItem())

            viewModel.onAction(CookAction.NotificationPermissionResult(granted = false))
            runCurrent()
            assertEquals(listOf(false), timers.permissionResults)
            // The timer starts whatever the answer was.
            assertEquals(1, timers.timers.value.size)
            assertNull(viewModel.uiState.value.notificationPrompt)
            // Notifications denied: the alarm may be silent, cook mode says so (not asked again).
            assertTrue(viewModel.uiState.value.showSilentAlarmWarning)
        }
    }

    @Test
    fun notNowStartsTheTimerAndDoesNotAskAgainThisSession() = runTest(mainDispatcher.dispatcher) {
        timers.setCapabilities(TimerCapabilities.FreshInstall)
        val viewModel = viewModel()
        val suggestion = viewModel.uiState.value.steps[0].suggestions.single()

        viewModel.onAction(CookAction.StartTimer(0, suggestion))
        viewModel.onAction(CookAction.DeclineNotificationPrompt)
        runCurrent()
        assertEquals(1, timers.timers.value.size)
        assertTrue(timers.permissionResults.isEmpty())
        // Never answered, but declined: cook mode says the alarm may be silent.
        assertTrue(viewModel.uiState.value.showSilentAlarmWarning)

        viewModel.onAction(CookAction.StartTimer(0, suggestion))
        runCurrent()
        assertNull(viewModel.uiState.value.notificationPrompt)
        assertEquals(2, timers.timers.value.size)
    }

    @Test
    fun theClockAppNeedsNoNotificationQuestion() = runTest(mainDispatcher.dispatcher) {
        timers.setCapabilities(TimerCapabilities.FreshInstall)
        timers.mode = TimerMode.CLOCK_APP
        settings.state.value = AppSettings.Default.copy(timerMode = TimerMode.CLOCK_APP)
        val viewModel = viewModel()

        viewModel.effects.test {
            viewModel.onAction(CookAction.StartTimer(0, viewModel.uiState.value.steps[0].suggestions.single()))
            runCurrent()
            assertEquals(CookEffect.Message(R.string.cook_timer_clock_app), awaitItem())
        }
        assertNull(viewModel.uiState.value.notificationPrompt)
        assertEquals(1, timers.clockAppRequests.size)
    }

    @Test
    fun lateAlarmWarningOnlyWhileTimersRun() = runTest(mainDispatcher.dispatcher) {
        timers.setCapabilities(TimerCapabilities.AllGranted.copy(exactAlarmsAllowed = false, exactAlarmsRequestable = true))
        val viewModel = viewModel()
        assertFalse(viewModel.uiState.value.showLateAlarmWarning)

        viewModel.onAction(CookAction.StartTimer(0, viewModel.uiState.value.steps[0].suggestions.single()))
        runCurrent()
        assertTrue(viewModel.uiState.value.showLateAlarmWarning)

        viewModel.onAction(CookAction.DismissExactAlarmPrompt)
        runCurrent()
        assertTrue(settings.state.value.exactAlarmPromptDismissed)
        assertTrue(viewModel.uiState.value.exactAlarmPromptDismissed)
    }

    @Test
    fun textSizeIsStoredInTheSettings() = runTest(mainDispatcher.dispatcher) {
        val viewModel = viewModel()

        viewModel.onAction(CookAction.LargerText)
        runCurrent()
        assertEquals(1.15f, settings.state.value.cookTextScale)
        assertEquals(1.15f, viewModel.uiState.value.textScale)
        repeat(10) { viewModel.onAction(CookAction.LargerText) }
        runCurrent()
        assertFalse(viewModel.uiState.value.canIncreaseText)
    }

    @Test
    fun cookModeFollowsTheAlwaysDarkSetting() = runTest(mainDispatcher.dispatcher) {
        settings.state.value = AppSettings.Default.copy(cookModeAlwaysDark = true)
        assertTrue(viewModel().uiState.value.alwaysDark)
    }

    @Test
    fun doneSavesTheCookLog() = runTest(mainDispatcher.dispatcher) {
        val viewModel = viewModel(CookKey(pasta.id, servings = 2.5))
        viewModel.onAction(CookAction.GoToPage(3))
        viewModel.onAction(CookAction.SetRating(4))
        viewModel.onAction(CookAction.SetComment("Mer vitlök nästa gång"))

        viewModel.effects.test {
            viewModel.onAction(CookAction.SaveCookLog)
            runCurrent()
            assertEquals(CookEffect.Finished(R.string.cook_done_saved), awaitItem())
        }

        val draft = cookLog.logged.single()
        assertEquals(pasta.id, draft.recipeId)
        // 2.5 servings are logged as 3 (Tandoor stores whole servings, rounded half up).
        assertEquals(3, draft.servings)
        assertEquals(4, draft.rating)
        assertEquals("Mer vitlök nästa gång", draft.comment)
        assertEquals(clock.now(), draft.cookedAt)
    }

    @Test
    fun theSameStarAgainClearsTheRating() = runTest(mainDispatcher.dispatcher) {
        val viewModel = viewModel()
        viewModel.onAction(CookAction.SetRating(3))
        assertEquals(3, viewModel.uiState.value.done.rating)
        viewModel.onAction(CookAction.SetRating(3))
        assertNull(viewModel.uiState.value.done.rating)
        viewModel.onAction(CookAction.SetRating(9))
        assertNull(viewModel.uiState.value.done.rating)
    }

    @Test
    fun loggedServingsCanBeChanged() = runTest(mainDispatcher.dispatcher) {
        val viewModel = viewModel()
        assertEquals(4, viewModel.uiState.value.done.servings)
        viewModel.onAction(CookAction.IncreaseLogServings)
        assertEquals(5, viewModel.uiState.value.done.servings)
        repeat(10) { viewModel.onAction(CookAction.DecreaseLogServings) }
        assertEquals(1, viewModel.uiState.value.done.servings)
    }

    @Test
    fun doneOfflineSaysItIsSentLaterAndClearsFinishedTimers() = runTest(mainDispatcher.dispatcher) {
        sync.setStatus(SyncStatus.Idle.copy(pause = SyncPause.OFFLINE))
        val viewModel = viewModel()
        viewModel.onAction(CookAction.StartTimer(0, viewModel.uiState.value.steps[0].suggestions.single()))
        runCurrent()
        timers.advanceTimeBy(5.minutes)
        viewModel.onAction(CookAction.StopRinging(timers.timers.value.single().id))
        runCurrent()
        assertTrue(viewModel.uiState.value.offline)

        viewModel.effects.test {
            viewModel.onAction(CookAction.SaveCookLog)
            runCurrent()
            assertEquals(CookEffect.Finished(R.string.cook_done_saved_offline), awaitItem())
        }
        assertEquals(1, cookLog.logged.size)
        assertTrue(timers.timers.value.isEmpty())
    }

    @Test
    fun doneInDemoModeSaysItStaysOnTheDevice() = runTest(mainDispatcher.dispatcher) {
        settings.state.value = AppSettings.Default.copy(demoMode = true)
        val viewModel = viewModel()

        viewModel.effects.test {
            viewModel.onAction(CookAction.SaveCookLog)
            runCurrent()
            assertEquals(CookEffect.Finished(R.string.cook_done_saved_demo), awaitItem())
        }
    }

    @Test
    fun aFailedSaveCanBeTriedAgain() = runTest(mainDispatcher.dispatcher) {
        cookLog.result = Outcome.Failure(TandemError.Unknown("disk full"))
        val viewModel = viewModel()

        viewModel.effects.test {
            viewModel.onAction(CookAction.SaveCookLog)
            runCurrent()
            assertEquals(CookEffect.Message(R.string.cook_done_save_failed), awaitItem())
            assertFalse(viewModel.uiState.value.done.saving)

            cookLog.result = Outcome.Success(Unit)
            viewModel.onAction(CookAction.SaveCookLog)
            runCurrent()
            assertEquals(CookEffect.Finished(R.string.cook_done_saved), awaitItem())
        }
        assertEquals(1, cookLog.logged.size)
    }

    @Test
    fun everythingSurvivesProcessDeath() = runTest(mainDispatcher.dispatcher) {
        val handle = SavedStateHandle()
        val first = viewModel(handle = handle)
        first.onAction(CookAction.GoToPage(2))
        first.onAction(CookAction.IncreaseServings)
        first.onAction(CookAction.ToggleIngredients)
        first.onAction(CookAction.ToggleIngredient(10131))
        first.onAction(CookAction.SwitchUnit(10131))
        first.onAction(CookAction.SetRating(5))
        first.onAction(CookAction.SetComment("Gott"))
        timers.setCapabilities(TimerCapabilities.FreshInstall)
        first.onAction(CookAction.StartTimer(1, first.uiState.value.steps[1].suggestions.single()))
        runCurrent()

        // The system keeps only what the SavedStateHandle holds (Bundle types) and the key.
        val restored = SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })
        val second = viewModel(handle = restored).uiState.value

        assertEquals(2, second.page)
        assertEquals(5.0, second.servings?.current)
        assertFalse(second.ingredientsExpanded)
        assertEquals(setOf(10131L), second.struckIngredientIds)
        assertEquals(mapOf(10131L to 1), second.unitChoices)
        assertEquals(5, second.done.rating)
        assertEquals("Gott", second.done.comment)
        assertEquals(1, second.notificationPrompt?.stepIndex)
    }

    @Test
    fun withoutAStoredRecipeTheErrorIsShown() = runTest(mainDispatcher.dispatcher) {
        repository.detailOverrides.value = mapOf(
            pasta.id to RecipeDetailState(recipeId = pasta.id, error = TandemError.NetworkUnavailable),
        )

        val viewModel = viewModel()
        assertFalse(viewModel.uiState.value.hasDetail)
        assertEquals(TandemError.NetworkUnavailable, viewModel.uiState.value.error)

        viewModel.onAction(CookAction.Retry)
        runCurrent()
        assertEquals(listOf(pasta.id), repository.refreshedIds)
    }

    @Test
    fun aRecipeWithoutStepsGetsOnePageWithAllIngredients() = runTest(mainDispatcher.dispatcher) {
        val flat = Recipe(id = 901, name = "Sallad", servings = 2, steps = emptyList())
        repository.details.value = repository.details.value + (flat.id to flat)

        val state = viewModel(CookKey(flat.id)).uiState.value

        assertEquals(1, state.steps.size)
        assertEquals(2, state.pageCount)
    }

    @Test
    fun pendingTimersRoundTripThroughText() {
        val pending = PendingTimer(stepIndex = 2, label = "Pastan · 8–10 min | al dente", durationSeconds = 600)
        assertEquals(pending, PendingTimer.decode(pending.encode()))
        assertNull(PendingTimer.decode("garbage"))
        assertNull(PendingTimer.decode("-1|600|x"))
        assertNull(PendingTimer.decode("1|0|x"))
    }

    @Test
    fun timerLabelsNameTheStepOrTheRecipe() {
        val suggestion = SampleRecipesSuggestion
        assertEquals("Svampen · 5 min", CookViewModel.timerLabel("Svampen", "Pasta", suggestion))
        assertEquals("Pasta · 5 min", CookViewModel.timerLabel("", "Pasta", suggestion))
        assertEquals("5 min", CookViewModel.timerLabel(" ", "", suggestion))
    }

    private companion object {
        val SampleRecipesSuggestion = TimerSuggestion(
            label = "5 min",
            duration = 5.minutes,
            source = TimerSuggestionSource.STEP_TIME,
        )
    }

    // --- fix-ui (0.2.0 review) ---

    @Test
    fun durationsAreFoundInTheVisibleTextNotInTheMarkdown() = runTest(mainDispatcher.dispatcher) {
        val markdown = pasta.copy(
            steps = listOf(
                pasta.steps[0].copy(instruction = "Koka **10** minuter.", timeMinutes = 0),
                pasta.steps[1].copy(instruction = "Koka i 10\nminuter.", timeMinutes = 0),
            ),
        )
        repository.details.value = repository.details.value + (pasta.id to markdown)
        val steps = viewModel().uiState.value.steps

        // "**10** min": no unit right after the number in the source, but on screen there is.
        assertEquals(listOf(10.minutes), steps[0].suggestions.map { it.duration })
        // A soft line break inside the duration: found, and the label has no line break.
        val acrossTheBreak = steps[1].suggestions.single()
        assertEquals(10.minutes, acrossTheBreak.duration)
        assertFalse(acrossTheBreak.label, acrossTheBreak.label.contains('\n'))
    }

    @Test
    fun theStepsPhotoReachesCookMode() = runTest(mainDispatcher.dispatcher) {
        val photo = "https://example.com/media/step.jpg"
        val withPhoto = pasta.copy(steps = pasta.steps.mapIndexed { i, step -> if (i == 1) step.copy(imageUrl = photo) else step })
        repository.details.value = repository.details.value + (pasta.id to withPhoto)
        assertEquals(listOf(null, photo, null), viewModel().uiState.value.steps.map { it.imageUrl })
    }

    @Test
    fun doneSaysTheEntryWaitsWhileTheSyncIsHeldBack() = runTest(mainDispatcher.dispatcher) {
        val cases = mapOf(
            SyncPause.SAFE_MODE to CookLogSendState.SAFE_MODE,
            SyncPause.UNAUTHORIZED to CookLogSendState.NEEDS_LOGIN,
            SyncPause.BLOCKED to CookLogSendState.HELD,
            SyncPause.CIRCUIT_BREAKER to CookLogSendState.HELD,
            SyncPause.OFFLINE to CookLogSendState.OFFLINE,
        )
        cases.forEach { (pause, expected) ->
            sync.setStatus(SyncStatus.Idle.copy(pause = pause))
            assertEquals(pause.name, expected, viewModel().uiState.value.sendState)
        }

        sync.setStatus(SyncStatus.Idle.copy(pause = SyncPause.SAFE_MODE))
        val viewModel = viewModel()
        viewModel.effects.test {
            viewModel.onAction(CookAction.SaveCookLog)
            runCurrent()
            assertEquals(CookEffect.Finished(R.string.cook_done_saved_later), awaitItem())
        }
    }

    @Test
    fun aStartedTimerIsAnnouncedAndKnowsItsSource() = runTest(mainDispatcher.dispatcher) {
        settings.state.value = AppSettings.Default.copy(activeAccountId = "account-1")
        val viewModel = viewModel()

        viewModel.announcements.test {
            viewModel.onAction(CookAction.StartTimer(0, viewModel.uiState.value.steps[0].suggestions.single()))
            runCurrent()
            assertEquals(R.string.cook_timer_started, awaitItem().text)
        }
        assertEquals("account-1", timers.timers.value.single().sourceKey)
    }

    @Test
    fun aDemoTimerBelongsToTheDemo() = runTest(mainDispatcher.dispatcher) {
        settings.state.value = AppSettings.Default.copy(demoMode = true, activeAccountId = "account-1")
        val viewModel = viewModel()

        viewModel.onAction(CookAction.StartTimer(0, viewModel.uiState.value.steps[0].suggestions.single()))
        runCurrent()
        assertEquals(TimerSource.DEMO, timers.timers.value.single().sourceKey)
    }
}
