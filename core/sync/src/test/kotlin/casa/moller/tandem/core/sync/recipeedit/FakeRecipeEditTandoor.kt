package casa.moller.tandem.core.sync.recipeedit

import casa.moller.tandem.core.sync.testing.FakeTandoorServer
import casa.moller.tandem.core.testing.fixtures.TandoorFixtures
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest

/**
 * A stateful Tandoor for the recipe editor's tests, answering in the recorded shapes of
 * [version] (the recipes of `fas1/`, the keywords of `fas1/keyword-list`) with the facts of FACTS
 * "Fas 3" and the serializers of Tandoor 2.6 (drf-writable-nested): `POST /api/recipe/` needs
 * `steps` and creates foods, units and keywords by name; a `PATCH` with `steps` updates the steps
 * and ingredients that carry their `id`, creates those without one, deletes the omitted ones and
 * bumps `updated_at`; `DELETE` answers 204 and the recipe is gone (404) afterwards; the photo is a
 * `PUT`. A guest gets 403 on every write.
 */
internal class FakeRecipeEditTandoor(private val version: String) {
    private val recipes = LinkedHashMap<Long, JsonObject>()
    private val keywords = LinkedHashMap<Long, String>()
    private var nextRecipeId = 800L
    private var nextStepId = 900L
    private var nextIngredientId = 900L
    private var nextKeywordId = 700L
    private var nextFoodId = 700L
    private var nextUnitId = 700L
    private var bumps = 0

    /** Every request this fake answered, as `METHOD path?query` with its body. */
    val calls: MutableList<Pair<String, String>> = CopyOnWriteArrayList()

    /** Whether writes are refused with 403 (a guest). */
    var guest: Boolean = false

    init {
        val listed = element("fas1/keyword-list").jsonObject["results"] as JsonArray
        listed.mapNotNull { it as? JsonObject }.forEach { keyword ->
            keyword.long("id")?.let { keywords[it] = keyword.string("name").orEmpty() }
        }
        FakeTandoorServer.RECIPES.forEach { (id, name) ->
            recipes[id.toLong()] = Json.parseToJsonElement(TandoorFixtures.exchange(name, version).body!!).jsonObject
        }
    }

    fun install(server: FakeTandoorServer) {
        server.handler = { request -> answer(request) }
    }

    fun callsTo(method: String, path: String): List<Pair<String, String>> = calls.filter { (call, _) -> call.startsWith("$method $path") }

    @Synchronized
    fun recipe(id: Long): JsonObject? = recipes[id]

