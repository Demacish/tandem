package casa.moller.tandem.feature.mealplan

import casa.moller.tandem.core.data.mealplan.MealPlanRepository
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.mealplan.WeekShoppingPreview
import casa.moller.tandem.core.model.mealplan.WeekShoppingSelection
import casa.moller.tandem.core.testing.MainDispatcherRule
import casa.moller.tandem.core.testing.data.FakeMealPlanRepository
import casa.moller.tandem.core.testing.data.FakeSpacePermissionsRepository
import casa.moller.tandem.feature.mealplan.MealPlanSamples.sunday
import casa.moller.tandem.feature.mealplan.MealPlanSamples.todayDinner
import casa.moller.tandem.feature.mealplan.navigation.WeekShoppingKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** "Handla för veckan" never adds a meal twice (plan 6.2 step 2, Phase 2 "Klart när"). */
@OptIn(ExperimentalCoroutinesApi::class)
class WeekShoppingViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val mealPlans = FakeMealPlanRepository(meals = MealPlanSamples.meals, mealTypes = MealPlanSamples.mealTypes)
    private val permissions = FakeSpacePermissionsRepository()

    private fun TestScope.open(vm: WeekShoppingViewModel): MutableList<WeekShoppingEffect> {
        val effects = mutableListOf<WeekShoppingEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.toList(effects) }
        advanceUntilIdle()
        return effects
    }

    @Test
    fun mealsAlreadyOnTheListCannotBeAddedAgain() = runTest {
        val vm = WeekShoppingViewModel(WeekShoppingKey(MealPlanSamples.weekStart.toString()), mealPlans, permissions)
        val effects = open(vm)
        val state = vm.uiState.value
        assertFalse(state.loading)
        val onList = state.meals.single { it.meal.meal.id == sunday.id }
        assertTrue(onList.meal.alreadyOnList)
        assertFalse(onList.canSelect)
        vm.onAction(WeekShoppingAction.ToggleMeal(sunday.id))
        assertFalse(vm.uiState.value.meals.single { it.meal.meal.id == sunday.id }.selected)
        vm.onAction(WeekShoppingAction.Add)
        advanceUntilIdle()
        // The fake's preview has no ingredients: nothing is selectable, nothing is added.
        assertTrue(mealPlans.weekSelections.isEmpty())
        assertTrue(effects.isEmpty())
    }

    @Test
    fun onHandFoodsStartUntickedAndTheChosenIngredientsAreAdded() = runTest {
        val preview = WeekShoppingPreview(MealPlanSamples.weekShopping.meals.map { it.meal }, autoAddOnServer = true)
        val repository = object : MealPlanRepository by mealPlans {
            override suspend fun weekShoppingPreview(start: LocalDate): Outcome<WeekShoppingPreview> = Outcome.Success(preview)
        }
        val vm = WeekShoppingViewModel(WeekShoppingKey(MealPlanSamples.weekStart.toString()), repository, permissions)
        val effects = open(vm)
        val state = vm.uiState.value
        assertTrue(state.autoAdd)
        val pasta = state.meals.single { it.meal.meal.id == todayDinner.id }
        assertEquals("salt is at home", setOf(101L, 102L, 103L), pasta.chosen)
        assertTrue(pasta.selected)
        val notDownloaded = state.meals.single { it.meal.meal.id == MealPlanSamples.friday.id }
        assertFalse("no ingredients to choose", notDownloaded.canSelect)
        assertEquals(listOf(pasta), state.toAdd)
        vm.onAction(WeekShoppingAction.ToggleIngredient(todayDinner.id, 102))
        vm.onAction(WeekShoppingAction.Add)
        advanceUntilIdle()
        assertEquals(listOf(WeekShoppingSelection(mapOf(todayDinner.id to setOf(101L, 103L)))), mealPlans.weekSelections)
        assertEquals(listOf<WeekShoppingEffect>(WeekShoppingEffect.Added(1)), effects)
    }

    @Test
    fun untickingEveryIngredientUnselectsTheMeal() = runTest {
        val preview = WeekShoppingPreview(MealPlanSamples.weekShopping.meals.map { it.meal })
        val repository = object : MealPlanRepository by mealPlans {
            override suspend fun weekShoppingPreview(start: LocalDate): Outcome<WeekShoppingPreview> = Outcome.Success(preview)
        }
        val vm = WeekShoppingViewModel(WeekShoppingKey(MealPlanSamples.weekStart.toString()), repository, permissions)
        open(vm)
        listOf(101L, 102L, 103L).forEach { vm.onAction(WeekShoppingAction.ToggleIngredient(todayDinner.id, it)) }
        assertFalse(vm.uiState.value.meals.single { it.meal.meal.id == todayDinner.id }.selected)
        assertTrue(vm.uiState.value.toAdd.isEmpty())
    }
}
