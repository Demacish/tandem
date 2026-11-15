package casa.moller.tandem.feature.shell.assistant

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.account.SpacePermissions
import casa.moller.tandem.core.model.account.SpaceRole
import casa.moller.tandem.core.model.assistant.AssistantSettings
import casa.moller.tandem.core.model.settings.AppLockSettings
import casa.moller.tandem.core.testing.MainDispatcherRule
import casa.moller.tandem.core.testing.data.FakeAppLock
import casa.moller.tandem.core.testing.data.FakeAssistantSettingsRepository
import casa.moller.tandem.core.testing.data.FakeSpacePermissionsRepository
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
 * Fas 5 (funktioner lane, plan §8.7): Mer → Assistenten. The switch is off until chosen; the page
 * stores it through core:data, says when it could not be saved, says with Lås appen on whether the
 * assistant gets an answer while locked, and tells a guest and demo mode what it can do there.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE_TALL)
class AssistantSettingsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @get:Rule
    val main = MainDispatcherRule()

    private fun string(@StringRes id: Int): String = ApplicationProvider.getApplicationContext<Context>().getString(id)

    @Test
    fun theAssistantIsOffUntilChosenAndTheSwitchIsStored() = runTest(main.dispatcher) {
        val settings = FakeAssistantSettingsRepository()
        val lock = FakeAppLock()
        val model = AssistantSettingsViewModel(settings, FakeSpacePermissionsRepository(), lock)
        backgroundScope.launch { model.uiState.collect { } }
        testScheduler.advanceUntilIdle()
        assertTrue(model.uiState.value.loaded)
        assertFalse(model.uiState.value.enabled)
        assertFalse(model.uiState.value.allowWhenLocked)
        assertFalse(model.uiState.value.lockEnabled)

        model.onAction(AssistantSettingsAction.SetEnabled(true))
        testScheduler.advanceUntilIdle()
        assertEquals(AssistantSettings(enabled = true, allowWhenLocked = false), settings.state.value)
        assertTrue(model.uiState.value.enabled)

        // Mer → Lås appen on, and "Tillåt assistenten när appen är låst" chosen there.
        lock.settingsState.value = AppLockSettings(enabled = true)
        settings.state.value = settings.state.value.copy(allowWhenLocked = true)
        testScheduler.advanceUntilIdle()
        assertTrue(model.uiState.value.lockEnabled && model.uiState.value.allowWhenLocked)
    }

    @Test
    fun aSettingThatCannotBeSavedSaysSo() = runTest(main.dispatcher) {
        val settings = FakeAssistantSettingsRepository().apply { failWrites = true }
        val model = AssistantSettingsViewModel(settings, FakeSpacePermissionsRepository(), FakeAppLock())
        model.effect.test {
            model.onAction(AssistantSettingsAction.SetEnabled(true))
            assertEquals(AssistantSettingsEffect.Message(R.string.shell_settings_not_saved), awaitItem())
        }
        assertFalse(settings.state.value.enabled)
    }

    @Test
    fun aGuestAndDemoModeAreShown() = runTest(main.dispatcher) {
        val permissions = FakeSpacePermissionsRepository(SpacePermissions(SpaceRole.GUEST))
        val model = AssistantSettingsViewModel(FakeAssistantSettingsRepository(), permissions, FakeAppLock())
        backgroundScope.launch { model.uiState.collect { } }
        testScheduler.advanceUntilIdle()
        assertTrue(model.uiState.value.guest)
        assertFalse(model.uiState.value.demo)

        permissions.state.value = SpacePermissions(SpaceRole.UNKNOWN, isDemo = true)
        testScheduler.advanceUntilIdle()
        assertFalse("demo mode is never read-only", model.uiState.value.guest)
        assertTrue(model.uiState.value.demo)
    }

    @Test
    fun theWholeRowToggles() {
        val actions = mutableListOf<AssistantSettingsAction>()
        composeRule.setContent { TandemTheme { AssistantSettingsScreen(ASSISTANT_OFF, { actions += it }, onNavigateBack = {}) } }
        val mainSwitch = string(R.string.shell_assistant_switch_title)

        composeRule.onNodeWithText(mainSwitch).assertIsOff().assertIsEnabled().performClick()
        assertEquals(listOf<AssistantSettingsAction>(AssistantSettingsAction.SetEnabled(true)), actions)
        // Without the app lock nothing is said about it.
        composeRule.onNodeWithText(string(R.string.shell_assistant_lock_title)).assertDoesNotExist()
    }

    @Test
    fun withTheLockThePageSaysWhetherTheAssistantGetsThrough() {
        composeRule.setContent { TandemTheme { AssistantSettingsScreen(ASSISTANT_LOCKED, {}, onNavigateBack = {}) } }
        composeRule.onNodeWithText(string(R.string.shell_assistant_switch_title)).assertIsOn()
        composeRule.onNodeWithText(string(R.string.shell_assistant_lock_title)).performScrollTo().assertExists()
        composeRule.onNodeWithText(string(R.string.shell_assistant_lock_blocked_body)).assertExists()
    }

    @Test
    fun belowAndroid16TheSwitchCannotBeUsed() {
        composeRule.setContent { TandemTheme { AssistantSettingsScreen(ASSISTANT_UNSUPPORTED, {}, onNavigateBack = {}) } }
        val mainSwitch = string(R.string.shell_assistant_switch_title)
        composeRule.onNodeWithText(mainSwitch).assertIsNotEnabled()
        composeRule.onNodeWithText(string(R.string.shell_assistant_unsupported_title)).assertExists()
    }
}
