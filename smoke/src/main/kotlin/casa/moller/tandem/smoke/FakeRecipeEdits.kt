package casa.moller.tandem.smoke

import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest

/**
 * Fas 4 review (fix-bygg): the recipe editor's writes for [Phase3SmokeTest], with [enabled] set
 * before the login. `POST /api/recipe/`, `PATCH` and `DELETE /api/recipe/{id}/` and
 * `PUT /api/recipe/{id}/image/` as Tandoor answers them (FACTS "Fas 3", "Fas 4: Fas 3:s rester"): a
 * PATCH keeps what it does not name, steps and ingredients keep their ids, a new one gets an id. A
 * recipe that changed is answered from here from then on, also in the sync's lists, so the app
 * reads back what it wrote. New foods get a recorded food's shape with the sent name.
 */
class FakeRecipeEdits(private val template: (String) -> JsonObject, private val recorded: (Long) -> JsonObject?) {
    /** One write as it reached the server: the body of a multipart request as its text. */
    data class Write(val method: String, val path: String, val body: String) {
        val json: JsonObject get() = Json.parseToJsonElement(body).jsonObject
    }

    /** True: this fake answers (see the class comment). Set before the login. */
    @Volatile
    var enabled: Boolean = false

    /** Every write, in order. */
    val writes: MutableList<Write> = CopyOnWriteArrayList()

    /** Fas 4 (#22): what `GET /api/property-type/` lists: `{id, name, unit, order}`. */
    val propertyTypes: MutableList<JsonObject> = CopyOnWriteArrayList()

    private val changed = LinkedHashMap<Long, JsonObject>()
    private val deleted = HashSet<Long>()
    private var nextRecipeId = FIRST_RECIPE
    private var nextPartId = FIRST_PART

    private val recipeTemplate by lazy { template(RECIPE_SHAPE) }
    private val stepTemplate by lazy { recipeTemplate.getValue("steps").jsonArray.first().jsonObject }
    private val ingredientTemplate by lazy { stepTemplate.getValue("ingredients").jsonArray.first().jsonObject }

    /** The writes of [method] to a path that starts with [path]. */
    fun writes(method: String, path: String): List<Write> = writes.filter { it.method == method && it.path.startsWith(path) }

    @Synchronized
    fun answer(request: RecordedRequest): MockResponse? {
        if (!enabled) return null
        val method = request.method
        val path = request.url.encodedPath
        val id = RECIPE.find(path)?.groupValues?.get(1)?.toLong()
        val response = when {
            method == "POST" && path == RECIPES -> create(parse(request))
            method == "GET" && path == PROPERTY_TYPES -> json(OK, typePage())
            method == "GET" && PROPERTY_TYPE.matches(path) -> propertyType(PROPERTY_TYPE.find(path)!!.groupValues[1].toLong())
            method == "PUT" && IMAGE.matches(path) -> removeImage(IMAGE.find(path)!!.groupValues[1].toLong())
            id == null -> null
            method == "GET" -> if (id in deleted) notFound() else changed[id]?.let { json(OK, it) }
            method == "PATCH" -> patch(id, parse(request))
            method == "DELETE" -> delete(id)
            else -> null
        }
        if (response != null && method != "GET") writes += Write(method, path, request.body?.utf8().orEmpty())
        return response
    }

    private fun typePage(): JsonObject = JsonObject(
        mapOf(
            "count" to JsonPrimitive(propertyTypes.size),
            "next" to JsonNull,
            "previous" to JsonNull,
            "results" to JsonArray(propertyTypes),
        ),
    )

    private fun propertyType(id: Long): MockResponse =
        propertyTypes.firstOrNull { it.long("id") == id }?.let { json(OK, it) } ?: notFound()

    private fun create(body: JsonObject): MockResponse {
        val id = nextRecipeId++
        val fields = mapOf(
            "id" to JsonPrimitive(id),
            "image" to JsonNull,
            "keywords" to JsonArray(emptyList()),
            "steps" to stepsOf(body, JsonArray(emptyList())),
            "created_at" to now(),
            "updated_at" to now(),
        )
        val recipe = JsonObject(recipeTemplate + body.filterKeys { it in RECIPE_FIELDS } + fields)
        changed[id] = recipe
        return json(CREATED, recipe)
    }

    private fun patch(id: Long, body: JsonObject): MockResponse {
        val recipe = current(id) ?: return notFound()
        val fields = mapOf("steps" to stepsOf(body, recipe.getValue("steps").jsonArray), "updated_at" to now())
        val updated = JsonObject(recipe + body.filterKeys { it in RECIPE_FIELDS } + fields)
        changed[id] = updated
        return json(OK, updated)
    }

    /** The image endpoint with an empty `image_url` (FACTS "Fas 4: Fas 3:s rester" c): 200 `{"image": null}`. */
    private fun removeImage(id: Long): MockResponse {
        val recipe = current(id) ?: return notFound()
        changed[id] = JsonObject(recipe + ("image" to JsonNull))
        return json(OK, JsonObject(mapOf("image" to JsonNull, "image_url" to JsonNull)))
    }

    private fun delete(id: Long): MockResponse {
        if (current(id) == null) return notFound()
        changed -= id
        deleted += id
        return MockResponse.Builder().code(NO_CONTENT).build()
    }

    private fun current(id: Long): JsonObject? = if (id in deleted) null else changed[id] ?: recorded(id)

