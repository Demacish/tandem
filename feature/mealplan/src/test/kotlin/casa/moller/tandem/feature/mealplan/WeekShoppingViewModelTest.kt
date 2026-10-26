package casa.moller.tandem.feature.mealplan

import androidx.lifecycle.SavedStateHandle
import casa.moller.tandem.core.domain.mealplan.WeekShoppingMerge
import casa.moller.tandem.core.model.mealplan.WeekShoppingSelection
import casa.moller.tandem.core.model.shopping.SupermarketCategory
import casa.moller.tandem.core.testing.MainDispatcherRule
import casa.moller.tandem.core.testing.data.FakeMealPlanRepository
import casa.moller.tandem.core.testing.data.FakeSpacePermissionsRepository
import casa.moller.tandem.core.testing.data.FakeSupermarketRepository
import casa.moller.tandem.feature.mealplan.MealPlanSamples.friday
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

/**
 * "Handla för veckan" (feature #16; Phase 2 koppling lane): a range of days, the ingredients merged
 * per food and unit, on-hand foods unticked, and never a meal twice (plan 6.2 step 2, Phase 2
 * "Klart när").
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WeekShoppingViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val mealPlans = FakeMealPlanRepository(meals = MealPlanSamples.meals, mealTypes = MealPlanSamples.mealTypes).apply {
        ingredients = MealPlanSamples.weekShoppingMeals.associate { it.meal.id to it.ingredients }
    }
    private val permissions = FakeSpacePermissionsRepository()
    private val supermarkets = FakeSupermarketRepository(
        categories = MealPlanSamples.categories.map { (id, name) -> SupermarketCategory(id, name) },
    )

    private fun viewModel() = WeekShoppingViewModel(
        WeekShoppingKey(MealPlanSamples.weekStart.toString()),
        SavedStateHandle(),
        mealPlans,
        permissions,
        supermarkets,
    )

    private fun TestScope.open(vm: WeekShoppingViewModel): MutableList<WeekShoppingEffect> {
        val effects = mutableListOf<WeekShoppingEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.toList(effects) }
        advanceUntilIdle()
        return effects
    }

    private val cream = WeekShoppingMerge.key("grädde", "dl")
    private val salt = WeekShoppingMerge.key("salt", null)
    private val pasta = WeekShoppingMerge.key("pasta", "g")

    @Test
    fun ingredientsAreMergedPerFoodAndUnitByAisle() = runTest {
        val vm = viewModel()
        open(vm)
        val state = vm.uiState.value
        assertFalse(state.loading)
        assertEquals(listOf(MealPlanSamples.weekStart to LocalDate(2026, 10, 25)), mealPlans.previewRanges)
        val creamLine = state.aisles.single { it.name == "Mejeri" }.lines.single().line
        assertEquals(cream, creamLine.key)
        assertEquals(5.0, creamLine.amount, 0.0)
        assertTrue(creamLine.merged)
        assertEquals(listOf("Fisk & kött", "Frukt & grönt", "Mejeri", "Torrvaror"), state.aisles.map { it.name })
        // Salt is at home in both meals: one line, unticked.
        assertEquals(listOf(salt), state.atHome.map { it.line.key })
        assertFalse(state.atHome.single().chosen)
        assertEquals(5, state.chosenCount)
        assertEquals(5, state.missingCount)
        assertEquals(6, state.lineCount)
        assertTrue(state.onlyMissing)
    }

    @Test
    fun aMealAlreadyOnTheListIsNeverAddedAgain() = runTest {
        val vm = viewModel()
        val effects = open(vm)
        val chicken = vm.uiState.value.meals.single { it.meal.meal.id == sunday.id }
        assertTrue(chicken.meal.alreadyOnList)
        assertFalse(chicken.canSelect)
        assertEquals(1, vm.uiState.value.alreadyOnListCount)
        vm.onAction(WeekShoppingAction.ToggleMeal(sunday.id))
        assertFalse(vm.uiState.value.meals.single { it.meal.meal.id == sunday.id }.included)
        vm.onAction(WeekShoppingAction.Add)
        advanceUntilIdle()
        val selection = mealPlans.weekSelections.single()
        assertEquals(setOf(todayDinner.id, friday.id), selection.ingredientIdsByMeal.keys)
        assertEquals(
            WeekShoppingSelection(mapOf(todayDinner.id to setOf(101L, 102L, 103L), friday.id to setOf(201L, 202L, 203L))),
            selection,
        )
        assertEquals(listOf<WeekShoppingEffect>(WeekShoppingEffect.Added(meals = 2, items = 5)), effects)
    }

    @Test
    fun leavingOutAMealAndUntickingALineChangeWhatIsAdded() = runTest {
        val vm = viewModel()
        open(vm)
        vm.onAction(WeekShoppingAction.ToggleMeal(friday.id))
        val creamLine = vm.uiState.value.aisles.flatMap { it.lines }.single { it.line.key == cream }.line
        assertEquals("only the pasta's cream is left", 3.0, creamLine.amount, 0.0)
        assertFalse(creamLine.merged)
        vm.onAction(WeekShoppingAction.ToggleLine(pasta))
        vm.onAction(WeekShoppingAction.ToggleLine(salt))
        vm.onAction(WeekShoppingAction.Add)
        advanceUntilIdle()
        assertEquals(WeekShoppingSelection(mapOf(todayDinner.id to setOf(102L, 103L, 104L))), mealPlans.weekSelections.single())
    }

    @Test
    fun onlyMissingAndAllIngredients() = runTest {
        val vm = viewModel()
        open(vm)
        vm.onAction(WeekShoppingAction.AllIngredients)
        assertTrue(vm.uiState.value.allChosen)
        assertEquals(6, vm.uiState.value.chosenCount)
        vm.onAction(WeekShoppingAction.OnlyMissing)
        assertTrue(vm.uiState.value.onlyMissing)
        assertEquals(5, vm.uiState.value.chosenCount)
    }

    @Test
    fun anotherRangeOfDaysIsReadAgain() = runTest {
        val vm = viewModel()
        open(vm)
        vm.onAction(WeekShoppingAction.SetRange(LocalDate(2026, 10, 24), LocalDate(2026, 10, 23)))
        advanceUntilIdle()
        assertEquals(LocalDate(2026, 10, 23) to LocalDate(2026, 10, 24), mealPlans.previewRanges.last())
        val state = vm.uiState.value
        assertEquals(LocalDate(2026, 10, 23), state.from)
        assertEquals(LocalDate(2026, 10, 24), state.to)
        assertEquals(listOf(friday.id), state.meals.map { it.meal.meal.id })
    }

    @Test
    fun aGuestCannotAdd() = runTest {
        permissions.setGuest(true)
        val vm = viewModel()
        open(vm)
        assertTrue(vm.uiState.value.readOnly)
        assertFalse(vm.uiState.value.canAdd)
        vm.onAction(WeekShoppingAction.Add)
        advanceUntilIdle()
        assertTrue(mealPlans.weekSelections.isEmpty())
    }
}
