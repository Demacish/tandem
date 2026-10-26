package casa.moller.tandem.core.sync.shopping

import casa.moller.tandem.core.sync.testing.FakeClock
import casa.moller.tandem.core.sync.testing.FakeTandoorServer
import casa.moller.tandem.core.testing.fixtures.TandoorFixtures
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest

/**
 * A stateful Tandoor shopping list for the sync tests, answering like the recorded server
 * (`fas2/` of [version]: the entry, list recipe and bulk shapes are the recordings with the state
 * filled in) and with the facts of FACTS "Fas 2 b-c": `updated_after` is `>=` on `updated_at`, the
 * list hides entries checked before midnight UTC − min(recent days, 14) days, `completed_at` is set
 * only when `checked` flips on an existing entry, bulk ignores unknown ids, 404 with Tandoor's
 * body for a deleted entry or list recipe, DELETE of a list recipe deletes its entries.
 *
 * "Another phone" changes the state directly ([partnerAdd], [partnerCheck], [partnerDelete]).
 */
internal class FakeShoppingTandoor(private val version: String, private val clock: FakeClock) {
    data class Entry(
        val id: Long,
        var food: String,
        var foodId: Long,
        var unit: String?,
        var amount: Double,
        var checked: Boolean = false,
        var completedAt: Long? = null,
        var delayUntil: Long? = null,
        var lists: Set<Long> = emptySet(),
        var listRecipe: Long? = null,
        var ingredient: Long? = null,
        val createdBy: Long = OWN_USER,
        val createdAt: Long,
        var updatedAt: Long,
    )

    data class ListRecipe(val id: Long, val recipe: Long?, var servings: Double, val mealplan: Long?)

    private val entries = LinkedHashMap<Long, Entry>()
    private val listRecipes = LinkedHashMap<Long, ListRecipe>()
    private val foodIds = HashMap<String, Long>()
    private var nextId = FIRST_ID
    private var serverMillis = 0L

    /** `shopping_recent_days` of the list's owner. */
    @Volatile
    var recentDays: Int = 7

    /** Every answer with status 404 (a "404 in the Caddy log"). */
    val notFound: MutableList<String> = CopyOnWriteArrayList()

    /** Every handled request as `METHOD path?query` and its body (empty when none). */
    val calls: MutableList<Pair<String, String>> = CopyOnWriteArrayList()

    private val entryTemplate = body("fas2/shopping-list-entry-create")
    private val listRecipeTemplate = body("fas2/shopping-list-recipe-create")
    private val foodTemplate = body("fas2/food-patch-onhand")
    private val relationTemplate = body("fas2/supermarket-category-relation-patch")

    fun install(server: FakeTandoorServer) {
        server.handler = { request -> answer(request) }
    }

    // ------------------------------------------------------------------------------ state

    /** Adds an entry on the server (by this account's user unless [by]); checked [checkedDaysAgo] days ago. */
    @Synchronized
    fun seed(food: String, amount: Double = 1.0, unit: String? = null, by: Long = OWN_USER, checkedDaysAgo: Int? = null): Long {
        val now = tick()
        val entry = Entry(
            id = nextId++,
            food = food,
            foodId = foodId(food),
            unit = unit,
            amount = amount,
            checked = checkedDaysAgo != null,
            completedAt = checkedDaysAgo?.let { now - it * DAY_MILLIS },
            createdBy = by,
            createdAt = now,
            updatedAt = now,
        )
        entries[entry.id] = entry
        return entry.id
    }

    @Synchronized
    fun partnerAdd(food: String): Long = seed(food, by = PARTNER)

    /**
     * Phase 2 (koppling lane): the server puts a planned meal's recipe on the list itself (the web
     * with `mealplan_autoadd_shopping`, or `addshopping`): one list recipe linked to [mealId] and one
     * entry per food. Returns the list recipe's id.
     */
    @Synchronized
    fun serverAddMeal(mealId: Long, recipe: Long, servings: Double, foods: List<String>): Long {
        val listRecipe = ListRecipe(id = nextId++, recipe = recipe, servings = servings, mealplan = mealId)
        listRecipes[listRecipe.id] = listRecipe
        for (food in foods) {
            val now = tick()
            val entry = Entry(
                id = nextId++,
                food = food,
                foodId = foodId(food),
                unit = null,
                amount = 1.0,
                listRecipe = listRecipe.id,
                createdBy = PARTNER,
                createdAt = now,
                updatedAt = now,
            )
            entries[entry.id] = entry
        }
        return listRecipe.id
    }

