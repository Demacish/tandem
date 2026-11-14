package casa.moller.tandem.feature.cook

import androidx.lifecycle.SavedStateHandle
import casa.moller.tandem.core.domain.format.NumberStyle
import casa.moller.tandem.core.testing.MainDispatcherRule
import casa.moller.tandem.core.testing.data.FakeCookLogRepository
import casa.moller.tandem.core.testing.data.FakePanSizeRepository
import casa.moller.tandem.core.testing.data.FakePreferencesRepository
import casa.moller.tandem.core.testing.data.FakeRecipeRepository
import casa.moller.tandem.core.testing.data.FakeSettingsRepository
import casa.moller.tandem.core.testing.data.FakeSpacePermissionsRepository
import casa.moller.tandem.core.testing.data.FakeSyncRepository
import casa.moller.tandem.core.testing.data.FakeTimerController
import casa.moller.tandem.core.testing.data.SampleRecipes
import casa.moller.tandem.core.testing.time.TestClock
import casa.moller.tandem.feature.cook.navigation.CookKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Device test 2026-11-14 (B4): page turns that come faster than the screen state follows. Main is a
 * [StandardTestDispatcher] here, so [CookViewModel.uiState] lags behind every action until
 * `runCurrent()`, as on a phone where two volume key presses arrive within one frame.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CookViewModelQuickPagesTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule(StandardTestDispatcher())

    private val clock = TestClock()
    private val pasta = SampleRecipes.MushroomPasta

    private fun TestScope.viewModel(key: CookKey = CookKey(pasta.id)): CookViewModel {
        val viewModel = CookViewModel(
            key,
            SavedStateHandle(),
            FakeRecipeRepository(),
            FakePreferencesRepository(),
            FakeSettingsRepository(),
            FakeTimerController(clock),
            FakeCookLogRepository(),
            FakeSyncRepository(),
            clock,
            FakeSpacePermissionsRepository(),
            FakePanSizeRepository(),
        )
        viewModel.onAction(CookAction.SetLocale(NumberStyle.English, "en"))
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()
        return viewModel
    }

    @Test
    fun twoNextBeforeTheStateFollowsTurnTwoPages() = runTest(mainDispatcher.dispatcher) {
        val viewModel = viewModel()
        assertEquals(0, viewModel.uiState.value.page)

        viewModel.onAction(CookAction.Next)
        viewModel.onAction(CookAction.Next)
        runCurrent()

        assertEquals(2, viewModel.uiState.value.page)
    }

    @Test
    fun quickTurnsBothWaysCountEveryOne() = runTest(mainDispatcher.dispatcher) {
        val viewModel = viewModel()

        repeat(3) { viewModel.onAction(CookAction.Next) }
        viewModel.onAction(CookAction.Previous)
        runCurrent()
        assertEquals(2, viewModel.uiState.value.page)

        // Still clamped to the pages: never past "Klart!", never before step 1.
        repeat(9) { viewModel.onAction(CookAction.Next) }
        runCurrent()
        assertEquals(3, viewModel.uiState.value.page)
        repeat(9) { viewModel.onAction(CookAction.Previous) }
        runCurrent()
        assertEquals(0, viewModel.uiState.value.page)
    }

    @Test
    fun previousFromAStepThatNoLongerExistsGoesToTheLastStep() = runTest(mainDispatcher.dispatcher) {
        // The key's step 10 is shown as the "Klart!" page (3); one back is step 3 (2), not 9.
        val viewModel = viewModel(CookKey(pasta.id, startStep = 10))
        assertEquals(3, viewModel.uiState.value.page)

        viewModel.onAction(CookAction.Previous)
        runCurrent()

        assertEquals(2, viewModel.uiState.value.page)
    }
}
