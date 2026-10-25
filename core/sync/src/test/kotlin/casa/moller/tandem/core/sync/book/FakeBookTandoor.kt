package casa.moller.tandem.core.sync.book

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
 * A stateful Tandoor for the books tests, answering in the recorded shapes of [version]
 * (`fas2/recipe-book-*`, `custom-filter-create-ids`, `user-space-*`, the recipes of `fas1/`) with
 * the facts of FACTS "Fas 2 g": `shared` is required, the same recipe twice in a book is a 400
 * "unique set", a deleted book or entry answers 404 with Tandoor's body, a recipe PATCH with
 * `keywords` replaces the whole list (keywords reused by name), a smart book has no entries.
 *
 * It does NOT refuse a write to someone else's book: the app must never send one (plan 3.7), and
 * the tests check [calls] for it.
 */
internal class FakeBookTandoor(private val version: String) {
    data class Book(
        val id: Long,
        val name: String,
        val owner: Long,
        val shared: List<Long> = emptyList(),
        val filter: JsonObject? = null,
        val description: String = "",
    )

    data class Entry(val id: Long, val book: Long, val recipe: Long)

    val books = LinkedHashMap<Long, Book>()
    val entries = LinkedHashMap<Long, Entry>()

    /** Recipe id -> keyword names, for recipes whose keywords changed (others answer as recorded). */
    private val keywords = HashMap<Long, List<String>>()
    private val keywordIds = HashMap<String, Long>()
    private var nextId = FIRST_ID

    /** The user the requests come from (owner of the books it creates). */
    @Volatile
    var caller: Long = OWN_USER

    /** The group of the caller in `user-space/all_personal` (`admin`, `user`, `guest`). */
    @Volatile
    var group: String = "user"

    /** Every request this fake answered, as `METHOD path?query` with its body. */
    val calls: MutableList<Pair<String, String>> = CopyOnWriteArrayList()

    fun install(server: FakeTandoorServer) {
        server.handler = { request -> answer(request) }
    }

    @Synchronized
    fun addBook(name: String, owner: Long, shared: List<Long> = emptyList(), filter: JsonObject? = null): Long {
        val id = nextId++
        books[id] = Book(id, name, owner, shared, filter)
        return id
    }

    @Synchronized
    fun addEntry(book: Long, recipe: Long): Long {
        val id = nextId++
        entries[id] = Entry(id, book, recipe)
        return id
    }

    @Synchronized
    fun keywordsOf(recipe: Long): List<String> = keywords[recipe] ?: recordedKeywords(recipe).map { it.string("name").orEmpty() }

    fun callsTo(method: String, pathPrefix: String): List<Pair<String, String>> =
        calls.filter { (call, _) -> call.startsWith("$method $pathPrefix") }

    // ------------------------------------------------------------------------------ answers

    @Synchronized
    private fun answer(request: RecordedRequest): MockResponse? {
        val method = request.method
        val path = request.url.encodedPath
        val text = request.body?.utf8().orEmpty()
        val response = when {
            path == BOOKS && method == "GET" -> page(books.values.map { bookJson(it) })
            path == BOOKS && method == "POST" -> createBook(parse(text))
            BOOK.matches(path) && method == "DELETE" -> deleteBook(BOOK.find(path)!!.groupValues[1].toLong())
            path == ENTRIES && method == "GET" -> listEntries(request.url.queryParameter("book"), request.url.queryParameter("recipe"))
            path == ENTRIES && method == "POST" -> createEntry(parse(text))
            ENTRY.matches(path) && method == "DELETE" -> deleteEntry(ENTRY.find(path)!!.groupValues[1].toLong())
            path == FILTERS && method == "POST" -> createFilter(parse(text))
            path == USERS && method == "GET" -> json(200, JsonArray(USER_IDS.map { userJson(it) }))
            path == USER_SPACE && method == "GET" -> json(200, element(if (group == "guest") USER_SPACE_GUEST else USER_SPACE_MEMBER))
            RECIPE.matches(path) && method == "GET" -> recipe(RECIPE.find(path)!!.groupValues[1].toLong())
            RECIPE.matches(path) && method == "PATCH" -> patchRecipe(RECIPE.find(path)!!.groupValues[1].toLong(), parse(text))
            else -> return null
        }
        val query = request.url.encodedQuery?.let { "?$it" }.orEmpty()
        calls += "$method $path$query" to text
        return response
    }