    /** Every list recipe on the server. */
    @Synchronized
    fun listRecipes(): List<ListRecipe> = listRecipes.values.map { it.copy() }

    @Synchronized
    fun partnerCheck(id: Long, checked: Boolean = true) {
        val entry = entries.getValue(id)
        val now = tick()
        entry.checked = checked
        entry.completedAt = if (checked) now else null
        entry.updatedAt = now
    }

    @Synchronized
    fun partnerDelete(ids: Collection<Long>) {
        ids.forEach { entries.remove(it) }
    }

    @Synchronized
    fun entry(id: Long): Entry? = entries[id]?.copy()

    @Synchronized
    fun all(): List<Entry> = entries.values.map { it.copy() }

    @Synchronized
    fun listRecipe(id: Long): ListRecipe? = listRecipes[id]?.copy()

    fun requests(method: String, pathPrefix: String): List<Pair<String, String>> =
        calls.filter { (call, _) -> call.startsWith("$method $pathPrefix") }

    // ------------------------------------------------------------------------------ answers

    /** Answers a shopping list request, null for any other path (so another fake may answer it). */
    @Synchronized
    fun answer(request: RecordedRequest): MockResponse? {
        val method = request.method
        val path = request.url.encodedPath
        val text = request.body?.utf8().orEmpty()
        val response = when {
            path == LIST && method == "GET" -> list(request.url.queryParameter("updated_after"))

            path == LIST && method == "POST" -> create(parse(text))

            path == BULK && method == "POST" -> bulk(parse(text))

            ENTRY.matches(path) -> entryAnswer(method, ENTRY.find(path)!!.groupValues[1].toLong(), parse(text))

            path == LIST_RECIPES && method == "POST" -> createListRecipe(parse(text))

            LIST_RECIPE.matches(path) -> listRecipeAnswer(method, LIST_RECIPE.find(path)!!.groupValues[1].toLong(), parse(text))

            FOOD.matches(path) && method == "PATCH" -> {
                val id = FOOD.find(path)!!.groupValues[1].toLong()
                json(200, JsonObject(foodTemplate + ("id" to num(id))))
            }

            RELATION.matches(path) && method == "PATCH" -> json(200, relationTemplate)

            else -> return null
        }
        val query = request.url.encodedQuery?.let { "?$it" }.orEmpty()
        calls += "$method $path$query" to text
        if (response.code == NOT_FOUND) notFound += "$method $path"
        return response
    }

    private fun list(updatedAfter: String?): MockResponse {
        val after = updatedAfter?.let { millisOf(it) }
        val windowStart = windowStart()
        val visible = entries.values.filter { !it.checked || (it.completedAt ?: Long.MIN_VALUE) >= windowStart }
            .filter { after == null || it.updatedAt >= after }
            .take(SERVER_LIMIT)
        return json(
            200,
            JsonObject(
                mapOf(
                    "count" to num(visible.size.toLong()),
                    "next" to JsonNull,
                    "previous" to JsonNull,
                    "timestamp" to JsonPrimitive(format(serverMillis())),
                    "results" to JsonArray(visible.map { toJson(it) }),
                ),
            ),
        )
    }

    private fun create(body: JsonObject): MockResponse {
        val food = body["food"] as? JsonObject ?: return json(500, null)
        val amount = (body["amount"] as? JsonPrimitive)?.doubleOrNull
            ?: return json(400, Json.parseToJsonElement("""{"amount":["This field is required."]}"""))
        val now = tick()
        val name = (food["name"] as JsonPrimitive).content
        val entry = Entry(
            id = nextId++,
            food = name,
            foodId = (food["id"] as? JsonPrimitive)?.longOrNull ?: foodId(name),
            unit = ((body["unit"] as? JsonObject)?.get("name") as? JsonPrimitive)?.content,
            amount = amount,
            // Tandoor sets completed_at only when `checked` flips on an existing entry.
            checked = (body["checked"] as? JsonPrimitive)?.booleanOrNull == true,
            lists = listIds(body),
            listRecipe = (body["list_recipe"] as? JsonPrimitive)?.longOrNull,
            ingredient = (body["ingredient"] as? JsonPrimitive)?.longOrNull,
            createdAt = now,
            updatedAt = now,
        )
        if (entry.listRecipe != null && entry.listRecipe !in listRecipes) {
            return json(400, Json.parseToJsonElement("""{"list_recipe":["Invalid pk"]}"""))
        }
        entries[entry.id] = entry
        return json(201, toJson(entry))
    }

