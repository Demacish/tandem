package casa.moller.tandem.core.model.recipe

import casa.moller.tandem.core.model.error.TandemError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RecipeModelTest {

    @Test
    fun totalTimeAddsWorkingAndWaiting() {
        assertEquals(45, RecipeOverview(id = 1, name = "Köttbullar", workingMinutes = 30, waitingMinutes = 15).totalMinutes)
        assertEquals(0, RecipeOverview(id = 1, name = "Smörgås").totalMinutes)
    }

    @Test
    fun ingredientsAreFlattenedInStepThenIngredientOrder() {
        val recipe = Recipe(
            id = 1,
            name = "Kanelbullar",
            steps = listOf(
                Step(id = 2, order = 1, ingredients = listOf(Ingredient(id = 22, order = 1), Ingredient(id = 21, order = 0))),
                Step(id = 1, order = 0, ingredients = listOf(Ingredient(id = 11, order = 0))),
            ),
        )
        assertEquals(listOf(11L, 21L, 22L), recipe.ingredients.map { it.id })
    }

    @Test
    fun headersAndNoAmountRowsAreNotScaled() {
        assertTrue(Ingredient(id = 1, amount = 2.0).isScalable)
        assertFalse(Ingredient(id = 2, isHeader = true).isScalable)
        assertFalse(Ingredient(id = 3, noAmount = true).isScalable)
    }

    @Test
    fun queryWithoutFiltersIsUnfilteredWhateverTheSort() {
        assertTrue(RecipeQuery.All.isUnfiltered)
        assertTrue(RecipeQuery(sort = RecipeSort.RATING).isUnfiltered)
        assertFalse(RecipeQuery(text = "äppelpaj").isUnfiltered)
        assertFalse(RecipeQuery(onlyOffline = true).isUnfiltered)
        assertFalse(RecipeQuery(maxTotalMinutes = 30).isUnfiltered)
        assertFalse(RecipeQuery(foodIds = setOf(1L)).isUnfiltered)
    }

    @Test
    fun detailStateKnowsWhenTheRecipeWasDeleted() {
        assertTrue(RecipeDetailState(recipeId = 5).isEmpty)
        assertTrue(RecipeDetailState(recipeId = 5, error = TandemError.NotFound).isDeleted)
        assertFalse(RecipeDetailState(recipeId = 5, error = TandemError.Timeout).isDeleted)
    }
}
