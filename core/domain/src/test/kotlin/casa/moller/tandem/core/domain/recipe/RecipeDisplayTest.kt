package casa.moller.tandem.core.domain.recipe

import casa.moller.tandem.core.domain.format.NumberStyle
import casa.moller.tandem.core.domain.scaling.ServingsScaling
import casa.moller.tandem.core.model.preferences.UserPreferences
import casa.moller.tandem.core.model.recipe.Recipe
import casa.moller.tandem.core.model.recipe.Step
import casa.moller.tandem.core.model.recipe.StepRecipeRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The single call the recipe screens make: [RecipeDisplay.build]. */
class RecipeDisplayTest {
    private val preferences = UserPreferences.Default

    @Test
    fun scalesStepsAndIngredientsTogether() {
        val pasta = RecipeFixtures.recipe("recipe-svamppasta.json")
        val display = RecipeDisplay.build(pasta, 8.0, preferences, NumberStyle.Swedish)
        assertEquals(pasta.id, display.recipeId)
        assertEquals(8.0, display.servings)
        assertEquals(2.0, display.factor)
        assertEquals(listOf(1, 2, 3), display.steps.map { it.number })
        assertEquals(listOf("Förbered", "Koka pastan", "Sås"), display.steps.map { it.name })
        assertEquals(listOf(10, 12, 8), display.steps.map { it.timeMinutes })
        assertEquals("Skiva champinjoner och hacka 2 st gula lökar.\nPressa 4 vitlöksklyftor.", display.steps[0].instruction)
        assertEquals("Koka pastan i 6 liter saltat vatten.", display.steps[1].instruction)
        assertEquals(9, display.ingredients.size)
        assertEquals(display.steps.flatMap { it.ingredients }, display.ingredients)
        assertEquals("800 g pasta", (display.steps[1].ingredients[0] as IngredientRow.Item).text())
        // No headers in this recipe: one group without a title.
        assertEquals(1, display.ingredientGroups.size)
        assertEquals(null, display.ingredientGroups[0].header)
    }

    @Test
    fun brokenTemplatesAreFlaggedPerStep() {
        val potatoes = RecipeFixtures.recipe("recipe-rarakor.json")
        val display = RecipeDisplay.build(potatoes, 4.0, preferences, NumberStyle.Swedish)
        assertEquals(listOf(true, false), display.steps.map { it.hasBrokenTemplate })
    }

    @Test
    fun stepsFollowTheirOrderAndKeepSubRecipes() {
        val recipe = Recipe(
            id = 1,
            name = "Test",
            servings = 2,
            steps = listOf(
                Step(id = 20, name = "Två", order = 2, stepRecipe = StepRecipeRef(7, "Deg")),
                Step(id = 10, name = "Ett", order = 1, showAsHeader = true, showIngredientsTable = false),
            ),
        )
        val display = RecipeDisplay.build(recipe, 2.0, preferences, NumberStyle.English)
        assertEquals(listOf(10L, 20L), display.steps.map { it.id })
        assertEquals(listOf(1, 2), display.steps.map { it.number })
        assertTrue(display.steps[0].showAsHeader)
        assertEquals(false, display.steps[0].showIngredientsTable)
        assertEquals(StepRecipeRef(7, "Deg"), display.steps[1].stepRecipe)
    }

    @Test
    fun invalidServingsShowTheRecipeAsWritten() {
        val pasta = RecipeFixtures.recipe("recipe-svamppasta.json")
        for (servings in listOf(0.0, -2.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            val display = RecipeDisplay.build(pasta, servings, preferences, NumberStyle.Swedish)
            assertEquals(1.0, display.factor, "$servings")
            assertEquals(4.0, display.servings, "$servings")
        }
    }

    @Test
    fun everyRecordedRecipeBuildsAtManyServings() {
        for (file in RecipeFixtures.recipeFiles) {
            val recipe = RecipeFixtures.recipe(file)
            for (servings in listOf(0.5, 1.0, 2.0, 3.0, 7.0, 100.0)) {
                for (style in listOf(NumberStyle.Swedish, NumberStyle.English, NumberStyle.Web)) {
                    val display = RecipeDisplay.build(recipe, servings, UserPreferences(useFractions = servings > 2.0), style)
                    assertEquals(ServingsScaling.factor(recipe, servings), display.factor)
                    assertEquals(recipe.ingredients.size, display.ingredients.size, file)
                }
            }
        }
    }
}