    private fun entryAnswer(method: String, id: Long, body: JsonObject): MockResponse {
        val entry = entries[id] ?: return notFound("ShoppingListEntry")
        return when (method) {
            "GET" -> json(200, toJson(entry))

            "DELETE" -> {
                entries.remove(id)
                MockResponse.Builder().code(204).build()
            }

            "PATCH" -> {
                val now = tick()
                (body["checked"] as? JsonPrimitive)?.booleanOrNull?.let { checked ->
                    if (checked != entry.checked) entry.completedAt = if (checked) now else null
                    entry.checked = checked
                }
                (body["amount"] as? JsonPrimitive)?.doubleOrNull?.let { entry.amount = it }
                if ("unit" in body) entry.unit = ((body["unit"] as? JsonObject)?.get("name") as? JsonPrimitive)?.content
                ((body["food"] as? JsonObject)?.get("name") as? JsonPrimitive)?.content?.let {
                    entry.food = it
                    entry.foodId = foodId(it)
                }
                if ("shopping_lists" in body) entry.lists = listIds(body)
                (body["delay_until"] as? JsonPrimitive)?.contentOrNull?.let { entry.delayUntil = millisOf(it) }
                entry.updatedAt = now
                json(200, toJson(entry))
            }

            else -> json(405, null)
        }
    }

    private fun bulk(body: JsonObject): MockResponse {
        val ids = body.getValue("ids").jsonArray.mapNotNull { (it as JsonPrimitive).longOrNull }
        val checked = (body["checked"] as JsonPrimitive).booleanOrNull == true
        val now = tick()
        // Unknown or deleted ids are ignored: still 200 (FACTS "Fas 2 b").
        for (entry in ids.mapNotNull { entries[it] }) {
            entry.checked = checked
            entry.completedAt = if (checked) now else null
            entry.updatedAt = now
        }
        return json(200, JsonObject(body + ("timestamp" to JsonPrimitive(format(now)))))
    }

    private fun createListRecipe(body: JsonObject): MockResponse {
        val recipe = ListRecipe(
            id = nextId++,
            recipe = (body["recipe"] as? JsonPrimitive)?.longOrNull,
            servings = (body["servings"] as? JsonPrimitive)?.doubleOrNull ?: 1.0,
            mealplan = (body["mealplan"] as? JsonPrimitive)?.longOrNull,
        )
        listRecipes[recipe.id] = recipe
        return json(201, listRecipeJson(recipe))
    }

    private fun listRecipeAnswer(method: String, id: Long, body: JsonObject): MockResponse {
        val recipe = listRecipes[id] ?: return notFound("ShoppingListRecipe")
        return when (method) {
            "DELETE" -> {
                listRecipes.remove(id)
                entries.values.removeAll { it.listRecipe == id }
                MockResponse.Builder().code(204).build()
            }

            "PATCH" -> {
                (body["servings"] as? JsonPrimitive)?.doubleOrNull?.let { recipe.servings = it }
                json(200, listRecipeJson(recipe))
            }

            else -> json(405, null)
        }
    }

    // ------------------------------------------------------------------------------ JSON

    private fun toJson(entry: Entry): JsonObject {
        val food = JsonObject(
            entryTemplate.getValue("food").jsonObject + mapOf("id" to num(entry.foodId), "name" to JsonPrimitive(entry.food)),
        )
        val unit = entry.unit?.let { name ->
            JsonObject(entryTemplate.getValue("unit").jsonObject + mapOf("id" to num(foodId("unit:$name")), "name" to JsonPrimitive(name)))
        } ?: JsonNull
        val listRecipe = entry.listRecipe?.let { listRecipes[it] }
        return JsonObject(
            entryTemplate + mapOf(
                "id" to num(entry.id),
                "list_recipe" to (entry.listRecipe?.let { num(it) } ?: JsonNull),
                "shopping_lists" to JsonArray(entry.lists.map { listJson(it) }),
                "food" to food,
                "unit" to unit,
                "amount" to JsonPrimitive(entry.amount),
                "checked" to JsonPrimitive(entry.checked),
                "ingredient" to (entry.ingredient?.let { num(it) } ?: JsonNull),
                "list_recipe_data" to (listRecipe?.let { listRecipeJson(it) } ?: JsonNull),
                "created_by" to user(entry.createdBy),
                "created_at" to JsonPrimitive(format(entry.createdAt)),
                "updated_at" to JsonPrimitive(format(entry.updatedAt)),
                "completed_at" to (entry.completedAt?.let { JsonPrimitive(format(it)) } ?: JsonNull),
                "delay_until" to (entry.delayUntil?.let { JsonPrimitive(format(it)) } ?: JsonNull),
            ),
        )
    }

