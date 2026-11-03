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

    @Test
    fun anythingElseIsEmpty() {
        assertTrue(RecipeNutrition.valuesOf(null, 4).isEmpty())
        assertTrue(RecipeNutrition.valuesOf("not json", 4).isEmpty())
        assertTrue(RecipeNutrition.valuesOf("""{"properties": [], "food_properties": {}}""", 4).isEmpty())
        assertTrue(RecipeNutrition.valuesOf("""{"properties": "x", "food_properties": []}""", 4).isEmpty())
    }
}
