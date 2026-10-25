package casa.moller.tandem.feature.mealplan

import androidx.lifecycle.SavedStateHandle
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.mealplan.MealPlanConflict
import casa.moller.tandem.core.model.sync.ForegroundTarget
import casa.moller.tandem.core.testing.MainDispatcherRule
import casa.moller.tandem.core.testing.data.FakeForegroundSync
import casa.moller.tandem.core.testing.data.FakeMealPlanRepository
import casa.moller.tandem.core.testing.data.FakeSpacePermissionsRepository
import casa.moller.tandem.core.testing.time.TestClock
import casa.moller.tandem.feature.mealplan.MealPlanSamples.day
import casa.moller.tandem.feature.mealplan.MealPlanSamples.dinner
import casa.moller.tandem.feature.mealplan.MealPlanSamples.lunch
import casa.moller.tandem.feature.mealplan.MealPlanSamples.monday
import casa.moller.tandem.feature.mealplan.MealPlanSamples.todayDinner
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class WeekViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val clock = TestClock(MealPlanSamples.now)
    private val mealPlans = FakeMealPlanRepository(meals = MealPlanSamples.meals, mealTypes = MealPlanSamples.mealTypes)
    private val permissions = FakeSpacePermissionsRepository()
    private val foreground = FakeForegroundSync()

    private fun viewModel(weekStart: String? = null, saved: SavedStateHandle = SavedStateHandle()) =
        WeekViewModel(weekStart, saved, mealPlans, permissions, foreground, clock)

    /** Starts collecting the state (as the screen does) and the effects. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun TestScope.open(vm: WeekViewModel): MutableList<WeekEffect> {
        val effects = mutableListOf<WeekEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.toList(effects) }
        return effects
    }

    @Test
    fun opensOnThisWeekWithTodayAndKeepsItFresh() = runTest {
        val vm = viewModel()
        open(vm)
        val state = vm.uiState.value
        assertFalse(state.loading)
        assertEquals(MealPlanSamples.weekStart, state.weekStart)
        assertEquals(MealPlanSamples.today, state.today)
        assertTrue(state.isThisWeek)
        assertEquals(7, state.days.size)
        assertEquals(listOf("Frukost", "Middag"), state.days[3].meals.map { it.mealType.name })
        assertEquals(6, state.plannedDays)
        assertEquals(MealPlanSamples.today, state.shownDay)
        assertEquals(listOf<ForegroundTarget>(ForegroundTarget.MealPlan(MealPlanSamples.weekStart)), foreground.started)
    }

    @Test
    fun anyDayOfTheKeyOpensItsWeek() = runTest {
        val vm = viewModel(weekStart = "2026-10-28")
        open(vm)
        assertEquals(LocalDate(2026, 10, 26), vm.uiState.value.weekStart)
        assertFalse(vm.uiState.value.isThisWeek)
    }

    @Test
    fun previousNextAndBackToToday() = runTest {
        val saved = SavedStateHandle()
        val vm = viewModel(saved = saved)
        open(vm)
        vm.onAction(WeekAction.NextWeek)
        assertEquals(day(7), vm.uiState.value.weekStart)
        assertTrue(ForegroundTarget.MealPlan(day(7)) in foreground.started)
        vm.onAction(WeekAction.PreviousWeek)
        vm.onAction(WeekAction.PreviousWeek)
        assertEquals(day(-7), vm.uiState.value.weekStart)
        vm.onAction(WeekAction.ThisWeek)
        assertEquals(MealPlanSamples.weekStart, vm.uiState.value.weekStart)
        assertEquals(MealPlanSamples.today, vm.uiState.value.selectedDay)
        // The shown week survives process death.
        vm.onAction(WeekAction.NextWeek)
        val restored = viewModel(saved = saved)
        open(restored)
        assertEquals(day(7), restored.uiState.value.weekStart)
    }

    @Test
    fun removingAMealCanBeUndone() = runTest {
        val vm = viewModel()
        val effects = open(vm)
        vm.onAction(WeekAction.Delete(todayDinner))
        assertTrue(vm.uiState.value.days[3].meals.none { it.id == todayDinner.id })
        assertEquals(WeekEffect.Show(WeekMessage.Removed(todayDinner.displayTitle)), effects.single())
        vm.onAction(WeekAction.Undo)
        val again = mealPlans.drafts.single()
        assertEquals(todayDinner.toDraft(), again)
        assertEquals(todayDinner.recipe?.id, again.recipeId)
        assertEquals("Dubbel sats, frys hälften", again.note)
        assertEquals(1, vm.uiState.value.days[3].meals.count { it.recipe?.id == todayDinner.recipe?.id })
    }

    @Test
    fun movingKeepsTheLengthAndCanBeUndone() = runTest {
        val vm = viewModel()
        val effects = open(vm)
        vm.onAction(WeekAction.StartMove(monday))
        assertEquals(MoveMealState(monday, monday.fromDate, dinner.id), vm.uiState.value.move)
        vm.onAction(WeekAction.MoveDate(day(5)))
        vm.onAction(WeekAction.MoveMealType(lunch.id))
        vm.onAction(WeekAction.ConfirmMove)
        assertNull(vm.uiState.value.move)
        val moved = vm.uiState.value.days[5].meals.single()
        assertEquals(monday.id, moved.id)
        assertEquals(lunch.id, moved.mealType.id)
        assertEquals(WeekEffect.Show(WeekMessage.Moved(monday.displayTitle, day(5))), effects.single())
        vm.onAction(WeekAction.Undo)
        val back = vm.uiState.value.days[0].meals.single()
        assertEquals(dinner.id, back.mealType.id)
    }

    @Test
    fun anUnchangedMoveDoesNothing() = runTest {
        val vm = viewModel()
        val effects = open(vm)
        vm.onAction(WeekAction.StartMove(monday))
        vm.onAction(WeekAction.ConfirmMove)
        assertTrue(effects.isEmpty())
        assertEquals(monday, vm.uiState.value.days[0].meals.single())
    }

    @Test
    fun aGuestSeesTheWeekButCannotChangeIt() = runTest {
        permissions.setGuest(true)
        val vm = viewModel()
        val effects = open(vm)
        assertTrue(vm.uiState.value.readOnly)
        vm.onAction(WeekAction.Delete(todayDinner))
        vm.onAction(WeekAction.StartMove(monday))
        assertNull(vm.uiState.value.move)
        assertTrue(effects.isEmpty())
        assertEquals(MealPlanSamples.meals.size, mealPlans.meals.value.size)
    }

    @Test
    fun aConflictIsShownWithItsMealAndResolved() = runTest {
        val conflict = MealPlanConflict(todayDinner.id, "title", mine = "Min", theirs = "Deras")
        mealPlans.conflicts.value = listOf(conflict)
        val vm = viewModel()
        open(vm)
        assertEquals(ConflictState(conflict, todayDinner.displayTitle), vm.uiState.value.conflict)
        vm.onAction(WeekAction.ResolveConflict(keepMine = false))
        assertEquals(listOf(conflict to false), mealPlans.resolved)
        assertNull(vm.uiState.value.conflict)
    }

    @Test
    fun aFailedRefreshIsReported() = runTest {
        mealPlans.failure = TandemError.NetworkUnavailable
        val vm = viewModel()
        val effects = open(vm)
        vm.onAction(WeekAction.Refresh)
        assertEquals(WeekEffect.Failed(TandemError.NetworkUnavailable), effects.single())
        assertFalse(vm.uiState.value.refreshing)
    }

    @Test
    fun selectingADayShowsItBeside() = runTest {
        val vm = viewModel()
        open(vm)
        vm.onAction(WeekAction.SelectDay(day(6)))
        assertEquals(day(6), vm.uiState.value.shownDay)
    }
}
