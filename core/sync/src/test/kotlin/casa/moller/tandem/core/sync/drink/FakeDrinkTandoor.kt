package casa.moller.tandem.core.sync.drink

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
 * A stateful Tandoor for the drinks tests, answering in the recorded shapes of [version] (`fas-s/`
 * keyword, move, drink and image recordings; the recipes of `fas1/`) with the facts of FACTS
 * "Fas S" c and f: `POST /api/keyword/` is a get-or-create by name (case ignored) that ignores
 * `parent`; `move` sets the parent; a recipe PATCH with `keywords` REPLACES the whole list (an
 * entry with `id` keeps that keyword, one with only `name` is reused or created by name at the
 * root); `POST /api/recipe/` needs `steps`; the photo is a PUT.
 */
internal class FakeDrinkTandoor(private val version: String) {
    data class Keyword(val id: Long, val name: String, val parent: Long? = null)

    data class Created(val id: Long, val name: String, val description: String, val internal: Boolean, var hasImage: Boolean = false)

    val keywords = LinkedHashMap<Long, Keyword>()

    /** Recipe id -> keyword ids, for recipes whose keywords changed (others answer as recorded). */
    private val recipeKeywords = HashMap<Long, List<Long>>()
    val created = LinkedHashMap<Long, Created>()
    private var nextKeywordId = 700L
    private var nextRecipeId = 600L

    /** Every request this fake answered, as `METHOD path?query` with its body. */
    val calls: MutableList<Pair<String, String>> = CopyOnWriteArrayList()

    init {
        val listed = element("fas1/keyword-list").jsonObject["results"] as JsonArray
        listed.mapNotNull { it as? JsonObject }.forEach { keyword ->
            val id = keyword.long("id") ?: return@forEach
            keywords[id] = Keyword(id, keyword.string("name").orEmpty(), keyword.long("parent"))
        }
    }

    fun install(server: FakeTandoorServer) {
        server.handler = { request -> answer(request) }
    }

    fun callsTo(method: String, path: String): List<Pair<String, String>> = calls.filter { (call, _) -> call.startsWith("$method $path") }

    @Synchronized
    fun keywordNamesOf(recipe: Long): List<String> = keywordIdsOf(recipe).mapNotNull { keywords[it]?.name }

    @Synchronized
    fun keywordNamed(name: String): Keyword? = keywords.values.firstOrNull { it.name.equals(name, ignoreCase = true) }

    /** Fas S review (fix-data): a keyword the household made on the web (an English root, a renamed pairing). */
    @Synchronized
    fun seedKeyword(name: String, parent: Long? = null): Long {
        val keyword = Keyword(nextKeywordId++, name, parent)
        keywords[keyword.id] = keyword
        return keyword.id
    }

    /** Another member edits [recipe]'s keywords on the web (adds [name]). */
    @Synchronized
    fun partnerAdds(recipe: Long, name: String) {
        recipeKeywords[recipe] = keywordIdsOf(recipe) + getOrCreate(name).id
    }

    // ------------------------------------------------------------------------------ answers

    @Synchronized
    fun answer(request: RecordedRequest): MockResponse? {
        val method = request.method
        val path = request.url.encodedPath
        val text = request.body?.utf8().orEmpty()
        val response = when {
            path == KEYWORDS && method == "GET" -> page(keywords.values.map { keywordJson(it) })

            path == KEYWORDS && method == "POST" -> json(201, keywordJson(getOrCreate(parse(text).string("name").orEmpty())))

            KEYWORD.matches(path) && method == "GET" -> keywords[KEYWORD.find(path)!!.groupValues[1].toLong()]
                ?.let { json(200, keywordJson(it)) } ?: notFound("Keyword")

            MOVE.matches(path) && method == "PUT" -> MOVE.find(path)!!.groupValues.let { move(it[1].toLong(), it[2].toLong()) }

            path == RECIPES && method == "POST" -> createRecipe(parse(text))

            IMAGE.matches(path) && method == "PUT" -> image(IMAGE.find(path)!!.groupValues[1].toLong())

            RECIPE.matches(path) && method == "GET" -> recipe(RECIPE.find(path)!!.groupValues[1].toLong())

            RECIPE.matches(path) && method == "PATCH" -> patchRecipe(RECIPE.find(path)!!.groupValues[1].toLong(), parse(text))

            else -> return null
        } ?: return null
        val query = request.url.encodedQuery?.let { "?$it" }.orEmpty()
        calls += "$method $path$query" to text
        return response
    }

    private fun getOrCreate(name: String): Keyword = keywords.values.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
        ?: Keyword(nextKeywordId++, name.trim()).also { keywords[it.id] = it }

    private fun move(id: Long, parent: Long): MockResponse {
        val keyword = keywords[id] ?: return notFound("Keyword")
        val target = keywords[parent] ?: return notFound("Keyword")
        keywords[id] = keyword.copy(parent = parent)
        val template = body("fas-s/keyword-move")
        return json(200, JsonObject(template + ("msg" to JsonPrimitive("${keyword.name} was moved successfully to parent ${target.name}"))))
    }

