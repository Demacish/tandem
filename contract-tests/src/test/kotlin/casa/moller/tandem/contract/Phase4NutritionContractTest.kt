package casa.moller.tandem.contract

import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * Fas 4 review (fix-data): what plan §10 Fas 3 "Klart när" asks of nutrition, on a real server.
 * Property types, foods with properties per 100 g and five recipes are created through the API, and
 * each recipe is read as the app reads it (`GET /api/recipe/{id}/`): the shapes and sums of
 * `properties` and `food_properties` that `RecipeNutrition` (core:database) turns into values per
 * serving. `core:sync`'s `RecipeNutritionFixtureTest` runs it on these recordings, against values
 * worked out by hand from the seed below.
 *
 * Recordings go to `build/contract-recordings/<tag>/fas4/nutrition/` and are committed to
 * `api-schema/fixtures/<version>/fas4/nutrition/`. Soft checks as in the other phases: every fact is
 * recorded and the last step compares [EXPECTED].
 *
 * Skipped unless TANDOOR_FAS1_TOKEN is set. Never run against a production server.
 */
@EnabledIfEnvironmentVariable(named = "TANDOOR_FAS1_TOKEN", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class Phase4NutritionContractTest {
    private val env = ContractEnv
    private val zone: ZoneId = ZoneId.of("Europe/Stockholm")
    private val today: LocalDate = LocalDate.now(zone)
    private val sanitizer = Sanitizer(listOf(env.password, env.noSpacePassword), runToday = today)
    private val proxyHeaders = mapOf("Host" to ContractEnv.PUBLIC_HOST, "X-Forwarded-Proto" to "https")
    private val recordDir = File(env.recordDir, "fas4/nutrition")
    private val http = TandoorHttp(env.baseUrl, sanitizer, recordDir, numbered = false, defaultHeaders = proxyHeaders)
    private val facts = Facts()
    private val mismatches = mutableListOf<String>()

    private val types = linkedMapOf<String, JsonObject>()
    private val foods = mutableListOf<Long>()
    private val recipes = mutableListOf<Long>()

    @BeforeAll
    fun setUp() {
        assertTrue(env.fas1Token.isNotBlank(), "TANDOOR_FAS1_TOKEN must be set")
        sanitizer.fakeTokenFor(env.fas1Token)
        recordDir.deleteRecursively()
        recordDir.mkdirs()
        facts["tandoorTag"] = env.tag
        facts["runDay"] = today.toString()
    }

    @AfterAll
    fun cleanUp() {
        recipes.forEach { api("cleanup-recipe", "DELETE", "/api/recipe/$it/", record = false) }
        foods.forEach { api("cleanup-food", "DELETE", "/api/food/$it/", record = false) }
        types.values.forEach { type -> type.longAt("id")?.let { api("cleanup-type", "DELETE", "/api/property-type/$it/", record = false) } }
        facts["mismatches"] = mismatches
        facts.write(File(recordDir, "observed-facts.json"), sanitizer)
    }

    @Test
    @Order(10)
    fun propertyTypesAndFoods() {
        for ((name, unit) in listOf(ENERGY to "kcal", PROTEIN to "g")) {
            val created = api("property-type-${unit.lowercase()}", "POST", "/api/property-type/", obj("name" to name, "unit" to unit))
            observe("type.$name.status", created.status)
            (created.json as? JsonObject)?.let { types[name] = it }
        }
        // Per 100 g: the flour has both types, the milk no protein (`missing_value`).
        createFood("food-flour", FLOUR, ENERGY to 340.0, PROTEIN to 10.0)
        createFood("food-milk", MILK, ENERGY to 60.0)
    }

    @Test
    @Order(20)
    fun recipesWithTheirSums() {
        recipe("pannkakor", servings = 4, lines = listOf(FLOUR to 200.0, MILK to 400.0))
        recipe("mjolgrot", servings = 2, lines = listOf(FLOUR to 100.0))
        recipe("bullar", servings = 24, lines = listOf(FLOUR to 1200.0))
        recipe("udda", servings = 3, lines = listOf(FLOUR to 150.0))
        // The recipe's own values (Tandoor's property editor), which are per serving, beside foods that have sums.
        recipe("egna-varden", servings = 2, lines = listOf(FLOUR to 300.0), own = listOf(ENERGY to 350.0))
    }

    @Test
    @Order(90)
    fun compareWithBaseline() {
        for ((key, expected) in EXPECTED) {
            if (facts[key] != Facts.toJson(expected)) mismatches += "$key: expected ${Facts.toJson(expected)}, observed ${facts[key]}"
        }
        assertTrue(mismatches.isEmpty(), "Observed facts differ from the nutrition baseline:\n" + mismatches.joinToString("\n"))
    }

    private fun createFood(name: String, food: String, vararg values: Pair<String, Double>) {
        val body = obj(
            "name" to food,
            "properties_food_amount" to 100,
            "properties_food_unit" to obj("name" to "g"),
            "properties" to JsonArray(values.map { (type, amount) -> property(type, amount) }),
        )
        val created = api(name, "POST", "/api/food/", body)
        observe("$name.status", created.status)
        val saved = created.json as? JsonObject
        saved?.longAt("id")?.let { foods += it }
        observe("$name.propertyCount", saved?.arrAt("properties")?.size)
    }

    private fun property(type: String, amount: Double): JsonObject {
        val saved = types[type]
        return obj("property_type" to obj("id" to saved?.longAt("id"), "name" to type), "property_amount" to amount)
    }

    private fun recipe(slug: String, servings: Int, lines: List<Pair<String, Double>>, own: List<Pair<String, Double>> = emptyList()) {
        val ingredients = lines.mapIndexed { order, (food, grams) ->
            obj(
                "food" to obj("name" to food),
                "unit" to obj("name" to "g"),
                "amount" to grams,
                "note" to "",
                "order" to order,
                "is_header" to false,
                "no_amount" to false,
            )
        }
        val step = obj(
            "name" to "",
            "instruction" to "Blanda.",
            "time" to 0,
            "order" to 0,
            "show_as_header" to false,
            "show_ingredients_table" to true,
            "ingredients" to JsonArray(ingredients),
        )
        val body = obj(
            "name" to "Tandem näring $slug",
            "description" to "",
            "servings" to servings,
            "servings_text" to "",
            "working_time" to 0,
            "waiting_time" to 0,
            "internal" to true,
            "keywords" to JsonArray(emptyList()),
            "steps" to JsonArray(listOf(step)),
            "properties" to JsonArray(own.map { (type, amount) -> property(type, amount) }),
        )
        val created = api("recipe-create-$slug", "POST", "/api/recipe/", body)
        observe("recipe.$slug.createStatus", created.status)
        val id = (created.json as? JsonObject)?.longAt("id") ?: return
        recipes += id
        val read = api("recipe-$slug", "GET", "/api/recipe/$id/")
        val recipe = read.json as? JsonObject ?: return
        observe("recipe.$slug.servings", recipe.longAt("servings"))
        observe("recipe.$slug.properties", recipe.arrAt("properties").objects().map { it.doubleAt("property_amount") })
        val sums = recipe.objAt("food_properties")?.values.orEmpty().mapNotNull { it as? JsonObject }
        observe("recipe.$slug.foodProperties", sums.associate { (it.stringAt("name") ?: "?") to it.doubleAt("total_value") })
        observe("recipe.$slug.missing", sums.filter { it.boolAt("missing_value") == true }.mapNotNull { it.stringAt("name") })
    }

    private fun api(name: String, method: String, path: String, body: JsonElement? = null, record: Boolean = true): Exchange = http.call(
        name,
        method,
        path,
        bearer = env.fas1Token,
        body = (body ?: JsonObject(emptyMap()).takeIf { method in BODY_METHODS })?.toString()?.toRequestBody(JSON),
        recordBody = body,
        record = record,
    )

    private fun observe(key: String, value: Any?) {
        facts[key] = value
    }

    private fun obj(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(pairs.associate { (k, v) -> k to Facts.toJson(v) })

    private companion object {
        val JSON = "application/json".toMediaType()
        val BODY_METHODS = setOf("POST", "PUT", "PATCH")
        const val ENERGY = "Tandem energi"
        const val PROTEIN = "Tandem protein"
        const val FLOUR = "tandem-vetemjol"
        const val MILK = "tandem-mjolk"

        /** The sums of the whole recipe, worked out by hand from the values per 100 g above. */
        val EXPECTED: Map<String, Any?> = linkedMapOf(
            "recipe.pannkakor.foodProperties" to mapOf(ENERGY to 920.0, PROTEIN to 20.0),
            "recipe.pannkakor.missing" to listOf(PROTEIN),
            "recipe.mjolgrot.foodProperties" to mapOf(ENERGY to 340.0, PROTEIN to 10.0),
            "recipe.bullar.foodProperties" to mapOf(ENERGY to 4080.0, PROTEIN to 120.0),
            "recipe.udda.foodProperties" to mapOf(ENERGY to 510.0, PROTEIN to 15.0),
            "recipe.egna-varden.properties" to listOf(350.0),
        )
    }
}
