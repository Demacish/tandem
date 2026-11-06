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
    private var nextFileId = 600L
    private var nextPropertyId = 500L

    /** Every request this fake answered, as `METHOD path?query` with its body. */
    val calls: MutableList<Pair<String, String>> = CopyOnWriteArrayList()

    /** Whether writes are refused with 403 (a guest). */
    var guest: Boolean = false

    /** Fas 4 (#22): what `GET /api/property-type/` lists. */
    val propertyTypes: MutableList<JsonObject> = CopyOnWriteArrayList()

    /** The next uploads refused with this many 400s (a space's file limit). */
    var refusedUploads: Int = 0

    /** Uploads after this many are refused with a 400, null for none. */
    var refuseAfterUploads: Int? = null

    /** `created_at` of the recipes POSTed from now on. */
    var createdAt: String = NOW

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

    /** Another member renames the keyword for the whole space (the recipes follow). */
    @Synchronized
    fun renameKeyword(id: Long, name: String) {
        keywords[id] = name
    }

    /** The keyword's name on the server. */
    @Synchronized
    fun keywordName(id: Long): String? = keywords[id]

    /** Another member changes the servings on the web: `updated_at` moves on. */
    @Synchronized
    fun partnerChangesServings(id: Long, servings: Int) {
        val recipe = recipes[id] ?: return
        recipes[id] = bumped(JsonObject(recipe + ("servings" to JsonPrimitive(servings))))
    }

    /** The recipe's own per-serving nutrition (`properties`, as Tandoor's property editor stores it). */
    @Synchronized
    fun hasNutrition(id: Long) {
        val recipe = recipes[id] ?: return
        val type = JsonObject(mapOf("name" to JsonPrimitive("Energi"), "unit" to JsonPrimitive("kcal")))
        val property = JsonObject(mapOf("property_amount" to JsonPrimitive(350), "property_type" to type))
        recipes[id] = JsonObject(recipe + ("properties" to JsonArray(listOf(property))))
    }

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

    /**
     * Another member changes only the first step on the web (`PATCH /api/step/{id}/`): the
     * recipe's `updated_at` stays (FACTS "Fas 4: Fas 3:s rester" d).
     */
    @Synchronized
    fun partnerEditsStep(id: Long, instruction: String) {
        val recipe = recipes[id] ?: return
        val steps = (recipe["steps"] as? JsonArray).orEmpty().mapIndexed { index, step ->
            if (index == 0) JsonObject((step as JsonObject) + ("instruction" to JsonPrimitive(instruction))) else step
        }
        recipes[id] = JsonObject(recipe + ("steps" to JsonArray(steps)))
    }

    /** The recipe has a picture on the server. */
    @Synchronized
    fun hasImage(id: Long) {
        val recipe = recipes[id] ?: return
        recipes[id] = JsonObject(recipe + ("image" to JsonPrimitive("/media/recipes/recipe_$id.jpg")))
    }

    @Synchronized
    fun answer(request: RecordedRequest): MockResponse? {
        val method = request.method
        val path = request.url.encodedPath
        val text = request.body?.utf8().orEmpty()
        val write = method in WRITES
        val response = when {
            write && guest -> json(403, JsonObject(mapOf("detail" to JsonPrimitive("You do not have permission to perform this action."))))
            write -> writeAnswer(method, path, text)
            else -> readAnswer(request, method, path)
        } ?: return null
        val query = request.url.encodedQuery?.let { "?$it" }.orEmpty()
        calls += "$method $path$query" to text
        return response
    }

    private fun writeAnswer(method: String, path: String, text: String): MockResponse? {
        val id = RECIPE.find(path)?.groupValues?.get(1)?.toLong()
        return when {
            path == RECIPES && method == "POST" -> create(parse(text))
            path == USER_FILES && method == "POST" -> userFile(text)
            IMAGE.matches(path) && method == "PUT" -> image(IMAGE.find(path)!!.groupValues[1].toLong(), text)
            id != null && method == "PATCH" -> patch(id, parse(text))
            id != null && method == "DELETE" -> if (recipes.remove(id) == null) notFound() else MockResponse.Builder().code(204).build()
            else -> null
        }
    }

    private fun readAnswer(request: RecordedRequest, method: String, path: String): MockResponse? {
        if (method != "GET") return null
        val keyword = KEYWORD.find(path)?.groupValues?.get(1)?.toLong()
        val recipe = RECIPE.find(path)?.groupValues?.get(1)?.toLong()
        return when {
            path == PROPERTY_TYPES -> page(propertyTypes)

            PROPERTY_TYPE.matches(path) -> {
                val id = PROPERTY_TYPE.find(path)!!.groupValues[1].toLong()
                propertyTypes.firstOrNull { it.long("id") == id }?.let { json(200, it) } ?: notFound()
            }

            keyword != null -> keywords[keyword]?.let { json(200, keywordJson(keyword to it)) } ?: notFound()

            recipe != null -> recipes[recipe]?.let { json(200, it) } ?: notFound()

            path == RECIPES && request.url.queryParameter("query") != null -> {
                val query = request.url.queryParameter("query").orEmpty()
                page(recipes.values.filter { it.string("name").orEmpty().contains(query, ignoreCase = true) }.map { overviewOf(it) })
            }

            else -> null
        }
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
                "created_at" to JsonPrimitive(createdAt),
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

    /** Fas 4 (#22): the multipart `name` is what comes back; `preview` is empty unless it is a picture (FACTS "Fas 1 m"). */
    private fun userFile(body: String): MockResponse {
        val limit = refuseAfterUploads
        if (refusedUploads > 0 || (limit != null && calls.count { it.first.startsWith("POST $USER_FILES") } >= limit)) {
            refusedUploads = (refusedUploads - 1).coerceAtLeast(0)
            return json(400, JsonObject(mapOf("detail" to JsonPrimitive("You have reached your file upload limit."))))
        }
        val name = FILE_NAME.find(body)?.groupValues?.get(1).orEmpty()
        val file = mapOf(
            "id" to JsonPrimitive(nextFileId++),
            "name" to JsonPrimitive(name),
            "file_download" to JsonPrimitive("https://tandoor.example.com/api/download-file/1/"),
            "preview" to JsonPrimitive(""),
            "file_size_kb" to JsonPrimitive(1),
        )
        return json(201, JsonObject(file))
    }

    /** The photo upload, or (Fas 3 import lane) a picture the server downloads from `image_url`: both end as the recipe's image. */
    private fun image(id: Long, body: String): MockResponse {
        val fromWeb = body.contains("name=\"image_url\"")
        val recipe = recipes[id] ?: return notFound()
        // An empty `image_url` removes the picture (FACTS "Fas 4: Fas 3:s rester" c).
        if (fromWeb && !body.contains("http")) {
            recipes[id] = JsonObject(recipe + ("image" to JsonNull))
            return json(200, JsonObject(mapOf("image" to JsonNull, "image_url" to JsonNull)))
        }
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
        // Fas 4 (#22): the recipe's own values; a value with an id stays that row, the others are made anew.
        body["properties"]?.let { list ->
            val properties = (list as JsonArray).mapNotNull { it as? JsonObject }.map { property ->
                val type = property.getValue("property_type") as JsonObject
                val known = propertyTypes.firstOrNull { it.long("id") == type.long("id") } ?: type
                JsonObject(property + ("id" to (property["id"] ?: JsonPrimitive(nextPropertyId++))) + ("property_type" to known))
            }
            fields["properties"] = JsonArray(properties)
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
        // Fas 4 (#22): the server's own read-only fields say what the writable ones point at.
        step["step_recipe"]?.let { link ->
            val linked = (link as? JsonPrimitive)?.longOrNull?.let { recipes[it] }
            fields["step_recipe"] = link
            fields["step_recipe_data"] = linked?.let { JsonObject(mapOf("id" to it.getValue("id"), "name" to it.getValue("name"))) } ?: JsonNull
        }
        step["file"]?.let { file ->
            fields["file"] = (file as? JsonObject)?.let { JsonObject(it + ("preview" to JsonPrimitive(""))) } ?: JsonNull
        }
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
        const val USER_FILES = "/api/user-file/"
        const val PROPERTY_TYPES = "/api/property-type/"
        val PROPERTY_TYPE = Regex("^/api/property-type/(\\d+)/$")
        val FILE_NAME = Regex("name=\"name\"[^\\n]*\\n(?:[^\\n]*: [^\\n]*\\n)*\\r?\\n([^\\r\\n]*)")
        const val TEMPLATE_RECIPE = 2L
        const val NOW = "2000-01-15T00:00:00.000000+02:00"
        val RECIPE = Regex("^/api/recipe/(\\d+)/$")
        val IMAGE = Regex("^/api/recipe/(\\d+)/image/$")
        val KEYWORD = Regex("^/api/keyword/(\\d+)/$")
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