    @Synchronized
    fun stepsOf(id: Long): List<JsonObject> = (recipes[id]?.get("steps") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    @Synchronized
    fun recipeNamed(name: String): JsonObject? = recipes.values.firstOrNull { it.string("name") == name }

    /** Another member deleted the recipe on the web. */
    @Synchronized
    fun removeOnServer(id: Long) {
        recipes.remove(id)
    }

    /** Another member edits the recipe on the web: its description changes and `updated_at` moves on. */
    @Synchronized
    fun partnerEdits(id: Long, description: String) {
        val recipe = recipes[id] ?: return
        recipes[id] = bumped(JsonObject(recipe + ("description" to JsonPrimitive(description))))
    }

    @Synchronized
    fun answer(request: RecordedRequest): MockResponse? {
        val method = request.method
        val path = request.url.encodedPath
        val text = request.body?.utf8().orEmpty()
        val write = method in WRITES
        val response = when {
            write && guest -> json(403, JsonObject(mapOf("detail" to JsonPrimitive("You do not have permission to perform this action."))))

            path == RECIPES && method == "POST" -> create(parse(text))

            IMAGE.matches(path) && method == "PUT" -> image(IMAGE.find(path)!!.groupValues[1].toLong(), fromWeb = text.contains("name=\"image_url\""))

            RECIPE.matches(path) && method == "GET" ->
                recipes[RECIPE.find(path)!!.groupValues[1].toLong()]?.let { json(200, it) } ?: notFound()

            RECIPE.matches(path) && method == "PATCH" -> patch(RECIPE.find(path)!!.groupValues[1].toLong(), parse(text))

            RECIPE.matches(path) && method == "DELETE" -> {
                val id = RECIPE.find(path)!!.groupValues[1].toLong()
                if (recipes.remove(id) == null) notFound() else MockResponse.Builder().code(204).build()
            }

            path == RECIPES && method == "GET" && request.url.queryParameter("query") != null -> {
                val query = request.url.queryParameter("query").orEmpty()
                page(recipes.values.filter { it.string("name").orEmpty().contains(query, ignoreCase = true) }.map { overviewOf(it) })
            }

            else -> return null
        }
        val query = request.url.encodedQuery?.let { "?$it" }.orEmpty()
        calls += "$method $path$query" to text
        return response
    }

    // ------------------------------------------------------------------------------ writes

    private fun create(body: JsonObject): MockResponse {
        if (body["steps"] !is JsonArray) {
            return json(400, JsonObject(mapOf("steps" to JsonArray(listOf(JsonPrimitive("This field is required."))))))
        }
        keywordsError(body)?.let { return it }
        val id = nextRecipeId++
        val template = recipes.getValue(TEMPLATE_RECIPE)
        val created = JsonObject(
            template + mapOf(
                "id" to JsonPrimitive(id),
                "image" to JsonNull,
                "rating" to JsonNull,
                "last_cooked" to JsonNull,
                "created_at" to JsonPrimitive(NOW),
                "updated_at" to JsonPrimitive(NOW),
            ),
        )
        recipes[id] = applied(created, body, isCreate = true)
        return json(201, recipes.getValue(id))
    }

    private fun patch(id: Long, body: JsonObject): MockResponse {
        val recipe = recipes[id] ?: return notFound()
        keywordsError(body)?.let { return it }
        recipes[id] = bumped(applied(recipe, body, isCreate = false))
        return json(200, recipes.getValue(id))
    }

    /** The photo upload, or (Fas 3 import lane) a picture the server downloads from `image_url`: both end as the recipe's image. */
    private fun image(id: Long, fromWeb: Boolean): MockResponse {
        val recipe = recipes[id] ?: return notFound()
        recipes[id] = JsonObject(recipe + ("image" to JsonPrimitive("/media/recipes/recipe_$id.jpg")))
        val imageUrl = if (fromWeb) JsonPrimitive("/media/recipes/recipe_$id.jpg") else JsonNull
        return json(200, JsonObject(mapOf("image" to JsonPrimitive("/media/recipes/recipe_$id.jpg"), "image_url" to imageUrl)))
    }

    /** [body]'s fields applied to [recipe] as the writable-nested serializers do. */
    private fun applied(recipe: JsonObject, body: JsonObject, isCreate: Boolean): JsonObject {
        val fields = recipe.toMutableMap()
        for (field in SCALAR_FIELDS) body[field]?.let { fields[field] = it }
        body["keywords"]?.let { list ->
            fields["keywords"] = JsonArray((list as JsonArray).mapNotNull { it as? JsonObject }.map { keywordJson(resolveKeyword(it)) })
        }
        body["steps"]?.let { list ->
            val stored = (recipe["steps"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.associateBy { it.long("id") }
            fields["steps"] = JsonArray(
                (list as JsonArray).mapNotNull { it as? JsonObject }.map { step ->
                    val existing = if (isCreate) null else step.long("id")?.let { stored[it] }
                    stepJson(existing, step)
                },
            )
        }
        return JsonObject(fields)
    }

    private fun stepJson(existing: JsonObject?, step: JsonObject): JsonObject {
        val fields = (existing ?: emptyStep()).toMutableMap()
        if (existing == null) fields["id"] = JsonPrimitive(nextStepId++)
        for (field in STEP_FIELDS) step[field]?.let { fields[field] = it }
        step["ingredients"]?.let { list ->
            val stored = (existing?.get("ingredients") as? JsonArray).orEmpty()
                .mapNotNull { it as? JsonObject }
                .associateBy { it.long("id") }
            fields["ingredients"] = JsonArray(
                (list as JsonArray).mapNotNull { it as? JsonObject }.map { ingredient ->
                    ingredientJson(ingredient.long("id")?.let { stored[it] }, ingredient)
                },
            )
        }
        return JsonObject(fields)
    }

    private fun ingredientJson(existing: JsonObject?, ingredient: JsonObject): JsonObject {
        val fields = (existing ?: emptyIngredient()).toMutableMap()
        if (existing == null) fields["id"] = JsonPrimitive(nextIngredientId++)
        for (field in INGREDIENT_FIELDS) ingredient[field]?.let { fields[field] = it }
        ingredient["food"]?.let { food ->
            fields["food"] = (food as? JsonObject)?.let { foodJson(it.string("name").orEmpty()) } ?: JsonNull
        }
        ingredient["unit"]?.let { unit ->
            fields["unit"] = (unit as? JsonObject)?.let { unitJson(it.string("name").orEmpty()) } ?: JsonNull
        }
        fields["conversions"] = JsonArray(emptyList())
        return JsonObject(fields)
    }

    /** FACTS "Fas 3 a": a keyword without `name` (`{id}` alone) is a 400. */
    private fun keywordsError(body: JsonObject): MockResponse? {
        val keywords = (body["keywords"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return null
        if (keywords.all { it.string("name") != null }) return null
        val errors = keywords.map { keyword ->
            if (keyword.string("name") == null) {
                JsonObject(mapOf("name" to JsonArray(listOf(JsonPrimitive("This field is required.")))))
            } else {
                JsonObject(emptyMap())
            }
        }
        return json(400, JsonObject(mapOf("keywords" to JsonArray(errors))))
    }

    private fun resolveKeyword(keyword: JsonObject): Pair<Long, String> {
        keyword.long("id")?.let { id -> keywords[id]?.let { return id to it } }
        val name = keyword.string("name").orEmpty().trim()
        val known = keywords.entries.firstOrNull { it.value.equals(name, ignoreCase = true) }
        if (known != null) return known.key to known.value
        val id = nextKeywordId++
        keywords[id] = name
        return id to name
    }

    // ------------------------------------------------------------------------------ shapes

    private fun bumped(recipe: JsonObject): JsonObject =
        JsonObject(recipe + ("updated_at" to JsonPrimitive("2000-01-15T00:00:${(++bumps).toString().padStart(2, '0')}.000000+02:00")))

    private fun keywordJson(keyword: Pair<Long, String>): JsonObject = JsonObject(
        mapOf(
            "id" to JsonPrimitive(keyword.first),
            "name" to JsonPrimitive(keyword.second),
            "label" to JsonPrimitive(keyword.second),
            "description" to JsonPrimitive(""),
            "parent" to JsonNull,
            "numchild" to JsonPrimitive(0),
            "created_at" to JsonPrimitive(NOW),
            "updated_at" to JsonPrimitive(NOW),
            "full_name" to JsonPrimitive(keyword.second),
        ),
    )

    private fun foodJson(name: String): JsonObject {
        val template = (firstIngredient()["food"] as? JsonObject) ?: JsonObject(emptyMap())
        val known = allIngredients().mapNotNull { it["food"] as? JsonObject }
            .firstOrNull { it.string("name").equals(name, ignoreCase = true) }
        if (known != null) return known
        return JsonObject(template + mapOf("id" to JsonPrimitive(nextFoodId++), "name" to JsonPrimitive(name), "plural_name" to JsonNull))
    }

    private fun unitJson(name: String): JsonObject {
        val template = (firstIngredient()["unit"] as? JsonObject) ?: JsonObject(emptyMap())
        val known = allIngredients().mapNotNull { it["unit"] as? JsonObject }
            .firstOrNull { it.string("name").equals(name, ignoreCase = true) }
        if (known != null) return known
        return JsonObject(template + mapOf("id" to JsonPrimitive(nextUnitId++), "name" to JsonPrimitive(name), "plural_name" to JsonNull))
    }

    private fun allIngredients(): Sequence<JsonObject> = recipes.values.asSequence()
        .flatMap { (it["steps"] as? JsonArray).orEmpty().asSequence() }
        .flatMap { ((it as? JsonObject)?.get("ingredients") as? JsonArray).orEmpty().asSequence() }
        .mapNotNull { it as? JsonObject }

    private fun firstIngredient(): JsonObject = (stepsOf(TEMPLATE_RECIPE).first()["ingredients"] as JsonArray)[0] as JsonObject

    private fun emptyStep(): JsonObject = JsonObject(
        stepsOf(TEMPLATE_RECIPE).first() + mapOf(
            "name" to JsonPrimitive(""),
            "instruction" to JsonPrimitive(""),
            "ingredients" to JsonArray(emptyList()),
            "time" to JsonPrimitive(0),
            "file" to JsonNull,
            "step_recipe" to JsonNull,
            "step_recipe_data" to JsonNull,
        ),
    )

    private fun emptyIngredient(): JsonObject = JsonObject(
        firstIngredient() + mapOf("food" to JsonNull, "unit" to JsonNull, "amount" to JsonPrimitive(0.0), "note" to JsonPrimitive("")),
    )

    private fun overviewOf(recipe: JsonObject): JsonObject = JsonObject(
        recipe.filterKeys { it in OVERVIEW_FIELDS } + ("keywords" to (recipe["keywords"] ?: JsonArray(emptyList()))),
    )

    private fun page(results: List<JsonElement>): MockResponse = json(
        200,
        JsonObject(
            mapOf(
                "count" to JsonPrimitive(results.size),
                "next" to JsonNull,
                "previous" to JsonNull,
                "timestamp" to JsonPrimitive("2000-01-15T00:00:00.000000+00:00"),
                "results" to JsonArray(results),
            ),
        ),
    )

    private fun notFound(): MockResponse = json(404, JsonObject(mapOf("detail" to JsonPrimitive("No Recipe matches the given query."))))

    private fun parse(text: String): JsonObject = if (text.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(text).jsonObject

    private fun element(name: String): JsonElement = Json.parseToJsonElement(TandoorFixtures.exchange(name, version).body!!)

    private fun json(code: Int, body: JsonElement): MockResponse = MockResponse.Builder()
        .code(code)
        .addHeader("Content-Type", "application/json")
        .body(body.toString())
        .build()

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

    private companion object {
        const val RECIPES = "/api/recipe/"
        const val TEMPLATE_RECIPE = 2L
        const val NOW = "2000-01-15T00:00:00.000000+02:00"
        val RECIPE = Regex("^/api/recipe/(\\d+)/$")
        val IMAGE = Regex("^/api/recipe/(\\d+)/image/$")
        val WRITES = setOf("POST", "PATCH", "PUT", "DELETE")
        val SCALAR_FIELDS =
            listOf("name", "description", "servings", "servings_text", "working_time", "waiting_time", "source_url", "private", "internal")
        val STEP_FIELDS = listOf("name", "instruction", "time", "order", "show_as_header", "show_ingredients_table")
        val INGREDIENT_FIELDS = listOf("amount", "note", "order", "is_header", "no_amount")
        val OVERVIEW_FIELDS = setOf(
            "id", "name", "description", "image", "working_time", "waiting_time", "created_by", "created_at", "updated_at",
            "internal", "private", "servings", "servings_text", "rating", "last_cooked", "new",
        )
    }
}
