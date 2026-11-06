package casa.moller.tandem.core.sync.recipeedit

import casa.moller.tandem.core.database.mapper.RecipeNutrition
import casa.moller.tandem.core.model.recipe.NutritionValue
import casa.moller.tandem.core.testing.fixtures.TandoorFixtures
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Fas 4 review (fix-data), plan §10 Fas 3 "Klart när" (the values per serving match the web's for
 * five recipes): `RecipeNutrition` on recipes a real Tandoor answered (`Phase4NutritionContractTest`,
 * `fas4/nutrition/`), not on JSON written here. The expected values are worked out by hand from what
 * that test seeds: per 100 g, flour 340 kcal and 10 g protein, milk 60 kcal and no protein.
 */
class RecipeNutritionFixtureTest {
    private fun energy(amount: Double) = NutritionValue(ENERGY, amount, "kcal")

    private fun protein(amount: Double) = NutritionValue(PROTEIN, amount, "g")

    private fun check(slug: String, servings: Int, vararg expected: NutritionValue) {
        for (version in listOf("2.6.13", "2.7.1")) {
            val body = TandoorFixtures.exchange("fas4/nutrition/recipe-$slug", version).body
            assertEquals("[$version] $slug", expected.toSet(), RecipeNutrition.valuesOf(body, servings).toSet())
        }
    }

    /** 200 g flour and 400 g milk for 4: 920 kcal and 20 g protein, the protein a lower bound (the milk has none). */
    @Test
    fun aRecipeWithAFoodThatLacksATypeShowsWhatIsKnown() = check("pannkakor", 4, energy(230.0), protein(5.0))

    /** 100 g flour for 2. */
    @Test
    fun aSmallRecipeIsDividedByItsServings() = check("mjolgrot", 2, energy(170.0), protein(5.0))

    /** 1200 g flour for 24. */
    @Test
    fun manyServings() = check("bullar", 24, energy(170.0), protein(5.0))

    /** 150 g flour for 3. */
    @Test
    fun anOddNumberOfServings() = check("udda", 3, energy(170.0), protein(5.0))

    /** The recipe's own value is per serving and wins over the foods' sums (1020 kcal here). */
    @Test
    fun theRecipesOwnValuesWinOverTheFoods() = check("egna-varden", 2, energy(350.0))

    private companion object {
        const val ENERGY = "Tandem energi"
        const val PROTEIN = "Tandem protein"
    }
}
