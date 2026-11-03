package casa.moller.tandem.core.database.mapper

import casa.moller.tandem.core.model.recipe.NutritionValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Fas 3 (näringsvärden): the nutrition values of a stored recipe, read from its raw JSON (the
 * whole `GET /api/recipe/{id}/` body is stored, plan 5.7, so no schema change is needed):
 *
 * - `properties: [{property_amount, property_type: {name, unit}}]`, the values entered on the
 *   recipe itself (Tandoor's property editor; PER SERVING, as its "Properties for one serving"),
 *   when there are any;
 * - else `food_properties: {"<type id>": {name, unit, total_value, missing_value}}`, Tandoor's
 *   sums over the ingredients' foods for the WHOLE recipe, divided by [servings]; a type whose
 *   value is 0 (no food carries it) is left out, one with `missing_value` is shown anyway (some
 *   foods lack it: a lower bound, like the web).
 *
 * Any JSON the app does not understand gives an empty list, never an error.
 */
object RecipeNutrition {
    private val json = Json { ignoreUnknownKeys = true }

    fun valuesOf(rawJson: String?, servings: Int): List<NutritionValue> {
        val body = rawJson?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() } as? JsonObject ?: return emptyList()
        val properties = (body["properties"] as? JsonArray)?.mapNotNull { it.toRecipeProperty() }.orEmpty()
        if (properties.isNotEmpty()) return properties
        val perRecipe = (body["food_properties"] as? JsonObject)?.values?.mapNotNull { it.toFoodProperty() }.orEmpty()
        val portions = servings.coerceAtLeast(1).toDouble()
        return perRecipe.map { it.copy(amount = it.amount / portions) }
    }

    private fun JsonElement.toRecipeProperty(): NutritionValue? {
        val property = this as? JsonObject ?: return null
        val type = property["property_type"] as? JsonObject ?: return null
        val name = type.string("name") ?: return null
        val amount = property.double("property_amount") ?: return null
        return NutritionValue(name = name, amount = amount, unit = type.string("unit").orEmpty())
    }

    private fun JsonElement.toFoodProperty(): NutritionValue? {
        val property = this as? JsonObject ?: return null
        val name = property.string("name") ?: return null
        val total = property.double("total_value") ?: return null
        if (total <= 0.0 && property.bool("missing_value") != true) return null
        return NutritionValue(name = name, amount = total, unit = property.string("unit").orEmpty())
    }

    private fun JsonObject.string(field: String): String? = (this[field] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.double(field: String): Double? = (this[field] as? JsonPrimitive)?.doubleOrNull

    private fun JsonObject.bool(field: String): Boolean? = (this[field] as? JsonPrimitive)?.booleanOrNull
}
