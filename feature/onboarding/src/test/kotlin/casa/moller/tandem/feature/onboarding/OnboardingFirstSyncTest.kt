package casa.moller.tandem.feature.onboarding

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.error.UnauthorizedSource
import casa.moller.tandem.core.model.sync.SyncPhase
import casa.moller.tandem.core.model.sync.SyncProgress
import casa.moller.tandem.core.model.sync.SyncStop
import casa.moller.tandem.core.testing.MainDispatcherRule
import casa.moller.tandem.core.testing.data.FakeSyncRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/** The steps after a login (plan 6.2 "Efter inloggning"): first sync and battery tip. */
class OnboardingFirstSyncTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val sync = FakeSyncRepository()

    private fun connectedViewModel(config: OnboardingConfig = OnboardingConfig(), handle: SavedStateHandle = SavedStateHandle()) =
        viewModel(config = config, sync = sync, handle = handle).apply {
            onGetStarted()
            onServerUrlChange("tandoor.example.com")
            onConnect()
            onUsernameChange("anna")
            onPasswordChange("secret")
            onLogin()
            assertEquals(OnboardingStep.CONNECTED, uiState.value.step)
        }

    @Test
    fun continueAfterLoginRunsTheFirstSyncAndFinishes() = runTest {
        val vm = connectedViewModel()
        vm.events.test {
            vm.onContinueAfterLogin()
            assertEquals(OnboardingEvent.Finished, awaitItem())
        }
        val state = vm.uiState.value
        assertEquals(OnboardingStep.FIRST_SYNC, state.step)
        assertEquals(312, state.firstSync.recipeCount)
        assertEquals(SyncPhase.DONE, state.firstSync.progress?.phase)
        assertEquals(PhaseStatus.DONE, state.firstSync.statusOf(SyncPhase.MEAL_PLAN))
        assertEquals(1f, state.firstSync.overallFraction)
    }

    @Test
    fun progressIsShownPerPhase() {
        sync.firstSyncProgress = listOf(SyncProgress(SyncPhase.SERVER_CHECK), SyncProgress(SyncPhase.RECIPE_INDEX, done = 156, total = 312))
        val vm = connectedViewModel()
        vm.onContinueAfterLogin()
        val firstSync = vm.uiState.value.firstSync
        assertEquals(SyncPhase.RECIPE_INDEX, firstSync.currentPhase)
        assertEquals(312, firstSync.recipeCount)
        assertEquals(PhaseStatus.DONE, firstSync.statusOf(SyncPhase.SERVER_CHECK))
        assertEquals(PhaseStatus.DONE, firstSync.statusOf(SyncPhase.REFERENCE_DATA))
        assertEquals(PhaseStatus.RUNNING, firstSync.statusOf(SyncPhase.RECIPE_INDEX))
        assertEquals(PhaseStatus.WAITING, firstSync.statusOf(SyncPhase.MEAL_PLAN))
        // Index phase is the third of six, half done.
        assertEquals(2.5f / 6f, firstSync.overallFraction!!, 0.001f)
    }

    @Test
    fun failureKeepsTheStepWithTheErrorAndRetryStartsAgain() = runTest {
        sync.firstSyncProgress = FakeSyncRepository.failingFirstSync(TandemError.Timeout)
        val vm = connectedViewModel()
        vm.onContinueAfterLogin()
        val failed = vm.uiState.value
        assertEquals(OnboardingStep.FIRST_SYNC, failed.step)
        assertEquals(TandemError.Timeout, failed.firstSync.error)
        assertEquals(PhaseStatus.FAILED, failed.firstSync.statusOf(SyncPhase.SERVER_CHECK))
        assertNull(failed.firstSync.currentPhase)

        sync.firstSyncProgress = listOf(SyncProgress(SyncPhase.DONE))
        vm.events.test {
            vm.onRetryFirstSync()
            assertEquals(OnboardingEvent.Finished, awaitItem())
        }
        assertNull(vm.uiState.value.firstSync.error)
    }

    @Test
    fun aCancelledOrOvertakenFirstSyncIsNoErrorAndGoesOnInTheBackground() = runTest {
        // komplettering-3: SyncStop markers are no failure ("Try again" would start a needless run).
        listOf(SyncStop.Cancelled, SyncStop.SourceChanged).forEach { stop ->
            sync.firstSyncProgress = FakeSyncRepository.failingFirstSync(stop)
            val vm = connectedViewModel()
            vm.events.test {
                vm.onContinueAfterLogin()
                assertEquals(OnboardingEvent.Finished, awaitItem())
            }
            assertNull(stop.toString(), vm.uiState.value.firstSync.error)
        }
    }

    @Test
    fun aRefusedLoginLeadsBackToTheLoginStepInsteadOfARetry() = runTest {
        sync.firstSyncProgress = FakeSyncRepository.failingFirstSync(TandemError.Unauthorized(UnauthorizedSource.TANDOOR))
        val vm = connectedViewModel()
        vm.onContinueAfterLogin()
        assertEquals(OnboardingStep.FIRST_SYNC, vm.uiState.value.step)

        vm.onLogInAgainAfterFirstSync()
        val state = vm.uiState.value
        assertEquals(OnboardingStep.LOGIN, state.step)
        assertNull(state.firstSync.error)
        assertNull(state.connected)
        assertEquals("anna", state.username)
    }

    @Test
    fun logInAgainIsIgnoredForOtherErrors() = runTest {
        sync.firstSyncProgress = FakeSyncRepository.failingFirstSync(TandemError.Timeout)
        val vm = connectedViewModel()
        vm.onContinueAfterLogin()
        vm.onLogInAgainAfterFirstSync()
        assertEquals(OnboardingStep.FIRST_SYNC, vm.uiState.value.step)
    }

    @Test
    fun continueInTheBackgroundEndsTheIntroWhileTheSyncRuns() = runTest {
        sync.firstSyncProgress = listOf(SyncProgress(SyncPhase.RECIPE_INDEX, done = 10, total = 312))
        val vm = connectedViewModel()
        vm.onContinueAfterLogin()
        assertEquals(OnboardingStep.FIRST_SYNC, vm.uiState.value.step)
        vm.events.test {
            vm.onContinueFirstSyncInBackground()
            assertEquals(OnboardingEvent.Finished, awaitItem())
        }
    }

    @Test
    fun batteryTipComesBeforeTheEndWhenConfigured() = runTest {
        val vm = connectedViewModel(config = OnboardingConfig(showBatteryTip = true))
        vm.events.test {
            vm.onContinueAfterLogin()
            assertEquals(OnboardingStep.BATTERY_TIP, vm.uiState.value.step)
            expectNoEvents()
            vm.onBatteryTipDone()
            assertEquals(OnboardingEvent.Finished, awaitItem())
        }
    }

    @Test
    fun actionsOutOfOrderAreIgnored() = runTest {
        val vm = viewModel(sync = sync)
        vm.events.test {
            vm.onContinueAfterLogin()
            vm.onRetryFirstSync()
            vm.onContinueFirstSyncInBackground()
            vm.onBatteryTipDone()
            expectNoEvents()
        }
        assertEquals(OnboardingStep.WELCOME, vm.uiState.value.step)
    }

    @Test
    fun theFirstSyncStepSurvivesProcessDeath() = runTest {
        val handle = SavedStateHandle()
        sync.firstSyncProgress = listOf(SyncProgress(SyncPhase.RECIPE_INDEX, done = 10, total = 312))
        connectedViewModel(handle = handle).onContinueAfterLogin()

        sync.firstSyncProgress = listOf(SyncProgress(SyncPhase.MEAL_PLAN))
        val restored = viewModel(sync = sync, handle = SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) }))
        assertEquals(OnboardingStep.FIRST_SYNC, restored.uiState.value.step)
        assertEquals(SyncPhase.MEAL_PLAN, restored.uiState.value.firstSync.currentPhase)
    }
}
