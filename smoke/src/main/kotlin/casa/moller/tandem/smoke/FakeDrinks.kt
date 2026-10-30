package casa.moller.tandem.smoke

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
 * Fas S (integration): the keywords and drinks of a Tandoor 2.6.13 for the release smoke test, a
 * port of core:sync's test fake (`FakeDrinkTandoor`). With [enabled] (set before the login) the
 * space has one drink: [DRINK_NAME], a recipe with the keyword `Dryck > Vin` (plan §8.8, B20).
 *
 * Facts of FACTS "Fas S" c and f: `POST /api/keyword/` is a get-or-create by name (case ignored)
 * that ignores `parent`; `move` sets the parent; a recipe PATCH with `keywords` REPLACES the whole
 * list (an entry with `id` keeps that keyword, one with only `name` is reused or created at the
 * root). "The partner" changes a recipe's keywords on the web with [partnerAdds].
 */
class FakeDrinks(private val template: (String) -> JsonObject) {
    data class Keyword(val id: Long, val name: String, val parent: Long? = null)

    /** True: the drink and its keywords exist (see the class comment). */
    @Volatile
    var enabled: Boolean = false

    private val keywords = LinkedHashMap<Long, Keyword>()

    /** Recipe id -> keyword ids, for recipes whose keywords changed (others answer as recorded). */
    private val recipeKeywords = HashMap<Long, List<Long>>()
    private var nextKeywordId = FIRST_NEW_KEYWORD

    /** Every request this fake answered, as `METHOD path?query` with its body. */
    val calls: MutableList<Pair<String, String>> = CopyOnWriteArrayList()

    private val keywordTemplate by lazy { template("fas-s/keyword-get-child") }
    private val drinkTemplate by lazy { template("fas-s/recipe-create-drink-empty-steps") }

    private fun ensureKeywords() {
        if (keywords.isEmpty()) {
            template("fas1/keyword-list").getValue("results").jsonArray.forEach { listed ->
                val keyword = listed.jsonObject
                val id = keyword.long("id") ?: return@forEach
                keywords[id] = Keyword(id, keyword.string("name").orEmpty(), keyword.long("parent"))
            }
        }
        if (enabled && DRINK_KEYWORD !in keywords) {
            keywords[DRINK_KEYWORD] = Keyword(DRINK_KEYWORD, "Dryck")
            keywords[WINE_KEYWORD] = Keyword(WINE_KEYWORD, "Vin", DRINK_KEYWORD)
            recipeKeywords[DRINK_ID] = listOf(DRINK_KEYWORD, WINE_KEYWORD)
        }
    }

    // ------------------------------------------------------------------------------ state

    /** The keyword names of [recipe] on the server ([recorded] = its recorded keyword ids). */
    @Synchronized
    fun keywordNamesOf(recipe: Long, recorded: List<Long>): List<String> {
        ensureKeywords()
        return (recipeKeywords[recipe] ?: recorded).mapNotNull { keywords[it]?.name }
    }

    /** The parent's name of the keyword called [name], null when it has none (or does not exist). */
    @Synchronized
    fun parentOf(name: String): String? {
        ensureKeywords()
        val keyword = keywords.values.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return null
        return keyword.parent?.let { keywords[it]?.name }
    }

    /** Another member edits [recipe]'s keywords on the web: adds the root keyword [name]. */
    @Synchronized
    fun partnerAdds(recipe: Long, recorded: List<Long>, name: String) {
        ensureKeywords()
        recipeKeywords[recipe] = (recipeKeywords[recipe] ?: recorded) + getOrCreate(name).id
    }

    fun requests(method: String, pathPrefix: String): List<Pair<String, String>> =
        calls.filter { (call, _) -> call.startsWith("$method $pathPrefix") }

    // ------------------------------------------------------------------------------ answers

