package casa.moller.tandem.feature.shopping.recipe

import androidx.lifecycle.SavedStateHandle
import casa.moller.tandem.core.model.recipe.Recipe
import casa.moller.tandem.core.model.shopping.RecipeShoppingRequest
import casa.moller.tandem.core.model.shopping.ShoppingEntry
import casa.moller.tandem.core.model.shopping.ShoppingFood
import casa.moller.tandem.core.model.shopping.ShoppingList
import casa.moller.tandem.core.model.shopping.ShoppingRecipeLabel
import casa.moller.tandem.core.testing.MainDispatcherRule
import casa.moller.tandem.core.testing.data.FakePreferencesRepository
import casa.moller.tandem.core.testing.data.FakeRecipeRepository
import casa.moller.tandem.core.testing.data.FakeShoppingRepository
import casa.moller.tandem.core.testing.data.FakeSpacePermissionsRepository
import casa.moller.tandem.core.testing.data.SampleRecipes
import java.util.Locale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * "Lägg på inköpslistan" (feature #12, Phase 2 koppling lane): the ingredients at the chosen
 * servings, on-hand foods unticked ("Finns hemma"), one list recipe with the chosen rows, the
 * recipe's rows on the list and removing them, guests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecipeShoppingViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    /** Krämig svamppasta with butter and salt at home. */
    private val pasta: Recipe = SampleRecipes.MushroomPasta.let { recipe ->
        recipe.copy(
            steps = recipe.steps.map { step ->
                step.copy(
                    ingredients = step.ingredients.map { ingredient ->
                        val food = ingredient.food
                        val atHome = food != null && food.name in setOf("smör", "salt")
                        if (atHome) ingredient.copy(food = food.copy(onHand = true)) else ingredient
                    },
                )
            },
        )
    }

    private val recipes = FakeRecipeRepository(details = listOf(pasta))
    private val shopping = FakeShoppingRepository(entries = emptyList(), lists = listOf(ShoppingList(1, "Veckohandling")))
    private val permissions = FakeSpacePermissionsRepository()

    private fun viewModel(recipeId: Long = pasta.id) =
        RecipeShoppingViewModel(recipeId, SavedStateHandle(), recipes, shopping, FakePreferencesRepository(), permissions)

    private fun TestScope.open(vm: RecipeShoppingViewModel): MutableList<RecipeShoppingEffect> {
        val effects = mutableListOf<RecipeShoppingEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.toList(effects) }
        vm.onAction(RecipeShoppingAction.SetLocale(Locale.forLanguageTag("sv-SE")))
        advanceUntilIdle()
        return effects
    }

    @Test
    fun theIngredientsAreScaledAndWhatIsAtHomeStartsUnticked() = runTest {
        val vm = viewModel()
        open(vm)
        assertTrue(vm.uiState.value.available)
        assertFalse(vm.uiState.value.open)
        vm.onAction(RecipeShoppingAction.Open(servings = 8.0))
        val state = vm.uiState.value
        assertTrue(state.open)
        assertEquals(8.0, state.servings, 0.0)
        assertEquals(listOf("champinjoner", "vitlöksklyftor", "pasta", "vispgrädde", "parmesan"), state.toBuy.map { it.food })
        assertEquals("800 g", state.toBuy.first().amount)
        assertTrue(state.toBuy.all { it.chosen })
        assertEquals(listOf("smör", "salt"), state.atHome.map { it.food })
        assertTrue("on-hand foods are not ticked", state.atHome.none { it.chosen } && state.atHome.all { it.onHand })
        assertEquals(5, state.chosenCount)
        assertTrue(state.onlyMissing)
    }

    @Test
    fun addingCreatesOneListRecipeWithTheChosenIngredients() = runTest {
        val vm = viewModel()
        val effects = open(vm)
        vm.onAction(RecipeShoppingAction.Open(servings = null))
        vm.onAction(RecipeShoppingAction.IncreaseServings)
        vm.onAction(RecipeShoppingAction.Toggle(10132)) // parmesan: not needed
        vm.onAction(RecipeShoppingAction.Toggle(10112)) // butter: needed after all
        vm.onAction(RecipeShoppingAction.SelectList(1))
        vm.onAction(RecipeShoppingAction.Add)
        advanceUntilIdle()
        val request = RecipeShoppingRequest(pasta.id, 5.0, setOf(10111L, 10112L, 10113L, 10121L, 10131L), listIds = setOf(1L))
        assertEquals(listOf(request), shopping.recipeRequests)
        assertEquals(listOf<RecipeShoppingEffect>(RecipeShoppingEffect.Added(5, pasta.name)), effects)
        assertFalse("the sheet closes", vm.uiState.value.open)
    }

    @Test
    fun selectAllAndOnlyMissing() = runTest {
        val vm = viewModel()
        open(vm)
        vm.onAction(RecipeShoppingAction.Open(4.0))
        vm.onAction(RecipeShoppingAction.SelectAll)
        assertTrue(vm.uiState.value.allChosen)
        assertEquals(7, vm.uiState.value.chosenCount)
        vm.onAction(RecipeShoppingAction.OnlyMissing)
        assertTrue(vm.uiState.value.onlyMissing)
        vm.onAction(RecipeShoppingAction.DecreaseServings)
        vm.onAction(RecipeShoppingAction.DecreaseServings)
        vm.onAction(RecipeShoppingAction.DecreaseServings)
        vm.onAction(RecipeShoppingAction.DecreaseServings)
        assertEquals("never under one serving", 1.0, vm.uiState.value.servings, 0.0)
        assertFalse(vm.uiState.value.canDecreaseServings)
    }

    @Test
    fun theRecipesRowsOnTheListCanBeRemovedTogetherAndPutBackWithUndo() = runTest {
        val label = ShoppingRecipeLabel(listRecipeId = -5, recipeId = pasta.id, recipeName = pasta.name, servings = 4.0)
        val meal = ShoppingRecipeLabel(listRecipeId = 9, recipeId = pasta.id, recipeName = pasta.name, mealPlanId = 3)
        shopping.entries.value = listOf(
            ShoppingEntry(1, ShoppingFood(1, "champinjon"), amount = 400.0, recipe = label, ingredientId = 10111, listIds = setOf(1)),
            ShoppingEntry(2, ShoppingFood(4, "pasta"), amount = 400.0, recipe = label, ingredientId = 10121, listIds = setOf(1)),
            ShoppingEntry(3, ShoppingFood(6, "vispgrädde"), amount = 2.5, recipe = meal),
            ShoppingEntry(4, ShoppingFood(8, "mjölk"), amount = 1.0),
            // Bought already: Tandoor deletes it with the list recipe too.
            ShoppingEntry(5, ShoppingFood(9, "parmesan"), amount = 50.0, recipe = label, ingredientId = 10131, checked = true),
        )
        val vm = viewModel()
        val effects = open(vm)
        assertEquals("a planned meal's rows are not this recipe's own", 2, vm.uiState.value.onList)
        assertEquals("the checked row goes too", 3, vm.uiState.value.removable)
        vm.onAction(RecipeShoppingAction.RemoveFromList)
        advanceUntilIdle()
        assertEquals(listOf(3L, 4L), shopping.entries.value.map { it.id })
        val removed = listOf<RecipeShoppingEffect>(RecipeShoppingEffect.Removed(3, pasta.name))
        assertEquals("the message counts every removed row (fix-ui)", removed, effects)
        assertEquals(0, vm.uiState.value.onList)

        // fix-ui: "Ångra" puts the rows still to buy back as one list recipe (the bought one stays off).
        vm.onAction(RecipeShoppingAction.Undo)
        advanceUntilIdle()
        val restored = RecipeShoppingRequest(pasta.id, 4.0, setOf(10111L, 10121L), listIds = setOf(1L))
        assertEquals(listOf(restored), shopping.recipeRequests)
        vm.onAction(RecipeShoppingAction.Undo)
        advanceUntilIdle()
        assertEquals("one undo only", 1, shopping.recipeRequests.size)
    }

    @Test
    fun aGuestGetsNoButtonAndNothingIsWritten() = runTest {
        permissions.setGuest(true)
        val vm = viewModel()
        open(vm)
        assertTrue(vm.uiState.value.readOnly)
        assertFalse(vm.uiState.value.canAdd)
        vm.onAction(RecipeShoppingAction.Open(4.0))
        assertFalse(vm.uiState.value.open)
        vm.onAction(RecipeShoppingAction.Add)
        advanceUntilIdle()
        assertTrue(shopping.recipeRequests.isEmpty())
    }

    @Test
    fun aRecipeThatIsNotStoredCannotGoOnTheList() = runTest {
        val vm = viewModel(recipeId = 999)
        open(vm)
        assertFalse(vm.uiState.value.loading)
        assertFalse(vm.uiState.value.available)
        vm.onAction(RecipeShoppingAction.Open(4.0))
        assertFalse(vm.uiState.value.open)
    }

    /** Fas S (hemma-ui lane): "Lägg det som saknas på listan" ticks exactly the missing foods. */
    @Test
    fun fromCookNowOnlyTheMissingFoodsAreTicked() = runTest {
        val vm = viewModel()
        open(vm)
        // Mushrooms (1) are missing, and so is the butter (2), although Tandoor marks it on hand.
        vm.onAction(RecipeShoppingAction.Open(servings = null, missingFoodIds = setOf(1L, 2L)))
        val state = vm.uiState.value
        assertTrue(state.open)
        assertTrue(state.fromPantry)
        assertEquals(listOf("champinjoner", "smör"), state.toBuy.map { it.food })
        assertTrue(state.toBuy.all { it.chosen })
        assertTrue(state.atHome.none { it.chosen })
        assertTrue("salt" in state.atHome.map { it.food })
        assertEquals(2, state.chosenCount)
        vm.onAction(RecipeShoppingAction.Add)
        advanceUntilIdle()
        assertEquals(setOf(10111L, 10112L), shopping.recipeRequests.last().ingredientIds)

        // The usual sheet afterwards is the usual choice again.
        vm.onAction(RecipeShoppingAction.Open(servings = 4.0))
        assertFalse(vm.uiState.value.fromPantry)
        assertTrue("smör" in vm.uiState.value.atHome.map { it.food })
    }

    // Fas 5 (plan §8.1 "Pannstorlek"): the sheet opened from a recipe or cook mode in another tin.

    /** The pasta baked in a 24 cm tin (only so that it has one). */
    private fun tinViewModel(diameter: Int = 24): RecipeShoppingViewModel {
        val tin = pasta.copy(diameter = diameter, diameterText = "cm")
        val repository = FakeRecipeRepository(details = listOf(tin))
        return RecipeShoppingViewModel(tin.id, SavedStateHandle(), repository, shopping, FakePreferencesRepository(), permissions)
    }

    @Test
    fun inASmallerTinTheListGetsTheServingsTimesTheTinLikeTheWeb() = runTest {
        val vm = tinViewModel()
        val effects = open(vm)
        vm.onAction(RecipeShoppingAction.Open(servings = 4.0, panSize = 20))
        val state = vm.uiState.value
        assertTrue(state.open)
        assertEquals("the stepper keeps the servings shown", 4.0, state.servings, 0.0)
        assertEquals(RecipeShoppingPan(diameter = 20, unit = "cm", factor = 0.69, factorText = "0,69"), state.pan)
        assertEquals("400 g × 0.69, as the recipe showed", "276 g", state.toBuy.first { it.id == 10111L }.amount)
        assertEquals(2.76, state.listServings, 0.0)

        vm.onAction(RecipeShoppingAction.Add)
        advanceUntilIdle()
        val request = RecipeShoppingRequest(pasta.id, 2.76, setOf(10111L, 10113L, 10121L, 10131L, 10132L))
        assertEquals("Tandoor's web sends servings × factor", listOf(request), shopping.recipeRequests)
        assertEquals(listOf<RecipeShoppingEffect>(RecipeShoppingEffect.Added(5, pasta.name)), effects)
    }

    @Test
    fun theStepperChangesTheServingsAndTheTinStays() = runTest {
        val vm = tinViewModel()
        open(vm)
        vm.onAction(RecipeShoppingAction.Open(servings = 4.0, panSize = 20))
        vm.onAction(RecipeShoppingAction.IncreaseServings)
        vm.onAction(RecipeShoppingAction.IncreaseServings)
        vm.onAction(RecipeShoppingAction.IncreaseServings)
        vm.onAction(RecipeShoppingAction.IncreaseServings)
        assertEquals(8.0, vm.uiState.value.servings, 0.0)
        assertEquals("552 g", vm.uiState.value.toBuy.first { it.id == 10111L }.amount)
        vm.onAction(RecipeShoppingAction.Add)
        advanceUntilIdle()
        assertEquals(5.52, shopping.recipeRequests.single().servings, 0.0)
    }

    @Test
    fun aLargerTinScalesUp() = runTest {
        val vm = tinViewModel(diameter = 20)
        open(vm)
        vm.onAction(RecipeShoppingAction.Open(servings = 4.0, panSize = 24))
        assertEquals(1.44, vm.uiState.value.pan?.factor)
        assertEquals("576 g", vm.uiState.value.toBuy.first { it.id == 10111L }.amount)
        vm.onAction(RecipeShoppingAction.Add)
        advanceUntilIdle()
        assertEquals(5.76, shopping.recipeRequests.single().servings, 0.0)
    }

    @Test
    fun theRecipesOwnTinOrNoTinIsTheServingsAlone() = runTest {
        // The recipe's own tin: nothing to say, nothing scaled.
        val own = tinViewModel()
        open(own)
        own.onAction(RecipeShoppingAction.Open(servings = 4.0, panSize = 24))
        assertEquals(null, own.uiState.value.pan)
        assertEquals("400 g", own.uiState.value.toBuy.first { it.id == 10111L }.amount)
        own.onAction(RecipeShoppingAction.Add)
        advanceUntilIdle()
        assertEquals(4.0, shopping.recipeRequests.last().servings, 0.0)

        // A recipe without a tin ignores a size (cook mode never sends one for it, but still).
        val plain = viewModel()
        open(plain)
        plain.onAction(RecipeShoppingAction.Open(servings = 4.0, panSize = 20))
        assertEquals(null, plain.uiState.value.pan)
        assertEquals("400 g", plain.uiState.value.toBuy.first { it.id == 10111L }.amount)
        plain.onAction(RecipeShoppingAction.Add)
        advanceUntilIdle()
        assertEquals(4.0, shopping.recipeRequests.last().servings, 0.0)
    }

    @Test
    fun theNextOpeningWithoutATinIsTheRecipesOwnAgain() = runTest {
        val vm = tinViewModel()
        open(vm)
        vm.onAction(RecipeShoppingAction.Open(servings = 4.0, panSize = 20))
        vm.onAction(RecipeShoppingAction.Dismiss)
        // Kan laga nu (and Idag) open the sheet without a tin.
        vm.onAction(RecipeShoppingAction.Open(servings = null, missingFoodIds = setOf(1L)))
        assertEquals(null, vm.uiState.value.pan)
        assertEquals("400 g", vm.uiState.value.toBuy.first { it.id == 10111L }.amount)
        vm.onAction(RecipeShoppingAction.Add)
        advanceUntilIdle()
        assertEquals(4.0, shopping.recipeRequests.single().servings, 0.0)
    }

    @Test
    fun theTinSurvivesProcessDeathWithTheSheet() = runTest {
        val handle = SavedStateHandle()
        val tin = pasta.copy(diameter = 24, diameterText = "cm")
        val repository = FakeRecipeRepository(details = listOf(tin))
        val first = RecipeShoppingViewModel(tin.id, handle, repository, shopping, FakePreferencesRepository(), permissions)
        open(first)
        first.onAction(RecipeShoppingAction.Open(servings = 4.0, panSize = 20))
        val again = RecipeShoppingViewModel(tin.id, handle, repository, shopping, FakePreferencesRepository(), permissions)
        open(again)
        assertTrue(again.uiState.value.open)
        assertEquals(20, again.uiState.value.pan?.diameter)
    }
}
