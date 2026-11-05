package casa.moller.tandem.core.database

import casa.moller.tandem.core.database.mapper.RecipeNutrition
import casa.moller.tandem.core.model.recipe.NutritionValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fas 3 (näringsvärden): the values read from a stored recipe's raw JSON. */
class RecipeNutritionTest {
    @Test
    fun recipePropertiesArePerServingAsTheyAre() {
        val json = """
            {"id": 1, "servings": 4, "properties": [
              {"id": 5, "property_amount": 520.0, "property_type": {"id": 1, "name": "Energi", "unit": "kcal"}},
              {"id": 6, "property_amount": 18, "property_type": {"id": 2, "name": "Protein", "unit": "g"}},
              {"id": 7, "property_amount": null, "property_type": {"id": 3, "name": "Salt", "unit": "g"}}
            ], "food_properties": {"1": {"name": "Energi", "unit": "kcal", "total_value": 9999, "missing_value": false}}}
        """
        assertEquals(
            listOf(NutritionValue("Energi", 520.0, "kcal"), NutritionValue("Protein", 18.0, "g")),
            RecipeNutrition.valuesOf(json, servings = 4),
        )
    }

    @Test
    fun foodPropertiesAreTheRecipesSumsDividedByTheServings() {
        val json = """
            {"id": 1, "servings": 4, "properties": [], "food_properties": {
              "1": {"id": 1, "name": "Energi", "unit": "kcal", "total_value": 2080.0, "missing_value": false},
              "2": {"id": 2, "name": "Protein", "unit": "g", "total_value": 0, "missing_value": false},
              "3": {"id": 3, "name": "Fett", "unit": "g", "total_value": 10, "missing_value": true}
            }}
        """
        assertEquals(
            listOf(NutritionValue("Energi", 520.0, "kcal"), NutritionValue("Fett", 2.5, "g")),
            RecipeNutrition.valuesOf(json, servings = 4),
        )
        assertEquals(listOf(NutritionValue("Energi", 2080.0, "kcal"), NutritionValue("Fett", 10.0, "g")), RecipeNutrition.valuesOf(json, 0))
    }

    /**
     * Plan §10 "Klart när" (Fas 3, näring): five recipes in the shapes Tandoor stores them, each with
     * the per-serving value its web "Properties" view shows (food sums divided by the recipe's
     * servings, or the recipe's own per-serving properties).
     */
    @Test
    fun fiveRecipesMatchTheWebsPerServingValues() {
        data class Case(val json: String, val servings: Int, val expected: List<NutritionValue>)
        fun food(id: Int, name: String, unit: String, total: Double, missing: Boolean = false) =
            """"$id": {"id": $id, "name": "$name", "unit": "$unit", "total_value": $total, "missing_value": $missing}"""
        fun recipe(servings: Int, vararg foods: String) =
            """{"servings": $servings, "properties": [], "food_properties": {${foods.joinToString(", ")}}}"""
        val cases = listOf(
            // Pannkakor, 4 portions, values from the foods.
            Case(
                recipe(4, food(1, "Energy", "kcal", 1680.0), food(2, "Protein", "g", 60.0)),
                4,
                listOf(NutritionValue("Energy", 420.0, "kcal"), NutritionValue("Protein", 15.0, "g")),
            ),
            // Fiskgratäng, 6 portions, one food without fat (shown anyway, like the web).
            Case(
                recipe(6, food(1, "Energy", "kcal", 2400.0), food(3, "Fat", "g", 90.0, missing = true)),
                6,
                listOf(NutritionValue("Energy", 400.0, "kcal"), NutritionValue("Fat", 15.0, "g")),
            ),
            // Kanelbullar, 24 pieces.
            Case(
                recipe(24, food(1, "Energy", "kcal", 4800.0), food(4, "Sugar", "g", 0.0)),
                24,
                listOf(NutritionValue("Energy", 200.0, "kcal")),
            ),
            // Linsgryta: the recipe's own properties (per serving) win over the foods.
            Case(
                """{"servings": 2, "properties": [{"property_amount": 350, "property_type": {"name": "Energy", "unit": "kcal"}}],
                   "food_properties": {${food(1, "Energy", "kcal", 1000.0)}}}""",
                2,
                listOf(NutritionValue("Energy", 350.0, "kcal")),
            ),
            // Smoothie, 1 portion, energy in kJ.
            Case(
                recipe(1, food(5, "Energi", "kJ", 1255.2), food(6, "Fiber", "g", 7.5)),
                1,
                listOf(NutritionValue("Energi", 1255.2, "kJ"), NutritionValue("Fiber", 7.5, "g")),
            ),
        )
        for (case in cases) assertEquals(case.json, case.expected, RecipeNutrition.valuesOf(case.json, case.servings))
    }

    @Test
    fun anythingElseIsEmpty() {
        assertTrue(RecipeNutrition.valuesOf(null, 4).isEmpty())
        assertTrue(RecipeNutrition.valuesOf("not json", 4).isEmpty())
        assertTrue(RecipeNutrition.valuesOf("""{"properties": [], "food_properties": {}}""", 4).isEmpty())
        assertTrue(RecipeNutrition.valuesOf("""{"properties": "x", "food_properties": []}""", 4).isEmpty())
    }
}