    private fun createBook(body: JsonObject): MockResponse {
        if (body["shared"] !is JsonArray) return json(400, JsonObject(mapOf("shared" to JsonArray(listOf(JsonPrimitive("required"))))))
        val id = nextId++
        val shared = (body["shared"] as JsonArray).mapNotNull { (it as? JsonObject)?.long("id") }
        val name = body.string("name").orEmpty()
        val book = Book(id, name, caller, shared, body["filter"] as? JsonObject, body.string("description").orEmpty())
        books[id] = book
        return json(201, bookJson(book))
    }

    private fun deleteBook(id: Long): MockResponse {
        books.remove(id) ?: return notFound("RecipeBook")
        entries.values.removeAll { it.book == id }
        return MockResponse.Builder().code(204).build()
    }

    private fun listEntries(book: String?, recipe: String?): MockResponse = page(
        entries.values
            .filter { book == null || it.book.toString() == book }
            .filter { recipe == null || it.recipe.toString() == recipe }
            .map { entryJson(it) },
    )

    private fun createEntry(body: JsonObject): MockResponse {
        val book = body.long("book")
        val recipe = body.long("recipe")
        if (book == null || books[book] == null) {
            return json(400, JsonObject(mapOf("book" to JsonArray(listOf(JsonPrimitive("Invalid pk."))))))
        }
        if (recipe == null) return json(400, JsonObject(mapOf("recipe" to JsonArray(listOf(JsonPrimitive("required"))))))
        if (entries.values.any { it.book == book && it.recipe == recipe }) {
            val message = JsonPrimitive("The fields recipe, book must make a unique set.")
            return json(400, JsonObject(mapOf("non_field_errors" to JsonArray(listOf(message)))))
        }
        val entry = Entry(nextId++, book, recipe)
        entries[entry.id] = entry
        return json(201, entryJson(entry))
    }

    private fun deleteEntry(id: Long): MockResponse {
        entries.remove(id) ?: return notFound("RecipeBookEntry")
        return MockResponse.Builder().code(204).build()
    }

    private fun createFilter(body: JsonObject): MockResponse {
        val template = body("fas2/custom-filter-create-ids")
        val filter = JsonObject(template + ("id" to JsonPrimitive(nextId++)) + ("name" to body["name"]!!) + ("search" to body["search"]!!))
        return json(201, filter)
    }

    private fun recipe(id: Long): MockResponse? {
        val name = FakeTandoorServer.RECIPES[id.toString()] ?: return null
        val recorded = Json.parseToJsonElement(TandoorFixtures.exchange(name).body!!).jsonObject
        val names = keywords[id] ?: return json(200, recorded)
        return json(200, JsonObject(recorded + ("keywords" to JsonArray(names.map { keywordJson(it) }))))
    }

    private fun patchRecipe(id: Long, body: JsonObject): MockResponse {
        val name = FakeTandoorServer.RECIPES[id.toString()] ?: return notFound("Recipe")
        val list = body["keywords"] as? JsonArray ?: return json(400, JsonObject(emptyMap()))
        keywords[id] = list.mapNotNull { (it as? JsonObject)?.string("name") }.distinct()
        val recorded = Json.parseToJsonElement(TandoorFixtures.exchange(name).body!!).jsonObject
        return json(200, JsonObject(recorded + ("keywords" to JsonArray(keywords.getValue(id).map { keywordJson(it) }))))
    }

    // ------------------------------------------------------------------------------ shapes

