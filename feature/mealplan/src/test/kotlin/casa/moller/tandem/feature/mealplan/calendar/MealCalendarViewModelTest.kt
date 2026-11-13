package casa.moller.tandem.feature.mealplan.calendar

import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.testing.MainDispatcherRule
import casa.moller.tandem.feature.mealplan.R
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Fas 5 (kalender lane, plan §7 #36): the phone's calendar asks for the calendar permission in
 * context and only then turns on; a no leaves it off with the way to the settings; "Ny länk" and
 * "Stäng av" wait for a yes; failures say why (offline, guest).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MealCalendarViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeMealCalendarRepository()

    @Test
    fun turningOnWithoutPermissionAsksFirstAndANoLeavesItOff() = runTest {
        repository.permission = false
        val viewModel = MealCalendarViewModel(repository)
        val effects = mutableListOf<MealCalendarEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.effect.toList(effects) }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }

        viewModel.onAction(MealCalendarAction.SetLocal(true))
        advanceUntilIdle()
        assertEquals(listOf<MealCalendarEffect>(MealCalendarEffect.RequestPermission), effects)
        assertTrue(repository.calls.isEmpty())

        viewModel.onAction(MealCalendarAction.PermissionResult(granted = false))
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.permissionDenied)
        assertFalse(viewModel.uiState.value.local.enabled)
        assertEquals(MealCalendarEffect.Message(R.string.calendar_msg_permission_denied), effects.last())

        repository.permission = true
        viewModel.onAction(MealCalendarAction.PermissionResult(granted = true))
        advanceUntilIdle()
        assertEquals(listOf("local true"), repository.calls)
        assertTrue(viewModel.uiState.value.local.enabled)
        assertFalse(viewModel.uiState.value.permissionDenied)
        assertEquals(MealCalendarEffect.Message(R.string.calendar_msg_local_on, R.string.calendar_local_name), effects.last())
    }

    @Test
    fun onOffAndRefresh() = runTest {
        val viewModel = MealCalendarViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        viewModel.onAction(MealCalendarAction.SetLocal(true))
        advanceUntilIdle()
        viewModel.onAction(MealCalendarAction.RefreshLocal)
        advanceUntilIdle()
        viewModel.onAction(MealCalendarAction.SetLocal(false))
        advanceUntilIdle()
        assertEquals(listOf("local true", "refresh", "local false"), repository.calls)
        assertFalse(viewModel.uiState.value.local.enabled)
        assertNull(viewModel.uiState.value.busy)
    }

    @Test
    fun replacingAndTurningOffTheLinkWaitForAYes() = runTest {
        val viewModel = MealCalendarViewModel(repository)
        val effects = mutableListOf<MealCalendarEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.effect.toList(effects) }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        viewModel.onAction(MealCalendarAction.CreateLink)
        advanceUntilIdle()
        assertEquals(MealCalendarSamples.URL, viewModel.uiState.value.link.url)

        viewModel.onAction(MealCalendarAction.AskReplace)
        advanceUntilIdle()
        assertEquals(LinkConfirm.REPLACE, viewModel.uiState.value.confirm)
        viewModel.onAction(MealCalendarAction.Dismiss)
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.confirm)
        assertEquals(listOf("enable"), repository.calls)

        viewModel.onAction(MealCalendarAction.AskReplace)
        viewModel.onAction(MealCalendarAction.Confirm)
        advanceUntilIdle()
        viewModel.onAction(MealCalendarAction.AskDisable)
        viewModel.onAction(MealCalendarAction.Confirm)
        advanceUntilIdle()
        assertEquals(listOf("enable", "replace", "disable"), repository.calls)
        assertNull(viewModel.uiState.value.link.url)
        assertEquals(
            listOf(R.string.calendar_msg_link_created, R.string.calendar_msg_link_replaced, R.string.calendar_msg_link_disabled),
            effects.filterIsInstance<MealCalendarEffect.Message>().map { it.text },
        )
    }

    @Test
    fun failuresSayWhy() = runTest {
        val viewModel = MealCalendarViewModel(repository)
        val effects = mutableListOf<MealCalendarEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.effect.toList(effects) }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        repository.linkFailure = TandemError.NetworkUnavailable
        viewModel.onAction(MealCalendarAction.CreateLink)
        advanceUntilIdle()
        repository.linkFailure = MealCalendarSamples.readOnly
        viewModel.onAction(MealCalendarAction.CreateLink)
        advanceUntilIdle()
        repository.linkFailure = TandemError.Unknown("x")
        viewModel.onAction(MealCalendarAction.CreateLink)
        advanceUntilIdle()
        assertEquals(
            listOf(R.string.calendar_msg_offline, R.string.calendar_msg_guest, R.string.calendar_msg_failed),
            effects.filterIsInstance<MealCalendarEffect.Message>().map { it.text },
        )
        assertNull(viewModel.uiState.value.link.url)
    }
}
