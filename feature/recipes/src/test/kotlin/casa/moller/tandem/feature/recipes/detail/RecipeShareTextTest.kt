package casa.moller.tandem.feature.recipes.detail

import casa.moller.tandem.core.domain.recipe.IngredientRow
import casa.moller.tandem.core.domain.recipe.UnitAmount
import casa.moller.tandem.core.domain.scaling.DisplayAmount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Dela som text" (plan §6.2, §8.6): what the screen shows right now, for someone without an account. */
class RecipeShareTextTest {
    private val labels = RecipeShareText.Labels(
        servings = "4 portioner",
        ingredients = "Ingredienser",
        steps = "Gör så här",
        source = "Källa:",
    )

    private fun amount(text: String, unit: String) = UnitAmount(text, unit, DisplayAmount.Decimal(0.0, text))

    private val flour = IngredientRow.Item(
        id = 1,
        units = listOf(amount("2,5", "dl"), amount("150", "g")),
        food = "vetemjöl",
        note = "siktat",
    )
    private val salt = IngredientRow.Item(id = 2, units = emptyList(), food = "salt", note = "", noAmount = true)

    private fun step(number: Int, instruction: String, name: String = "", ingredients: List<IngredientRow> = emptyList()) = StepItem(
        id = number.toLong(),
        number = number,
        name = name,
        instruction = instruction,
        minutes = 0,
        ingredients = ingredients,
        showIngredients = ingredients.isNotEmpty(),
        stepRecipe = null,
    )

    private val state = RecipeDetailUiState(
        recipeId = 1,
        header = RecipeHeader(name = "Pannkakor", description = "Tunna"),
        hasDetail = true,
        servings = ServingsState(current = 4.0, original = 4, text = "", amountText = "4"),
        ingredients = listOf(IngredientRow.Header(9, "Smet"), flour, salt),
        steps = listOf(step(1, "Vispa ihop.\n"), step(2, "Stek.", name = "I pannan")),
        sourceUrl = "https://example.com/pannkakor",
        webUrl = "https://tandoor.example.com/recipe/1/",
    )

    @Test
    fun theWholeRecipeInTheOrderOfTheScreen() {
        assertEquals(
            """
            Pannkakor
            Tunna
            4 portioner

            Ingredienser
            Smet
            - 2,5 dl vetemjöl, siktat
            - salt

            Gör så här
            1. Vispa ihop.
            2. I pannan
            Stek.

            Källa: https://example.com/pannkakor
            """.trimIndent(),
            RecipeShareText.of(state, labels),
        )
    }

    @Test
    fun theUnitAndServingsAreTheOnesOnTheScreen() {
        val text = RecipeShareText.of(
            state.copy(
                unitChoices = mapOf(1L to 1),
                servings = ServingsState(current = 2.5, original = 4, text = "bitar", amountText = "2,5"),
            ),
            labels,
        )
        assertTrue(text, "- 150 g vetemjöl, siktat" in text)
        assertTrue(text, "2,5 bitar" in text)
    }

    @Test
    fun aRecipeWithItsIngredientsInTheStepsListsThemAll() {
        val steps = listOf(step(1, "Vispa.", ingredients = listOf(flour)), step(2, "Salta.", ingredients = listOf(salt)))
        val text = RecipeShareText.of(state.copy(ingredients = emptyList(), steps = steps), labels)
        assertTrue(text, "Ingredienser\n- 2,5 dl vetemjöl, siktat\n- salt" in text)
    }

    @Test
    fun theServerIsNotInIt() {
        assertFalse("tandoor.example.com" in RecipeShareText.of(state, labels))
    }

    @Test
    fun whatTheRecipeLacksLeavesNoGap() {
        val bare = RecipeDetailUiState(recipeId = 1, header = RecipeHeader(name = "Vatten"), hasDetail = true)
        assertEquals("Vatten", RecipeShareText.of(bare, labels))
    }
}
