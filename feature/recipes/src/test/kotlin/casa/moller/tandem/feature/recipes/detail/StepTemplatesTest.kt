package casa.moller.tandem.feature.recipes.detail

import casa.moller.tandem.core.model.preferences.UserPreferences
import casa.moller.tandem.core.model.recipe.Food
import casa.moller.tandem.core.model.recipe.Ingredient
import casa.moller.tandem.core.model.recipe.MeasurementUnit
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/** The stand-in for Tandoor's step templates (FACTS.md "Fas 1 d"). */
class StepTemplatesTest {

    private val sv = Locale.forLanguageTag("sv")
    private val prefs = UserPreferences.Default
    private val grams = MeasurementUnit(1, "g")
    private val stepIngredients = listOf(
        ingredientItem(
            Ingredient(id = 1, food = Food(1, "champinjon", "champinjoner"), unit = grams, amount = 250.0, note = "skivade"),
            factor = 2.0,
        ),
        ingredientItem(Ingredient(id = 2, food = Food(2, "salt"), noAmount = true), factor = 2.0),
    )

    private fun render(text: String, factor: Double = 2.0) = StepTemplates.render(text, stepIngredients, factor, sv, prefs)

    @Test
    fun textWithoutTemplatesIsUnchanged() {
        assertEquals("Koka **pastan**.", render("Koka **pastan**."))
    }

    @Test
    fun scaleMultipliesWithTheServingsFactor() {
        assertEquals("Häll i 200 ml vatten.", render("Häll i {{ scale(100) }} ml vatten."))
        assertEquals("Häll i 1,5 dl.", render("Häll i {{scale(0,75)}} dl."))
    }

    @Test
    fun ingredientReferencesUseTheScaledRows() {
        assertEquals("Stek 500 g champinjoner.", render("Stek {{ ingredients[0] }}."))
        val parts = "{{ ingredients[0].amount }}|{{ ingredients[0].unit }}|{{ ingredients[0].food }}|{{ ingredients[0].note }}"
        assertEquals("500|g|champinjoner|skivade", render(parts))
        assertEquals("Smaka av med salt.", render("Smaka av med {{ ingredients[1] }}."))
    }

    @Test
    fun unknownCodeLosesItsBracesAndMissingRowsVanish() {
        assertEquals("a b", render("a {{ ingredients[7] }}b"))
        assertEquals("x foo(1) y", render("x {{ foo(1) }} y"))
    }
}