    private fun keywordIdsOf(recipe: Long): List<Long> = recipeKeywords[recipe] ?: recordedKeywords(recipe).mapNotNull { it.long("id") }

    private fun recordedJson(recipe: Long): JsonObject? {
        val name = FakeTandoorServer.RECIPES[recipe.toString()] ?: return null
        return Json.parseToJsonElement(TandoorFixtures.exchange(name, version).body!!).jsonObject
    }

    private fun recordedKeywords(recipe: Long): List<JsonObject> =
        (recordedJson(recipe)?.get("keywords") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    private fun recipeJson(recipe: Long): JsonObject? {
        val drink = created[recipe]
        val base = if (drink != null) {
            JsonObject(
                body("fas-s/recipe-create-drink-empty-steps") + mapOf(
                    "id" to JsonPrimitive(drink.id),
                    "name" to JsonPrimitive(drink.name),
                    "description" to JsonPrimitive(drink.description),
                    "internal" to JsonPrimitive(drink.internal),
                    "image" to if (drink.hasImage) JsonPrimitive("/media/recipes/drink_${drink.id}.jpg") else JsonNull,
                ),
            )
        } else {
            recordedJson(recipe) ?: return null
        }
        return JsonObject(base + ("keywords" to JsonArray(keywordIdsOf(recipe).mapNotNull { keywords[it] }.map { keywordJson(it) })))
    }

    private fun recipe(id: Long): MockResponse? = recipeJson(id)?.let { json(200, it) }

    private fun patchRecipe(id: Long, body: JsonObject): MockResponse {
        recipeJson(id) ?: return notFound("Recipe")
        val list = body["keywords"] as? JsonArray ?: return json(400, JsonObject(emptyMap()))
        recipeKeywords[id] = resolve(list)
        return json(200, recipeJson(id)!!)
    }

    private fun resolve(list: JsonArray): List<Long> = list.mapNotNull { it as? JsonObject }.map { entry ->
        entry.long("id")?.takeIf { it in keywords } ?: getOrCreate(entry.string("name").orEmpty()).id
    }.distinct()

    private fun createRecipe(body: JsonObject): MockResponse {
        if (body["steps"] !is JsonArray) {
            return json(400, JsonObject(mapOf("steps" to JsonArray(listOf(JsonPrimitive("This field is required."))))))
        }
        val id = nextRecipeId++
        val internal = (body["internal"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: false
        created[id] = Created(id, body.string("name").orEmpty(), body.string("description").orEmpty(), internal)
        recipeKeywords[id] = resolve(body["keywords"] as? JsonArray ?: JsonArray(emptyList()))
        return json(201, recipeJson(id)!!)
    }

    private fun image(id: Long): MockResponse {
        val drink = created[id] ?: return notFound("Recipe")
        drink.hasImage = true
        val template = body("fas-s/recipe-image-put-drink")
        return json(200, JsonObject(template + ("image" to JsonPrimitive("/media/recipes/drink_$id.jpg"))))
    }

    // ------------------------------------------------------------------------------ shapes

    private fun keywordJson(keyword: Keyword): JsonObject {
        val parent = keyword.parent?.let { keywords[it] }
        return JsonObject(
            body("fas-s/keyword-get-child") + mapOf(
                "id" to JsonPrimitive(keyword.id),
                "name" to JsonPrimitive(keyword.name),
                "label" to JsonPrimitive(keyword.name),
                "parent" to (keyword.parent?.let { JsonPrimitive(it) } ?: JsonNull),
                "numchild" to JsonPrimitive(keywords.values.count { it.parent == keyword.id }),
                "full_name" to JsonPrimitive(if (parent != null) "${parent.name} > ${keyword.name}" else keyword.name),
            ),
        )
    }

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

    private fun notFound(model: String): MockResponse =
        json(404, JsonObject(mapOf("detail" to JsonPrimitive("No $model matches the given query."))))

    private fun parse(text: String): JsonObject = if (text.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(text).jsonObject

    private fun element(name: String): JsonElement = Json.parseToJsonElement(TandoorFixtures.exchange(name, version).body!!)

    private fun body(name: String): JsonObject = element(name).jsonObject

    private fun json(code: Int, body: JsonElement): MockResponse = MockResponse.Builder()
        .code(code)
        .addHeader("Content-Type", "application/json")
        .body(body.toString())
        .build()

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

    private companion object {
        const val KEYWORDS = "/api/keyword/"
        const val RECIPES = "/api/recipe/"
        val KEYWORD = Regex("^/api/keyword/(\\d+)/$")
        val MOVE = Regex("^/api/keyword/(\\d+)/move/(\\d+)/$")
        val RECIPE = Regex("^/api/recipe/(\\d+)/$")
        val IMAGE = Regex("^/api/recipe/(\\d+)/image/$")
    }
}
