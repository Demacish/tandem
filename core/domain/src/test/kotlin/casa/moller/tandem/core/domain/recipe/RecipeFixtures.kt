package casa.moller.tandem.core.domain.recipe

import casa.moller.tandem.core.model.recipe.Food
import casa.moller.tandem.core.model.recipe.Ingredient
import casa.moller.tandem.core.model.recipe.MeasurementUnit
import casa.moller.tandem.core.model.recipe.Recipe
import casa.moller.tandem.core.model.recipe.Step
import casa.moller.tandem.core.model.recipe.UnitConversion
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * The recorded Tandoor 2.6.13 fixtures (`api-schema/fixtures/2.6.13/fas1/`), read from the
 * directory the build passes as `tandem.fixturesDir`. A small test-only mapper turns a recorded
 * `GET /api/recipe/{id}/` body into the app's [Recipe], with the same field meanings as the data
 * layer (FACTS.md "Fas 1 d").
 */
internal object RecipeFixtures {
    val fas1: File = File(System.getProperty("tandem.fixturesDir") ?: "../../api-schema/fixtures", "2.6.13/fas1")

    /** The 11 recorded recipe details (`recipe-<slug>.json`). */
    val recipeFiles: List<String> = listOf(
        "recipe-pyttipanna.json",
        "recipe-svamppasta.json",
        "recipe-pannkakor.json",
        "recipe-kanelbullar.json",
        "recipe-kottbullar.json",
        "recipe-shakshuka.json",
        "recipe-artsoppa.json",
        "recipe-gronsakssoppa.json",
        "recipe-smorgastarta.json",
        "recipe-appelkaka.json",
        "recipe-rarakor.json",
    )

    fun file(name: String): File = File(fas1, name)

    /** The response body of a recorded exchange. */
    fun body(name: String): JsonObject {
        val recording = Json.parseToJsonElement(file(name).readText()).jsonObject
        val response = recording["response"]?.jsonObject ?: error("$name has no response")
        return response["body"] as? JsonObject ?: error("$name has no JSON object body")
    }

    /** The recorded recipe [name] as the app's model. */
    fun recipe(name: String): Recipe = recipe(body(name))

    fun recipe(json: JsonObject): Recipe = Recipe(
        id = json.long("id"),
        name = json.string("name").orEmpty(),
        servings = json.long("servings").toInt(),
        servingsText = json.string("servings_text").orEmpty(),
        workingMinutes = json.long("working_time").toInt(),
        waitingMinutes = json.long("waiting_time").toInt(),
        rating = json.double("rating"),
        steps = json.objects("steps").map { step ->
            Step(
                id = step.long("id"),
                name = step.string("name").orEmpty(),
                instruction = step.string("instruction").orEmpty(),
                timeMinutes = step.long("time").toInt(),
                order = step.long("order").toInt(),
                ingredients = step.objects("ingredients").map(::ingredient),
                showAsHeader = step.boolean("show_as_header"),
                showIngredientsTable = step.boolean("show_ingredients_table"),
            )
        },
    )

    private fun ingredient(json: JsonObject): Ingredient {
        val food = json.obj("food")
        val unit = json.obj("unit")
        return Ingredient(
            id = json.long("id"),
            food = food?.let { Food(id = it.long("id"), name = it.string("name").orEmpty(), pluralName = it.string("plural_name")) },
            unit = unit?.let {
                MeasurementUnit(id = it.long("id"), name = it.string("name").orEmpty(), pluralName = it.string("plural_name"))
            },
            amount = json.double("amount") ?: 0.0,
            note = json.string("note"),
            isHeader = json.boolean("is_header"),
            noAmount = json.boolean("no_amount"),
            originalText = json.string("original_text"),
            order = json.long("order").toInt(),
            conversions = json.objects("conversions").map { conversion ->
                UnitConversion(amount = conversion.double("amount") ?: 0.0, unitName = conversion.string("unit").orEmpty())
            },
        )
    }

    private fun JsonObject.primitive(key: String): JsonPrimitive? = (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }

    fun JsonObject.string(key: String): String? = primitive(key)?.contentOrNull

    fun JsonObject.long(key: String): Long = primitive(key)?.longOrNull ?: 0L

    fun JsonObject.double(key: String): Double? = primitive(key)?.doubleOrNull

    fun JsonObject.boolean(key: String): Boolean = primitive(key)?.booleanOrNull ?: false

    fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    fun JsonObject.objects(key: String): List<JsonObject> = (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

    fun JsonElement.asObject(): JsonObject = this as? JsonObject ?: error("not an object: $this")
}