    private fun bookJson(book: Book): JsonObject = JsonObject(
        body("fas2/recipe-book-create-shared") +
            mapOf(
                "id" to JsonPrimitive(book.id),
                "name" to JsonPrimitive(book.name),
                "description" to JsonPrimitive(book.description),
                "shared" to JsonArray(book.shared.map { userJson(it) }),
                "created_by" to userJson(book.owner),
                "filter" to (book.filter ?: JsonNull),
            ),
    )

    private fun entryJson(entry: Entry): JsonObject {
        val template = body("fas2/recipe-book-entry-create-as-shared-member")
        val recipe = (template["recipe_content"] as JsonObject) + ("id" to JsonPrimitive(entry.recipe))
        return JsonObject(
            template + mapOf(
                "id" to JsonPrimitive(entry.id),
                "book" to JsonPrimitive(entry.book),
                "book_content" to (books[entry.book]?.let { bookJson(it) } ?: JsonNull),
                "recipe" to JsonPrimitive(entry.recipe),
                "recipe_content" to JsonObject(recipe),
            ),
        )
    }

    private fun userJson(id: Long): JsonObject = JsonObject(
        mapOf(
            "id" to JsonPrimitive(id),
            "username" to JsonPrimitive("user-$id"),
            "first_name" to JsonPrimitive(""),
            "last_name" to JsonPrimitive(""),
            "display_name" to JsonPrimitive(USER_NAMES[id] ?: "user-$id"),
            "is_staff" to JsonPrimitive(false),
            "is_superuser" to JsonPrimitive(false),
            "is_active" to JsonPrimitive(true),
        ),
    )

    private fun keywordJson(name: String): JsonObject {
        val id = keywordIds.getOrPut(name) { recordedKeywordId(name) ?: (KEYWORD_ID_START + keywordIds.size) }
        return JsonObject(
            mapOf(
                "id" to JsonPrimitive(id),
                "name" to JsonPrimitive(name),
                "label" to JsonPrimitive(name),
                "description" to JsonPrimitive(""),
                "parent" to JsonNull,
                "numchild" to JsonPrimitive(0),
                "full_name" to JsonPrimitive(name),
            ),
        )
    }

    private fun recordedKeywords(recipe: Long): List<JsonObject> {
        val name = FakeTandoorServer.RECIPES[recipe.toString()] ?: return emptyList()
        val recorded = Json.parseToJsonElement(TandoorFixtures.exchange(name).body!!).jsonObject
        return (recorded["keywords"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    }

    private fun recordedKeywordId(name: String): Long? = FakeTandoorServer.RECIPES.keys.asSequence()
        .flatMap { recordedKeywords(it.toLong()) }
        .firstOrNull { it.string("name") == name }
        ?.long("id")

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

    companion object {
        /** The phone's user (`SyncTestGraph(userId = …)` in the tests). */
        const val OWN_USER = 1L
        const val PARTNER = 3L
        const val GUEST = 4L
        val USER_IDS = listOf(OWN_USER, PARTNER, GUEST)
        val USER_NAMES = mapOf(OWN_USER to "Kim", PARTNER to "Alex", GUEST to "Gäst")
        private const val FIRST_ID = 500L
        private const val KEYWORD_ID_START = 800L
        private const val BOOKS = "/api/recipe-book/"
        private const val ENTRIES = "/api/recipe-book-entry/"
        private const val FILTERS = "/api/custom-filter/"
        private const val USERS = "/api/user/"
        private const val USER_SPACE = "/api/user-space/all_personal/"
        private const val USER_SPACE_GUEST = "fas2/user-space-guest"
        private const val USER_SPACE_MEMBER = "fas2/user-space-owner"
        private val BOOK = Regex("^/api/recipe-book/(\\d+)/$")
        private val ENTRY = Regex("^/api/recipe-book-entry/(\\d+)/$")
        private val RECIPE = Regex("^/api/recipe/(\\d+)/$")
    }
}
