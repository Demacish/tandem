package casa.moller.tandem.feature.shell.lock

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.assistant.AssistantSettings
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.settings.AppLockSettings
import casa.moller.tandem.core.model.settings.AppLockStatus
import casa.moller.tandem.core.model.settings.AppLockTimeout
import casa.moller.tandem.core.testing.MainDispatcherRule
import casa.moller.tandem.core.testing.data.FakeAppLock
import casa.moller.tandem.core.testing.data.FakeAssistantSettingsRepository
import casa.moller.tandem.core.ui.security.DeviceAuthResult
import casa.moller.tandem.feature.shell.R
import casa.moller.tandem.feature.shell.settings.PHONE_TALL
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Fas 5 (las lane, plan §5.5): Mer → Lås appen. Off by default; turning it on confirms the screen
 * lock first (a closed prompt leaves it off); without a screen lock it cannot be turned on and the
 * page offers the system settings; "Lås efter" is one tap.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE_TALL)
class AppLockSettingsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @get:Rule
    val main = MainDispatcherRule()

    @Test
    fun theLockIsOffUntilChosen() = runTest(main.dispatcher) {
        val model = AppLockSettingsViewModel(FakeAppLock(), FakeAssistantSettingsRepository())
        backgroundScope.launch { model.uiState.collect { } }
        testScheduler.advanceUntilIdle()
        assertFalse(model.uiState.value.enabled)
        assertEquals(AppLockTimeout.ONE_MINUTE, model.uiState.value.timeout)
        assertTrue(model.uiState.value.switchEnabled)
    }

    @Test
    fun turningItOnConfirmsTheScreenLockFirst() = runTest(main.dispatcher) {
        val lock = FakeAppLock()
        val model = AppLockSettingsViewModel(lock, FakeAssistantSettingsRepository())
        backgroundScope.launch { model.uiState.collect { } }
        model.effect.test {
            model.onAction(AppLockSettingsAction.SetEnabled(true))
            assertEquals(AppLockSettingsEffect.ConfirmScreenLock, awaitItem())
            assertTrue(model.uiState.value.confirming)
            assertFalse("nothing is stored before the screen lock is confirmed", lock.settingsState.value.enabled)

            // A second tap while the prompt is open asks nothing more.
            model.onAction(AppLockSettingsAction.SetEnabled(true))
            expectNoEvents()

            model.onAction(AppLockSettingsAction.Confirmed(DeviceAuthResult.Cancelled))
            testScheduler.advanceUntilIdle()
            assertFalse(lock.settingsState.value.enabled)

            model.onAction(AppLockSettingsAction.SetEnabled(true))
            assertEquals(AppLockSettingsEffect.ConfirmScreenLock, awaitItem())
            model.onAction(AppLockSettingsAction.Confirmed(DeviceAuthResult.Succeeded))
            testScheduler.advanceUntilIdle()
            assertTrue(lock.settingsState.value.enabled)
            // Turned on in the app: the app stays open.
            assertEquals(AppLockStatus.UNLOCKED, lock.status.value)

            model.onAction(AppLockSettingsAction.SetEnabled(false))
            testScheduler.advanceUntilIdle()
            assertFalse(lock.settingsState.value.enabled)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun withoutAScreenLockItCannotBeTurnedOn() = runTest(main.dispatcher) {
        val model = AppLockSettingsViewModel(FakeAppLock(), FakeAssistantSettingsRepository())
        backgroundScope.launch { model.uiState.collect { } }
        model.effect.test {
            model.onAction(AppLockSettingsAction.ScreenLockChecked(available = false))
            testScheduler.advanceUntilIdle()
            assertFalse(model.uiState.value.switchEnabled)
            model.onAction(AppLockSettingsAction.SetEnabled(true))
            expectNoEvents()
            model.onAction(AppLockSettingsAction.OpenScreenLockSettings)
            assertEquals(AppLockSettingsEffect.OpenScreenLockSettings, awaitItem())
            // Back from the settings with a screen lock.
            model.onAction(AppLockSettingsAction.ScreenLockChecked(available = true))
            testScheduler.advanceUntilIdle()
            assertTrue(model.uiState.value.switchEnabled)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun aFailedPromptOrWriteIsReported() = runTest(main.dispatcher) {
        val lock = FakeAppLock()
        val model = AppLockSettingsViewModel(lock, FakeAssistantSettingsRepository())
        backgroundScope.launch { model.uiState.collect { } }
        model.effect.test {
            model.onAction(AppLockSettingsAction.SetEnabled(true))
            assertEquals(AppLockSettingsEffect.ConfirmScreenLock, awaitItem())
            model.onAction(AppLockSettingsAction.Confirmed(DeviceAuthResult.Failed("Too many attempts.")))
            assertEquals(AppLockSettingsEffect.SystemMessage("Too many attempts."), awaitItem())
            model.onAction(AppLockSettingsAction.SetEnabled(true))
            assertEquals(AppLockSettingsEffect.ConfirmScreenLock, awaitItem())
            model.onAction(AppLockSettingsAction.Confirmed(DeviceAuthResult.Failed(null)))
            assertEquals(AppLockSettingsEffect.Message(R.string.shell_lock_confirm_failed), awaitItem())

            lock.failWrites = Outcome.Failure(TandemError.Unknown("tandem.data.settings-not-saved"))
            model.onAction(AppLockSettingsAction.SetTimeout(AppLockTimeout.FIVE_MINUTES))
            assertEquals(AppLockSettingsEffect.Message(R.string.shell_settings_not_saved), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun theTimeoutIsOneTapAndTheSwitchTurnsTheLockOff() {
        val actions = mutableListOf<AppLockSettingsAction>()
        composeRule.setContent { TandemTheme { AppLockSettingsScreen(LOCK_ON, { actions += it }, {}) } }
        composeRule.onNodeWithText("After 5 minutes").performScrollTo().performClick()
        composeRule.onNodeWithText("Ask for the screen lock").assertIsOn().performClick()
        assertEquals(
            listOf(AppLockSettingsAction.SetTimeout(AppLockTimeout.FIVE_MINUTES), AppLockSettingsAction.SetEnabled(false)),
            actions,
        )
    }

    @Test
    fun withoutAScreenLockThePageOffersTheSettings() {
        val actions = mutableListOf<AppLockSettingsAction>()
        composeRule.setContent { TandemTheme { AppLockSettingsScreen(LOCK_NO_SCREEN_LOCK, { actions += it }, {}) } }
        composeRule.onNodeWithText("Ask for the screen lock").assertIsNotEnabled()
        composeRule.onNodeWithText("Set up screen lock").performScrollTo().performClick()
        // The timeouts only show with the lock on.
        composeRule.onNodeWithText("After 5 minutes").assertDoesNotExist()
        assertEquals(listOf<AppLockSettingsAction>(AppLockSettingsAction.OpenScreenLockSettings), actions)
    }

    @Test
    fun theMoreRowSaysWhetherTheLockIsOn() {
        composeRule.setContent { TandemTheme { AppLockMoreSection(LOCK_ON_IMMEDIATELY, selected = false, onOpen = {}) } }
        composeRule.onNodeWithText("On · locks immediately").assertExists()
        composeRule.onNodeWithText("Security").assertExists()
    }

    // --- Fas 5 (funktioner lane): "Tillåt assistenten när appen är låst" next to the lock ---

    @Test
    fun theAssistantSwitchIsStoredAndOnlyShownWithTheLockAndTheAssistantOn() = runTest(main.dispatcher) {
        val assistant = FakeAssistantSettingsRepository(AssistantSettings(enabled = true))
        val model = AppLockSettingsViewModel(FakeAppLock(AppLockSettings(enabled = true)), assistant)
        backgroundScope.launch { model.uiState.collect { } }
        testScheduler.advanceUntilIdle()
        assertTrue(model.uiState.value.assistantEnabled)
        assertFalse("off until chosen", model.uiState.value.assistantWhenLocked)
        model.onAction(AppLockSettingsAction.SetAssistantWhenLocked(true))
        testScheduler.advanceUntilIdle()
        assertTrue(assistant.state.value.allowWhenLocked)
        assertTrue(model.uiState.value.assistantWhenLocked)
    }

    @Test
    fun theAssistantSwitchSitsNextToTheLock() {
        val actions = mutableListOf<AppLockSettingsAction>()
        composeRule.setContent { TandemTheme { AppLockSettingsScreen(LOCK_ON_ASSISTANT, { actions += it }, {}) } }
        composeRule.onNodeWithText(ASSISTANT_WHEN_LOCKED).performScrollTo().assertIsOff().performClick()
        assertEquals(listOf<AppLockSettingsAction>(AppLockSettingsAction.SetAssistantWhenLocked(true)), actions)
    }

    @Test
    fun withoutTheAssistantThereIsNoAssistantSwitch() {
        composeRule.setContent { TandemTheme { AppLockSettingsScreen(LOCK_ON, {}, {}) } }
        composeRule.onNodeWithText(ASSISTANT_WHEN_LOCKED).assertDoesNotExist()
    }

    @Test
    fun theMoreRowSaysOff() {
        composeRule.setContent { TandemTheme { AppLockMoreSection(LOCK_OFF, selected = false, onOpen = {}) } }
        composeRule.onNodeWithText("Off").assertExists()
    }

    private companion object {
        /** feature:shell `shell_assistant_locked_title`. */
        const val ASSISTANT_WHEN_LOCKED = "Allow the assistant when the app is locked"
    }
}