    private fun stepsOf(body: JsonObject, current: JsonArray): JsonArray {
        val sent = body["steps"] as? JsonArray ?: return current
        return JsonArray(
            sent.map { element ->
                val step = element.jsonObject
                val before = current.byId(step.long("id"))
                val id = step.long("id") ?: nextPartId++
                val instruction = step["instruction"] ?: JsonPrimitive("")
                val fields = mapOf(
                    "id" to JsonPrimitive(id),
                    "instruction" to instruction,
                    "instruction_markdown" to instruction,
                    "ingredients" to ingredientsOf(step, (before ?: stepTemplate).getValue("ingredients").jsonArray, before != null),
                )
                JsonObject((before ?: stepTemplate) + step.filterKeys { it in STEP_FIELDS } + fields)
            },
        )
    }

    private fun ingredientsOf(step: JsonObject, current: JsonArray, known: Boolean): JsonArray {
        val sent = step["ingredients"] as? JsonArray ?: return if (known) current else JsonArray(emptyList())
        return JsonArray(
            sent.map { element ->
                val line = element.jsonObject
                val before = current.byId(line.long("id")).takeIf { known }
                val names = when (before) {
                    null -> mapOf("food" to newPart(line["food"], "food"), "unit" to newPart(line["unit"], "unit"))
                    else -> emptyMap()
                }
                val id = line.long("id") ?: nextPartId++
                val fields = line.filterKeys { it in INGREDIENT_FIELDS }
                JsonObject((before ?: ingredientTemplate) + fields + names + ("id" to JsonPrimitive(id)))
            },
        )
    }

    /** A new food or unit: the recorded [key]'s shape with the sent name. */
    private fun newPart(sent: JsonElement?, key: String): JsonElement {
        val name = (sent as? JsonObject)?.string("name") ?: return JsonNull
        val shape = ingredientTemplate.getValue(key).jsonObject
        return JsonObject(shape + mapOf("id" to JsonPrimitive(nextPartId++), "name" to JsonPrimitive(name)))
    }

    /** A recorded recipe list as it is now: the changed ones as they are, the deleted gone, the new ones added. */
    @Synchronized
    fun overviews(list: JsonElement): JsonElement {
        if (!enabled) return list
        val body = list.jsonObject
        val listed = body.getValue("results").jsonArray.map { it.jsonObject }
        val sample = listed.firstOrNull() ?: return list
        val results = (listed.map { overview -> (overview.long("id") ?: 0L) to overview } + changed.keys.map { it to sample })
            .distinctBy { (id, _) -> id }
            .filter { (id, _) -> id !in deleted }
            .map { (id, overview) ->
                changed[id]?.let { recipe -> JsonObject(overview + recipe.filterKeys { it in OVERVIEW_FIELDS }) } ?: overview
            }
        return JsonObject(body + mapOf("count" to JsonPrimitive(results.size), "next" to JsonNull, "results" to JsonArray(results)))
    }

    /** `recipe/flat/` as it is now. */
    @Synchronized
    fun flat(list: JsonElement): JsonElement {
        if (!enabled) return list
        val listed = list.jsonArray.map { it.jsonObject }
        val sample = listed.firstOrNull() ?: return list
        val known = listed.mapNotNull { it.long("id") }.toSet()
        val added = changed.filterKeys { it !in known }.map { (id, recipe) ->
            JsonObject(sample + mapOf("id" to JsonPrimitive(id), "name" to recipe.getValue("name"), "image" to JsonNull))
        }
        return JsonArray(listed.filter { it.long("id") !in deleted } + added)
    }

    private fun parse(request: RecordedRequest): JsonObject = Json.parseToJsonElement(request.body?.utf8().orEmpty()).jsonObject

    private fun now(): JsonPrimitive = JsonPrimitive(Instant.now().toString())

    private fun notFound(): MockResponse =
        json(NOT_FOUND, JsonObject(mapOf("detail" to JsonPrimitive("No Recipe matches the given query."))))

    private fun json(code: Int, body: JsonElement): MockResponse = MockResponse.Builder()
        .code(code)
        .addHeader("Content-Type", "application/json")
        .body(body.toString())
        .build()

    private fun JsonArray.byId(id: Long?): JsonObject? = id?.let { wanted -> map { it.jsonObject }.firstOrNull { it.long("id") == wanted } }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

    private companion object {
        const val RECIPE_SHAPE = "fas1/recipe-svamppasta"
        const val FIRST_RECIPE = 80L
        const val FIRST_PART = 9000L
        const val RECIPES = "/api/recipe/"
        const val PROPERTY_TYPES = "/api/property-type/"
        val PROPERTY_TYPE = Regex("^/api/property-type/(\\d+)/$")
        val RECIPE = Regex("^/api/recipe/(\\d+)/$")
        val IMAGE = Regex("^/api/recipe/(\\d+)/image/$")
        val RECIPE_FIELDS = setOf(
            "name", "description", "servings", "servings_text", "working_time", "waiting_time", "source_url", "private", "internal",
            "properties",
        )
        val STEP_FIELDS = setOf("name", "time", "order", "show_as_header", "show_ingredients_table", "step_recipe")
        val INGREDIENT_FIELDS = setOf("amount", "note", "order", "is_header", "no_amount")
        val OVERVIEW_FIELDS = setOf(
            "id", "name", "description", "image", "servings", "working_time", "waiting_time", "updated_at", "created_at",
        )
        const val OK = 200
        const val CREATED = 201
        const val NO_CONTENT = 204
        const val NOT_FOUND = 404
    }
}
