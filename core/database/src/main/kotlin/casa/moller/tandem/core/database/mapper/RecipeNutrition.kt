package casa.moller.tandem.core.database.mapper

import casa.moller.tandem.core.model.recipe.NutritionValue
import casa.moller.tandem.core.model.recipe.RecipeProperty
import casa.moller.tandem.core.model.recipe.StepFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

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
        val body = parse(rawJson) ?: return emptyList()
        val properties = (body["properties"] as? JsonArray)?.mapNotNull { it.toRecipeProperty() }.orEmpty()
        if (properties.isNotEmpty()) return properties
        val perRecipe = (body["food_properties"] as? JsonObject)?.values?.mapNotNull { it.toFoodProperty() }.orEmpty()
        val portions = servings.coerceAtLeast(1).toDouble()
        return perRecipe.map { it.copy(amount = it.amount / portions) }
    }

    /**
     * Fas 4 (#22): the values entered on the recipe itself, for the editor (a value without an amount
     * is left out, as it is when shown). An unsent edit's stored body has them too (`RecipeEditPayloads`).
     */
    fun propertiesOf(rawJson: String?): List<RecipeProperty> {
        val properties = parse(rawJson)?.get("properties") as? JsonArray ?: return emptyList()
        return properties.mapNotNull { element ->
            val property = element as? JsonObject ?: return@mapNotNull null
            val type = property["property_type"] as? JsonObject ?: return@mapNotNull null
            RecipeProperty(
                id = property.long("id"),
                typeId = type.long("id") ?: return@mapNotNull null,
                typeName = type.string("name") ?: return@mapNotNull null,
                unit = type.string("unit").orEmpty(),
                amount = property.double("property_amount") ?: return@mapNotNull null,
            )
        }
    }

    /**
     * Fas 4 (#22): each step's file, by the step's position after sorting by `order` (as the step
     * rows are stored; a step not sent yet has no id to go by). Room has only a picture's link, so the
     * stored JSON is what knows a file's id and name.
     */
    fun stepFilesOf(rawJson: String?): List<StepFile?> {
        val steps = parse(rawJson)?.get("steps") as? JsonArray ?: return emptyList()
        return steps.mapNotNull { it as? JsonObject }.sortedBy { (it["order"] as? JsonPrimitive)?.intOrNull ?: 0 }.map { step ->
            (step["file"] as? JsonObject)?.let { file ->
                file.string("name")?.let { name -> StepFile(id = file.long("id"), name = name, uri = file.string("path")) }
            }
        }
    }

    private fun parse(rawJson: String?): JsonObject? =
        rawJson?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() } as? JsonObject

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

    private fun JsonObject.long(field: String): Long? = (this[field] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.double(field: String): Double? = (this[field] as? JsonPrimitive)?.doubleOrNull

    private fun JsonObject.bool(field: String): Boolean? = (this[field] as? JsonPrimitive)?.booleanOrNull
}