    /**
     * Answers keyword requests and the recipes this fake changed; null for everything else (the
     * recordings answer). [recorded] is a recipe's recorded detail.
     */
    @Synchronized
    fun answer(request: RecordedRequest, recorded: (Long) -> JsonObject?): MockResponse? {
        ensureKeywords()
        val method = request.method
        val path = request.url.encodedPath
        val text = request.body?.utf8().orEmpty()
        val response = when {
            path == KEYWORDS -> keywordsAnswer(method, text)
            KEYWORD.matches(path) || MOVE.matches(path) -> keywordAnswer(method, path)
            RECIPE.matches(path) -> recipeAnswer(method, RECIPE.find(path)!!.groupValues[1].toLong(), text, recorded)
            else -> null
        } ?: return null
        val query = request.url.encodedQuery?.let { "?$it" }.orEmpty()
        calls += "$method $path$query" to text
        return response
    }

    private fun keywordsAnswer(method: String, text: String): MockResponse? = when (method) {
        "GET" -> page(keywords.values.map { keywordJson(it) })
        "POST" -> json(CREATED, keywordJson(getOrCreate(parse(text).string("name").orEmpty())))
        else -> null
    }

    private fun keywordAnswer(method: String, path: String): MockResponse? {
        MOVE.find(path)?.groupValues?.let { return if (method == "PUT") move(it[1].toLong(), it[2].toLong()) else null }
        if (method != "GET") return null
        return keywords[KEYWORD.find(path)!!.groupValues[1].toLong()]?.let { json(OK, keywordJson(it)) } ?: notFound("Keyword")
    }

    /** A recipe this fake changed (or the drink); null for the others, which the recordings answer. */
    private fun recipeAnswer(method: String, id: Long, text: String, recorded: (Long) -> JsonObject?): MockResponse? = when (method) {
        "GET" -> if (id == DRINK_ID || id in recipeKeywords) recipeJson(id, recorded) ?: notFound("Recipe") else null
        "PATCH" -> patchRecipe(id, parse(text), recorded)
        else -> null
    }

    /**
     * A recorded recipe list with the drink added (when [enabled]) and the changed keywords of
     * every recipe; with [keywordsOr] (Tandoor's `keywords_or`, children included) only the
     * recipes with one of those keywords.
     */
    @Synchronized
    fun overviews(recorded: JsonElement, keywordsOr: Set<Long>?): JsonElement {
        ensureKeywords()
        val body = recorded.jsonObject
        val listed = body.getValue("results").jsonArray.map { it.jsonObject }
        val all = (if (enabled) listed + drinkOverview(listed.first()) else listed).map { overview ->
            val id = overview.long("id") ?: return@map overview
            val changed = recipeKeywords[id] ?: return@map overview
            JsonObject(overview + ("keywords" to JsonArray(changed.mapNotNull { keywords[it] }.map { overviewKeyword(it) })))
        }
        val results = if (keywordsOr == null) {
            all
        } else {
            val wanted = keywords.values.filter { it.id in keywordsOr || it.parent?.let { parent -> parent in keywordsOr } == true }
                .map { it.id }
                .toSet()
            all.filter { overview -> overview.keywordIds().any { it in wanted } }
        }
        return JsonObject(body + mapOf("count" to JsonPrimitive(results.size), "next" to JsonNull, "results" to JsonArray(results)))
    }

    /** `recipe/flat/` with the drink added (when [enabled]). */
    @Synchronized
    fun flat(recorded: JsonElement): JsonElement {
        if (!enabled) return recorded
        val listed = recorded.jsonArray
        val fields = mapOf("id" to JsonPrimitive(DRINK_ID), "name" to JsonPrimitive(DRINK_NAME), "image" to JsonNull)
        val drink = JsonObject(listed.first().jsonObject + fields)
        return JsonArray(listed + drink)
    }

    private fun drinkOverview(sample: JsonObject): JsonObject = JsonObject(
        sample + mapOf(
            "id" to JsonPrimitive(DRINK_ID),
            "name" to JsonPrimitive(DRINK_NAME),
            "description" to JsonPrimitive(""),
            "image" to JsonNull,
            "working_time" to JsonPrimitive(0),
            "waiting_time" to JsonPrimitive(0),
            "servings" to JsonPrimitive(1),
            "rating" to JsonNull,
            "last_cooked" to JsonNull,
        ),
    )

