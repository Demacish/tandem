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
    fun theRecipesRowsOnTheListCanBeRemovedTogether() = runTest {
        val label = ShoppingRecipeLabel(listRecipeId = -5, recipeId = pasta.id, recipeName = pasta.name)
        val meal = ShoppingRecipeLabel(listRecipeId = 9, recipeId = pasta.id, recipeName = pasta.name, mealPlanId = 3)
        shopping.entries.value = listOf(
            ShoppingEntry(1, ShoppingFood(1, "champinjon"), amount = 400.0, recipe = label),
            ShoppingEntry(2, ShoppingFood(4, "pasta"), amount = 400.0, recipe = label),
            ShoppingEntry(3, ShoppingFood(6, "vispgrädde"), amount = 2.5, recipe = meal),
            ShoppingEntry(4, ShoppingFood(8, "mjölk"), amount = 1.0),
        )
        val vm = viewModel()
        val effects = open(vm)
        assertEquals("a planned meal's rows are not this recipe's own", 2, vm.uiState.value.onList)
        vm.onAction(RecipeShoppingAction.RemoveFromList)
        advanceUntilIdle()
        assertEquals(listOf(3L, 4L), shopping.entries.value.map { it.id })
        assertEquals(listOf<RecipeShoppingEffect>(RecipeShoppingEffect.Removed(2, pasta.name)), effects)
        assertEquals(0, vm.uiState.value.onList)
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
}
