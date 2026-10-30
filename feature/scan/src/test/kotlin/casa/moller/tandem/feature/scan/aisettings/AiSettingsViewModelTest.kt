package casa.moller.tandem.feature.scan.aisettings

import casa.moller.tandem.core.model.account.SpacePermissions
import casa.moller.tandem.core.model.account.SpaceRole
import casa.moller.tandem.core.model.ai.AiKeyCheck
import casa.moller.tandem.core.model.ai.AiProvider
import casa.moller.tandem.core.model.ai.AiSettings
import casa.moller.tandem.core.testing.MainDispatcherRule
import casa.moller.tandem.core.testing.data.FakeAiSettingsRepository
import casa.moller.tandem.core.testing.data.FakeFridgeVision
import casa.moller.tandem.core.testing.data.FakeSpacePermissionsRepository
import casa.moller.tandem.feature.scan.R
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Settings → AI: the key is write-only (never in the state or an action's text), removal, the test, the fields. */
class AiSettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val ai = FakeAiSettingsRepository()
    private val vision = FakeFridgeVision()
    private val permissions = FakeSpacePermissionsRepository()

    private fun viewModel() = AiSettingsViewModel(settings = ai, vision = vision, permissions = permissions)

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun TestScope.open(vm: AiSettingsViewModel): MutableList<AiSettingsEffect> {
        val effects = mutableListOf<AiSettingsEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.toList(effects) }
        return effects
    }

    @Test
    fun aSavedKeyIsNeverInTheStateAndTheFieldIsCleared() = runTest {
        val vm = viewModel()
        val effects = open(vm)
        vm.onAction(AiSettingsAction.SelectProvider(AiProvider.CLAUDE))
        assertEquals(AiProvider.CLAUDE, vm.uiState.value.provider)
        assertFalse(vm.uiState.value.hasKey)

        val action = AiSettingsAction.SaveKey(KEY)
        assertFalse(action.toString().contains(KEY))
        vm.onAction(action)

        assertEquals(KEY, ai.keys[AiProvider.CLAUDE])
        assertTrue(vm.uiState.value.hasKey)
        assertFalse(vm.uiState.value.toString().contains(KEY))
        assertTrue(AiSettingsEffect.KeyStored in effects)
        assertTrue(AiSettingsEffect.Message(R.string.ai_key_saved) in effects)
    }

    @Test
    fun anEmptyKeyIsAnErrorAndNotStored() = runTest {
        val vm = viewModel()
        val effects = open(vm)
        vm.onAction(AiSettingsAction.SelectProvider(AiProvider.OPENAI))
        vm.onAction(AiSettingsAction.SaveKey("   "))
        assertTrue(ai.keys.isEmpty())
        // Fas S komplettering: the fake answers core:ai's own code (AiSettingsProblem.KEY_EMPTY), so
        // the field says "Paste the key first", not a storage failure.
        assertEquals(AiKeyError.EMPTY, vm.uiState.value.keyError)
        assertFalse(AiSettingsEffect.KeyStored in effects)
        vm.onAction(AiSettingsAction.KeyEdited)
        assertNull(vm.uiState.value.keyError)
    }

    @Test
    fun removingTheKeyAsksFirst() = runTest {
        val vm = viewModel()
        open(vm)
        vm.onAction(AiSettingsAction.SelectProvider(AiProvider.GEMINI))
        vm.onAction(AiSettingsAction.SaveKey(KEY))
        vm.onAction(AiSettingsAction.RemoveKey)
        assertTrue(vm.uiState.value.confirmRemove)
        assertEquals(KEY, ai.keys[AiProvider.GEMINI])

        vm.onAction(AiSettingsAction.ConfirmRemoveKey)
        assertFalse(vm.uiState.value.confirmRemove)
        assertFalse(vm.uiState.value.hasKey)
        assertTrue(ai.keys.isEmpty())
    }

    @Test
    fun theKeyTestShowsTheResultAndIsOffInDemoMode() = runTest {
        val vm = viewModel()
        open(vm)
        assertFalse(vm.uiState.value.canCheck)
        vm.onAction(AiSettingsAction.SelectProvider(AiProvider.CLAUDE))
        vm.onAction(AiSettingsAction.SaveKey(KEY))
        vision.keyCheck = AiKeyCheck.INVALID_KEY
        vm.onAction(AiSettingsAction.CheckKey)
        assertEquals(AiKeyCheck.INVALID_KEY, vm.uiState.value.check)
        assertEquals(1, vision.keyChecks)

        permissions.state.value = SpacePermissions(SpaceRole.USER, isDemo = true)
        assertTrue(vm.uiState.value.demo)
        assertFalse(vm.uiState.value.canCheck)
        vm.onAction(AiSettingsAction.CheckKey)
        assertEquals(1, vision.keyChecks)
    }

    @Test
    fun modelAndBaseUrlAreSavedTogetherAndCanBeReset() = runTest {
        val vm = viewModel()
        open(vm)
        vm.onAction(AiSettingsAction.SelectProvider(AiProvider.OPENAI))
        assertEquals("gpt-5-nano", vm.uiState.value.defaultModel)
        vm.onAction(AiSettingsAction.EditModel("gpt-custom"))
        vm.onAction(AiSettingsAction.EditBaseUrl("http://proxy.example.com"))
        assertTrue(vm.uiState.value.advancedChanged)
        vm.onAction(AiSettingsAction.SaveAdvanced)
        assertTrue(vm.uiState.value.baseUrlError)
        assertEquals("gpt-custom", ai.settings.first().model)

        vm.onAction(AiSettingsAction.EditBaseUrl("https://proxy.example.com"))
        vm.onAction(AiSettingsAction.SaveAdvanced)
        assertFalse(vm.uiState.value.baseUrlError)
        assertEquals("https://proxy.example.com", ai.settings.first().customBaseUrl)

        vm.onAction(AiSettingsAction.ResetAdvanced)
        assertEquals(AiSettings(provider = AiProvider.OPENAI), ai.settings.first())
        assertEquals("", vm.uiState.value.model)
    }

    private companion object {
        const val KEY = "sk-test-not-a-real-key"
    }
}