    private fun listRecipeJson(recipe: ListRecipe): JsonObject = JsonObject(
        listRecipeTemplate + mapOf(
            "id" to num(recipe.id),
            "recipe" to (recipe.recipe?.let { num(it) } ?: JsonNull),
            "recipe_data" to JsonNull,
            "mealplan" to (recipe.mealplan?.let { num(it) } ?: JsonNull),
            "servings" to JsonPrimitive(recipe.servings),
        ),
    )

    private fun user(id: Long): JsonElement {
        val template = entryTemplate.getValue("created_by").jsonObject
        if (id == OWN_USER) return template
        val partner = mapOf("id" to num(id), "username" to JsonPrimitive("partner"), "display_name" to JsonPrimitive(PARTNER_NAME))
        return JsonObject(template + partner)
    }

    private fun listIds(body: JsonObject): Set<Long> = (body["shopping_lists"] as? JsonArray).orEmpty()
        .mapNotNull { ((it as? JsonObject)?.get("id") as? JsonPrimitive)?.longOrNull }
        .toSet()

    private fun listJson(id: Long): JsonObject = JsonObject(mapOf("id" to num(id), "name" to JsonPrimitive("Lista $id")))

    private fun notFound(model: String): MockResponse =
        json(NOT_FOUND, JsonObject(mapOf("detail" to JsonPrimitive("No $model matches the given query."))))

    private fun millisOf(text: String): Long = OffsetDateTime.parse(text).toInstant().toEpochMilli()

    private fun foodId(name: String): Long = foodIds.getOrPut(name.lowercase()) { FIRST_FOOD_ID + foodIds.size }

    /** Midnight UTC, min(recent days, 14) days back (the list's window). */
    private fun windowStart(): Long {
        val now = serverMillis()
        return now - now.mod(DAY_MILLIS) - recentDays.coerceIn(0, 14) * DAY_MILLIS
    }

    /** The server's clock: the test clock, and a millisecond more for every change (so `updated_at` orders them). */
    private fun serverMillis(): Long = maxOf(clock.millis, serverMillis)

    private fun tick(): Long {
        serverMillis = serverMillis() + 1
        return serverMillis
    }

    private fun parse(text: String): JsonObject = if (text.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(text).jsonObject

    private fun body(name: String): JsonObject = Json.parseToJsonElement(TandoorFixtures.exchange(name, version).body!!).jsonObject

    private fun json(code: Int, body: JsonElement?): MockResponse = MockResponse.Builder()
        .code(code)
        .addHeader("Content-Type", if (body == null) "text/html" else "application/json")
        .body(body?.toString() ?: "<html>Server Error</html>")
        .build()

    private fun num(value: Long) = JsonPrimitive(value)

    companion object {
        const val OWN_USER = 1L
        const val PARTNER = 2L
        const val PARTNER_NAME = "Partner"
        const val DAY_MILLIS = 86_400_000L
        const val SERVER_LIMIT = 1000
        private const val NOT_FOUND = 404
        private const val FIRST_ID = 100L
        private const val FIRST_FOOD_ID = 500L
        private const val LIST = "/api/shopping-list-entry/"
        private const val BULK = "/api/shopping-list-entry/bulk/"
        private const val LIST_RECIPES = "/api/shopping-list-recipe/"
        private val ENTRY = Regex("^/api/shopping-list-entry/(\\d+)/$")
        private val LIST_RECIPE = Regex("^/api/shopping-list-recipe/(\\d+)/$")
        private val FOOD = Regex("^/api/food/(\\d+)/$")
        private val RELATION = Regex("^/api/supermarket-category-relation/(\\d+)/$")

        /** `yyyy-MM-ddTHH:mm:ss.SSSSSS+00:00`, like Tandoor (with the `+` that must be URL-encoded). */
        private val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSxxx")

        fun format(millis: Long): String = FORMAT.format(Instant.ofEpochMilli(millis).atOffset(ZoneOffset.UTC))
    }
}
