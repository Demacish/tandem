package casa.moller.tandem.feature.mealplan

import casa.moller.tandem.core.model.mealplan.MealPlanDraft
import casa.moller.tandem.core.model.mealplan.MealPlanUpdate
import casa.moller.tandem.core.model.preferences.UserPreferences
import casa.moller.tandem.core.testing.MainDispatcherRule
import casa.moller.tandem.core.testing.data.FakeMealPlanRepository
import casa.moller.tandem.core.testing.data.FakePreferencesRepository
import casa.moller.tandem.core.testing.data.FakeRecipeRepository
import casa.moller.tandem.core.testing.data.FakeSpacePermissionsRepository
import casa.moller.tandem.core.testing.data.SampleRecipes
import casa.moller.tandem.core.testing.time.TestClock
import casa.moller.tandem.feature.mealplan.MealPlanSamples.day
import casa.moller.tandem.feature.mealplan.MealPlanSamples.dinner
import casa.moller.tandem.feature.mealplan.MealPlanSamples.leftovers
import casa.moller.tandem.feature.mealplan.MealPlanSamples.lunch
import casa.moller.tandem.feature.mealplan.MealPlanSamples.todayDinner
import casa.moller.tandem.feature.mealplan.navigation.PlanMealKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlanMealViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val clock = TestClock(MealPlanSamples.now)
    private val mealPlans = FakeMealPlanRepository(meals = MealPlanSamples.meals, mealTypes = MealPlanSamples.mealTypes)
    private val recipes = FakeRecipeRepository()
    private val preferences = FakePreferencesRepository(UserPreferences(defaultMealTypeId = dinner.id, mealPlanAutoAddShopping = true))
    private val permissions = FakeSpacePermissionsRepository()
    private val pasta = SampleRecipes.MushroomPasta

    private fun viewModel(key: PlanMealKey) = PlanMealViewModel(key, mealPlans, recipes, preferences, permissions, clock)

    private fun TestScope.open(vm: PlanMealViewModel): MutableList<PlanMealEffect> {
        val effects = mutableListOf<PlanMealEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.toList(effects) }
        advanceUntilIdle()
        return effects
    }

    @Test
    fun aNewMealStartsWithTheDefaultMealTypeAndTheShoppingPreference() = runTest {
        val vm = viewModel(PlanMealKey(date = day(5).toString()))
        open(vm)
        val state = vm.uiState.value
        assertFalse(state.loading)
        assertFalse(state.editing)
        assertEquals(day(5), state.date)
        assertEquals(dinner.id, state.mealTypeId)
        assertEquals(MealKind.RECIPE, state.kind)
        assertTrue("starts as mealplan_autoadd_shopping", state.addToShopping)
        assertFalse("only with a recipe", state.showAddToShopping)
    }

    @Test
    fun theKeysMealTypeWins() = runTest {
        val vm = viewModel(PlanMealKey(date = day(5).toString(), mealTypeId = lunch.id))
        open(vm)
        assertEquals(lunch.id, vm.uiState.value.mealTypeId)
    }

    @Test
    fun searchingPickingAndSavingARecipe() = runTest {
        val vm = viewModel(PlanMealKey(date = day(5).toString()))
        val effects = open(vm)
        vm.onAction(PlanMealAction.Query("svamp"))
        advanceUntilIdle()
        val found = vm.uiState.value.results
        assertTrue("$found", found.isNotEmpty() && found.all { "svamp" in it.name.lowercase() || it.id != pasta.id })
        vm.onAction(PlanMealAction.PickRecipe(found.single { it.id == pasta.id }))
        advanceUntilIdle()
        assertEquals(pasta.id, vm.uiState.value.recipe?.id)
        assertEquals("the recipe's own servings", pasta.servings.toDouble(), vm.uiState.value.servings, 0.0)
        vm.onAction(PlanMealAction.MoreServings)
        vm.onAction(PlanMealAction.Note("Dubbel sats"))
        vm.onAction(PlanMealAction.Save)
        advanceUntilIdle()
        assertEquals(
            MealPlanDraft(
                date = day(5),
                mealTypeId = dinner.id,
                recipeId = pasta.id,
                servings = pasta.servings + 1.0,
                note = "Dubbel sats",
                addToShopping = true,
            ),
            mealPlans.drafts.single(),
        )
        assertEquals(listOf<PlanMealEffect>(PlanMealEffect.Done), effects)
    }

    @Test
    fun addToShoppingIsSentOnlyWhenTicked() = runTest {
        val vm = viewModel(PlanMealKey(date = day(5).toString(), recipeId = pasta.id))
        open(vm)
        assertEquals(pasta.id, vm.uiState.value.recipe?.id)
        vm.onAction(PlanMealAction.AddToShopping(false))
        vm.onAction(PlanMealAction.Save)
        advanceUntilIdle()
        assertFalse(mealPlans.drafts.single().addToShopping)
    }

    @Test
    fun aTitleOnlyMealNeedsATitle() = runTest {
        val vm = viewModel(PlanMealKey(date = day(5).toString()))
        val effects = open(vm)
        vm.onAction(PlanMealAction.SetKind(MealKind.TITLE))
        vm.onAction(PlanMealAction.Save)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.showErrors && vm.uiState.value.titleMissing)
        assertTrue(effects.isEmpty())
        vm.onAction(PlanMealAction.Title("Äta ute"))
        vm.onAction(PlanMealAction.SetDate(day(6)))
        vm.onAction(PlanMealAction.Save)
        advanceUntilIdle()
        val draft = mealPlans.drafts.single()
        assertEquals("Äta ute", draft.title)
        assertNull(draft.recipeId)
        assertEquals(day(6), draft.date)
        assertFalse(draft.addToShopping)
    }

    @Test
    fun aRecipeMealNeedsARecipe() = runTest {
        val vm = viewModel(PlanMealKey(date = day(5).toString()))
        open(vm)
        vm.onAction(PlanMealAction.Save)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.recipeMissing && vm.uiState.value.showErrors)
        assertTrue(mealPlans.drafts.isEmpty())
    }

    @Test
    fun changingAMealSavesOnlyWhatChanged() = runTest {
        val vm = viewModel(PlanMealKey(date = todayDinner.fromDate.toString(), mealPlanId = todayDinner.id))
        val effects = open(vm)
        val state = vm.uiState.value
        assertTrue(state.editing)
        assertEquals(todayDinner.recipe, state.recipe)
        assertEquals(listOf(MealKind.RECIPE), state.kinds)
        assertEquals("Dubbel sats, frys hälften", state.note)
        assertFalse(state.showAddToShopping)
        vm.onAction(PlanMealAction.SetDate(day(5)))
        vm.onAction(PlanMealAction.FewerServings)
        vm.onAction(PlanMealAction.Save)
        advanceUntilIdle()
        val changed = mealPlans.meals.value.single { it.id == todayDinner.id }
        assertEquals(day(5), changed.fromDate)
        assertEquals(3.0, changed.servings, 0.0)
        assertEquals(todayDinner.note, changed.note)
        assertEquals(listOf<PlanMealEffect>(PlanMealEffect.Done), effects)
        assertEquals(MealPlanUpdate(fromDate = day(5), servings = 3.0), changes(todayDinner, vm.uiState.value, dinner.id))
    }

    @Test
    fun aTitleOnlyMealCanGetARecipe() = runTest {
        val vm = viewModel(PlanMealKey(date = leftovers.fromDate.toString(), mealPlanId = leftovers.id))
        open(vm)
        assertEquals(MealKind.TITLE, vm.uiState.value.kind)
        assertEquals(MealKind.entries, vm.uiState.value.kinds)
        vm.onAction(PlanMealAction.SetKind(MealKind.RECIPE))
        vm.onAction(PlanMealAction.PickRecipe(SampleRecipes.overviews.first()))
        vm.onAction(PlanMealAction.Save)
        advanceUntilIdle()
        assertEquals(SampleRecipes.overviews.first().id, changes(leftovers, vm.uiState.value, lunch.id).recipeId)
    }

    @Test
    fun removingFromThePage() = runTest {
        val vm = viewModel(PlanMealKey(date = todayDinner.fromDate.toString(), mealPlanId = todayDinner.id))
        val effects = open(vm)
        vm.onAction(PlanMealAction.Delete)
        advanceUntilIdle()
        assertTrue(mealPlans.meals.value.none { it.id == todayDinner.id })
        assertEquals(listOf<PlanMealEffect>(PlanMealEffect.Done), effects)
    }

    @Test
    fun aMealDeletedElsewhereIsGone() = runTest {
        val vm = viewModel(PlanMealKey(date = day(5).toString(), mealPlanId = 999))
        open(vm)
        assertTrue(vm.uiState.value.gone)
        assertFalse(vm.uiState.value.canSave)
    }

    @Test
    fun aNewMealTypeIsCreatedAndChosen() = runTest {
        val vm = viewModel(PlanMealKey(date = day(5).toString()))
        open(vm)
        vm.onAction(PlanMealAction.OpenNewMealType)
        vm.onAction(PlanMealAction.NewMealTypeName("Mellanmål"))
        vm.onAction(PlanMealAction.CreateMealType)
        advanceUntilIdle()
        val created = mealPlans.mealTypes.value.single { it.name == "Mellanmål" }
        assertEquals(created.id, vm.uiState.value.mealTypeId)
        assertNull(vm.uiState.value.newMealType)
    }

    @Test
    fun aGuestCannotSave() = runTest {
        permissions.setGuest(true)
        val vm = viewModel(PlanMealKey(date = day(5).toString()))
        open(vm)
        vm.onAction(PlanMealAction.SetKind(MealKind.TITLE))
        vm.onAction(PlanMealAction.Title("Rester"))
        vm.onAction(PlanMealAction.Save)
        advanceUntilIdle()
        assertTrue(mealPlans.drafts.isEmpty())
        assertFalse(vm.uiState.value.canSave)
    }
}