    private fun JsonObject.keywordIds(): List<Long> =
        (this["keywords"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.long("id") }

    private fun getOrCreate(name: String): Keyword = keywords.values.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
        ?: Keyword(nextKeywordId++, name.trim()).also { keywords[it.id] = it }

    private fun move(id: Long, parent: Long): MockResponse {
        val keyword = keywords[id] ?: return notFound("Keyword")
        val target = keywords[parent] ?: return notFound("Keyword")
        keywords[id] = keyword.copy(parent = parent)
        val body = template("fas-s/keyword-move")
        return json(OK, JsonObject(body + ("msg" to JsonPrimitive("${keyword.name} was moved successfully to parent ${target.name}"))))
    }

    private fun recordedIds(id: Long, recorded: (Long) -> JsonObject?): List<Long> =
        (recorded(id)?.get("keywords") as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.long("id") }

    private fun recipeJson(id: Long, recorded: (Long) -> JsonObject?): MockResponse? {
        val base = if (id == DRINK_ID) {
            JsonObject(drinkTemplate + mapOf("id" to JsonPrimitive(DRINK_ID), "name" to JsonPrimitive(DRINK_NAME), "image" to JsonNull))
        } else {
            recorded(id) ?: return null
        }
        val ids = recipeKeywords[id] ?: recordedIds(id, recorded)
        return json(OK, JsonObject(base + ("keywords" to JsonArray(ids.mapNotNull { keywords[it] }.map { keywordJson(it) }))))
    }

    private fun patchRecipe(id: Long, body: JsonObject, recorded: (Long) -> JsonObject?): MockResponse {
        if (id != DRINK_ID && recorded(id) == null) return notFound("Recipe")
        val list = body["keywords"] as? JsonArray ?: return json(BAD_REQUEST, JsonObject(emptyMap()))
        recipeKeywords[id] = list.mapNotNull { it as? JsonObject }.map { entry ->
            entry.long("id")?.takeIf { it in keywords } ?: getOrCreate(entry.string("name").orEmpty()).id
        }.distinct()
        return recipeJson(id, recorded)!!
    }

    // ------------------------------------------------------------------------------ shapes

    private fun keywordJson(keyword: Keyword): JsonObject {
        val parent = keyword.parent?.let { keywords[it] }
        return JsonObject(
            keywordTemplate + mapOf(
                "id" to JsonPrimitive(keyword.id),
                "name" to JsonPrimitive(keyword.name),
                "label" to JsonPrimitive(keyword.name),
                "parent" to (keyword.parent?.let { JsonPrimitive(it) } ?: JsonNull),
                "numchild" to JsonPrimitive(keywords.values.count { it.parent == keyword.id }),
                "full_name" to JsonPrimitive(if (parent != null) "${parent.name} > ${keyword.name}" else keyword.name),
            ),
        )
    }

    private fun overviewKeyword(keyword: Keyword): JsonObject =
        JsonObject(mapOf("id" to JsonPrimitive(keyword.id), "label" to JsonPrimitive(keyword.name)))

    private fun page(results: List<JsonElement>): MockResponse = json(
        OK,
        JsonObject(
            mapOf(
                "count" to JsonPrimitive(results.size),
                "next" to JsonNull,
                "previous" to JsonNull,
                "results" to JsonArray(results),
            ),
        ),
    )

    private fun notFound(model: String): MockResponse =
        json(NOT_FOUND, JsonObject(mapOf("detail" to JsonPrimitive("No $model matches the given query."))))

    private fun parse(text: String): JsonObject = if (text.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(text).jsonObject

    private fun json(code: Int, body: JsonElement): MockResponse = MockResponse.Builder()
        .code(code)
        .addHeader("Content-Type", "application/json")
        .body(body.toString())
        .build()

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

    companion object {
        /** The space's one drink (with [enabled]). */
        const val DRINK_ID = 40L
        const val DRINK_NAME = "Rioja Exempel"
        const val DRINK_KEYWORD = 100L
        const val WINE_KEYWORD = 101L

        private const val FIRST_NEW_KEYWORD = 700L
        private const val OK = 200
        private const val CREATED = 201
        private const val BAD_REQUEST = 400
        private const val NOT_FOUND = 404
        private const val KEYWORDS = "/api/keyword/"
        private val KEYWORD = Regex("^/api/keyword/(\\d+)/$")
        private val MOVE = Regex("^/api/keyword/(\\d+)/move/(\\d+)/$")
        private val RECIPE = Regex("^/api/recipe/(\\d+)/$")
    }
}
