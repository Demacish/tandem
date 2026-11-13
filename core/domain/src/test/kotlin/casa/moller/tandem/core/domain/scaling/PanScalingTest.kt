package casa.moller.tandem.core.domain.scaling

import casa.moller.tandem.core.domain.format.NumberStyle
import casa.moller.tandem.core.domain.recipe.IngredientRow
import casa.moller.tandem.core.domain.recipe.RecipeDisplay
import casa.moller.tandem.core.domain.recipe.RecipeFixtures
import casa.moller.tandem.core.model.preferences.UserPreferences
import casa.moller.tandem.core.model.recipe.Recipe
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** Fas 5 (plan §8.1 "Pannstorlek"): the tin scales by area, rounded like Tandoor's web. */
class PanScalingTest {

    @ParameterizedTest(name = "{0} → {1}: {2}")
    @CsvSource(
        // The web: Math.round((new / old)² × 100) / 100.
        "24, 20, 0.69",
        "24, 24, 1.0",
        "20, 24, 1.44",
        "24, 26, 1.17",
        "24, 18, 0.56",
        "24, 12, 0.25",
        "24, 48, 4.0",
        "22, 20, 0.83",
        "9, 8, 0.79",
        // Half up: (10/8)² = 1.5625 → 1.56; (15/10)² = 2.25 exactly.
        "8, 10, 1.56",
        "10, 15, 2.25",
        // Not a tin: the recipe as written.
        "0, 20, 1.0",
        "-4, 20, 1.0",
        "24, 0, 1.0",
        // Never scaled away entirely.
        "100, 1, 0.01",
    )
    fun areaFactorLikeTheWeb(original: Int, chosen: Int, expected: Double) {
        assertEquals(expected, PanScaling.areaFactor(original, chosen), 1e-12)
    }

    @Test
    fun aRecipeWithoutDiameterHasNoTin() {
        val plain = Recipe(id = 1, name = "Soppa")
        assertFalse(PanScaling.hasTin(plain))
        assertEquals(1.0, PanScaling.factor(plain, 20))
        assertEquals(0, PanScaling.current(plain, 20))
        assertTrue(PanScaling.hasTin(plain.copy(diameter = 24)))
    }

    @Test
    fun theChosenTinIsUsedWhenValid() {
        val cake = Recipe(id = 1, name = "Kladdkaka", diameter = 24, diameterText = "cm")
        assertEquals(24, PanScaling.current(cake, null))
        assertEquals(20, PanScaling.current(cake, 20))
        assertEquals(24, PanScaling.current(cake, 0))
        assertEquals(24, PanScaling.current(cake, PanScaling.MAX_DIAMETER + 1))
        assertEquals(0.69, PanScaling.factor(cake, 20), 1e-12)
        assertEquals(1.0, PanScaling.factor(cake, null))
    }

    @Test
    fun stepperStaysInRange() {
        assertEquals(25, PanScaling.larger(24))
        assertEquals(23, PanScaling.smaller(24))
        assertEquals(PanScaling.MIN_DIAMETER, PanScaling.smaller(PanScaling.MIN_DIAMETER))
        assertEquals(PanScaling.MAX_DIAMETER, PanScaling.larger(PanScaling.MAX_DIAMETER))
    }

    @Test
    fun theUnitIsTheRecipesOrCentimetres() {
        assertEquals("cm", PanScaling.unit(Recipe(id = 1, name = "a", diameter = 24)))
        assertEquals("in", PanScaling.unit(Recipe(id = 1, name = "a", diameter = 9, diameterText = " in ")))
    }

    @ParameterizedTest(name = "{0} servings × {1} = {2}")
    @CsvSource(
        // Tandoor's web sends servings × factor when the recipe goes on the list in another tin.
        "4.0, 0.69, 2.76",
        "8.0, 0.69, 5.52",
        "3.0, 1.17, 3.51",
        "2.5, 1.44, 3.6",
        "1.0, 0.01, 0.01",
        "7.0, 0.83, 5.81",
        // The recipe's own tin, or no usable factor: the servings as they are.
        "4.0, 1.0, 4.0",
        "4.0, 0.0, 4.0",
        "4.0, -1.0, 4.0",
        "4.0, NaN, 4.0",
    )
    fun listServingsAreTheServingsTimesTheTin(servings: Double, factor: Double, expected: Double) {
        assertEquals(expected, PanScaling.listServings(servings, factor))
    }

    @Test
    fun listServingsAreRoundedToFourDecimals() {
        // 3 × 0.83 is 2.4899999999999998 in binary; Tandoor gets 2.49.
        assertEquals(2.49, PanScaling.listServings(3.0, 0.83))
        assertEquals(0.3333, PanScaling.listServings(1.0 / 3.0, 0.9999999))
    }

    @Test
    fun theTinMultipliesTheServingsInTheDisplay() {
        // The pasta is written for 4 servings with 400 g pasta (the recorded 2.6.13 recipe).
        val pasta = RecipeFixtures.recipe("recipe-svamppasta.json").copy(diameter = 24, diameterText = "cm")
        val preferences = UserPreferences.Default

        val smaller = RecipeDisplay.build(pasta, 4.0, preferences, NumberStyle.Swedish, extraFactor = PanScaling.factor(pasta, 20))
        assertEquals(0.69, smaller.factor, 1e-12)
        assertEquals(4.0, smaller.servings)
        assertEquals("276 g pasta", (smaller.steps[1].ingredients[0] as IngredientRow.Item).text())

        // Twice the servings in the smaller tin: 2 × 0.69.
        val double = RecipeDisplay.build(pasta, 8.0, preferences, NumberStyle.Swedish, extraFactor = PanScaling.factor(pasta, 20))
        assertEquals(1.38, double.factor, 1e-12)
        assertEquals("552 g pasta", (double.steps[1].ingredients[0] as IngredientRow.Item).text())

        // A bad factor is ignored (the recipe at its servings).
        val ignored = RecipeDisplay.build(pasta, 4.0, preferences, NumberStyle.Swedish, extraFactor = Double.NaN)
        assertEquals(1.0, ignored.factor)
    }
}
