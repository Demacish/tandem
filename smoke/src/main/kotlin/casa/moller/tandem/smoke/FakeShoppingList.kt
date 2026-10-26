package casa.moller.tandem.smoke

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
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
 * Phase 2 (integration lane): a stateful Tandoor 2.6.13 shopping list for the release smoke test,
 * a port of core:sync's test fake (`FakeShoppingTandoor`) to the test APK. It answers in the
 * recorded shapes (`fas2/shopping-list-entry-create`, `fas2/shopping-list-recipe-create`, filled
 * with the state) and with the facts of FACTS "Fas 2 b-c": `updated_after` is `>=` on
 * `updated_at`, the list hides entries checked before midnight UTC − 7 days, `completed_at` is set
 * only when `checked` flips on an existing entry, bulk ignores unknown ids, a deleted entry or list
 * recipe answers 404 with Tandoor's body, and deleting a list recipe deletes its entries.
 *
 * "The partner" (another phone in the household) changes the state directly ([partnerCheck],
 * [partnerDelete], [serverAddMeal]). The clock is the real one: the app compares `completed_at`
 * and `delay_until` with the device's time.
 */
class FakeShoppingList(private val template: (String) -> JsonObject) {
    data class Entry(
        val id: Long,
        var food: String,
        var foodId: Long,
        var unit: String?,
        var amount: Double,
        var categoryId: Long? = null,
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
    private var lastMillis = 0L

    private val entryTemplate by lazy { template("fas2/shopping-list-entry-create") }
    private val listRecipeTemplate by lazy { template("fas2/shopping-list-recipe-create") }
    private val foodTemplate by lazy { template("fas2/food-patch-onhand") }
    private val relationTemplate by lazy { template("fas2/supermarket-category-relation-patch") }
    private val categories by lazy {
        template("fas2/supermarket-category-list").getValue("results").jsonArray.associateBy {
            (it.jsonObject.getValue("id") as JsonPrimitive).content.toLong()
        }
    }

    // ------------------------------------------------------------------------------ state

    /** Adds an entry on the server, by this account's user, in aisle [category] (null: none). */
    @Synchronized
    fun seed(food: String, amount: Double = 1.0, unit: String? = null, category: Long? = null): Long {
        val now = tick()
        val entry = Entry(
            id = nextId++,
            food = food,
            foodId = foodId(food),
            unit = unit,
            amount = amount,
            categoryId = category,
            createdAt = now,
            updatedAt = now,
        )
        entries[entry.id] = entry
        return entry.id
    }

    /** The partner checks (or unchecks) [id] on their phone. */
    @Synchronized
    fun partnerCheck(id: Long, checked: Boolean = true) {
        val entry = entries.getValue(id)
        val now = tick()
        entry.checked = checked
        entry.completedAt = if (checked) now else null
        entry.updatedAt = now
    }

    /** The partner deletes [ids] on their phone. */
    @Synchronized
    fun partnerDelete(ids: Collection<Long>) {
        ids.forEach { entries.remove(it) }
    }

    /**
     * The server puts a planned meal's recipe on the list itself (the web with
     * `mealplan_autoadd_shopping`, or `addshopping`): one list recipe linked to [mealId] and one
     * entry per food. Returns the list recipe's id.
     */
    @Synchronized
    fun serverAddMeal(mealId: Long, recipe: Long, servings: Double, foods: List<String>): Long {
        val listRecipe = ListRecipe(id = nextId++, recipe = recipe, servings = servings, mealplan = mealId)
        listRecipes[listRecipe.id] = listRecipe
        for (food in foods.distinct()) {
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

    @Synchronized
    fun all(): List<Entry> = entries.values.map { it.copy() }

    @Synchronized
    fun listRecipes(): List<ListRecipe> = listRecipes.values.map { it.copy() }

    @Synchronized
    fun hasListRecipeFor(mealId: Long): Boolean = listRecipes.values.any { it.mealplan == mealId }

    // ------------------------------------------------------------------------------ answers

    /** Answers a shopping list request, null for any other path. */
    @Synchronized
    fun answer(request: RecordedRequest): MockResponse? {
        val method = request.method
        val path = request.url.encodedPath
        val text = request.body?.utf8().orEmpty()
        return when {
            path == LIST && method == "GET" -> list(request.url.queryParameter("updated_after"))

            path == LIST && method == "POST" -> create(parse(text))

            path == BULK && method == "POST" -> bulk(parse(text))

            ENTRY.matches(path) -> entryAnswer(method, ENTRY.find(path)!!.groupValues[1].toLong(), parse(text))

            path == LIST_RECIPES && method == "POST" -> createListRecipe(parse(text))

            LIST_RECIPE.matches(path) -> listRecipeAnswer(method, LIST_RECIPE.find(path)!!.groupValues[1].toLong(), parse(text))

            FOOD.matches(path) && method == "PATCH" -> {
                val id = FOOD.find(path)!!.groupValues[1].toLong()
                json(OK, JsonObject(foodTemplate + ("id" to JsonPrimitive(id))))
            }

            RELATION.matches(path) && method == "PATCH" -> json(OK, relationTemplate)

            else -> null
        }
    }

    private fun list(updatedAfter: String?): MockResponse {
        val after = updatedAfter?.let { millisOf(it) }
        val windowStart = windowStart()
        val visible = entries.values.filter { !it.checked || (it.completedAt ?: Long.MIN_VALUE) >= windowStart }
            .filter { after == null || it.updatedAt >= after }
        return json(
            OK,
            JsonObject(
                mapOf(
                    "count" to JsonPrimitive(visible.size),
                    "next" to JsonNull,
                    "previous" to JsonNull,
                    "timestamp" to JsonPrimitive(format(now())),
                    "results" to JsonArray(visible.map { toJson(it) }),
                ),
            ),
        )
    }

    private fun create(body: JsonObject): MockResponse {
        val food = body["food"] as? JsonObject ?: return json(SERVER_ERROR, null)
        val amount = (body["amount"] as? JsonPrimitive)?.doubleOrNull
            ?: return json(BAD_REQUEST, Json.parseToJsonElement("""{"amount":["This field is required."]}"""))
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
            delayUntil = (body["delay_until"] as? JsonPrimitive)?.contentOrNull?.let { millisOf(it) },
            lists = listIds(body),
            listRecipe = (body["list_recipe"] as? JsonPrimitive)?.longOrNull,
            ingredient = (body["ingredient"] as? JsonPrimitive)?.longOrNull,
            createdAt = now,
            updatedAt = now,
        )
        if (entry.listRecipe != null && entry.listRecipe !in listRecipes) {
            return json(BAD_REQUEST, Json.parseToJsonElement("""{"list_recipe":["Invalid pk"]}"""))
        }
        entries[entry.id] = entry
        return json(CREATED, toJson(entry))
    }

    private fun entryAnswer(method: String, id: Long, body: JsonObject): MockResponse {
        val entry = entries[id] ?: return notFound("ShoppingListEntry")
        return when (method) {
            "GET" -> json(OK, toJson(entry))

            "DELETE" -> {
                entries.remove(id)
                MockResponse.Builder().code(NO_CONTENT).build()
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
                if ("delay_until" in body) entry.delayUntil = (body["delay_until"] as? JsonPrimitive)?.contentOrNull?.let { millisOf(it) }
                entry.updatedAt = now
                json(OK, toJson(entry))
            }

            else -> json(METHOD_NOT_ALLOWED, null)
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
        return json(OK, JsonObject(body + ("timestamp" to JsonPrimitive(format(now)))))
    }

    private fun createListRecipe(body: JsonObject): MockResponse {
        val recipe = ListRecipe(
            id = nextId++,
            recipe = (body["recipe"] as? JsonPrimitive)?.longOrNull,
            servings = (body["servings"] as? JsonPrimitive)?.doubleOrNull ?: 1.0,
            mealplan = (body["mealplan"] as? JsonPrimitive)?.longOrNull,
        )
        listRecipes[recipe.id] = recipe
        return json(CREATED, listRecipeJson(recipe))
    }

    private fun listRecipeAnswer(method: String, id: Long, body: JsonObject): MockResponse {
        val recipe = listRecipes[id] ?: return notFound("ShoppingListRecipe")
        return when (method) {
            "DELETE" -> {
                listRecipes.remove(id)
                entries.values.removeAll { it.listRecipe == id }
                MockResponse.Builder().code(NO_CONTENT).build()
            }

            "PATCH" -> {
                (body["servings"] as? JsonPrimitive)?.doubleOrNull?.let { recipe.servings = it }
                json(OK, listRecipeJson(recipe))
            }

            else -> json(METHOD_NOT_ALLOWED, null)
        }
    }

    // ------------------------------------------------------------------------------ JSON

    private fun toJson(entry: Entry): JsonObject {
        val food = foodJson(entry)
        val unit = unitJson(entry.unit)
        val listRecipe = entry.listRecipe?.let { listRecipes[it] }
        return JsonObject(
            entryTemplate + mapOf(
                "id" to JsonPrimitive(entry.id),
                "list_recipe" to (entry.listRecipe?.let { JsonPrimitive(it) } ?: JsonNull),
                "shopping_lists" to JsonArray(entry.lists.map { listJson(it) }),
                "food" to food,
                "unit" to unit,
                "amount" to JsonPrimitive(entry.amount),
                "checked" to JsonPrimitive(entry.checked),
                "ingredient" to (entry.ingredient?.let { JsonPrimitive(it) } ?: JsonNull),
                "list_recipe_data" to (listRecipe?.let { listRecipeJson(it) } ?: JsonNull),
                "created_by" to user(entry.createdBy),
                "created_at" to JsonPrimitive(format(entry.createdAt)),
                "updated_at" to JsonPrimitive(format(entry.updatedAt)),
                "completed_at" to (entry.completedAt?.let { JsonPrimitive(format(it)) } ?: JsonNull),
                "delay_until" to (entry.delayUntil?.let { JsonPrimitive(format(it)) } ?: JsonNull),
            ),
        )
    }

    private fun foodJson(entry: Entry): JsonObject {
        val category = entry.categoryId?.let { categories[it] } ?: JsonNull
        val fields = mapOf("id" to JsonPrimitive(entry.foodId), "name" to JsonPrimitive(entry.food), "supermarket_category" to category)
        return JsonObject(entryTemplate.getValue("food").jsonObject + fields)
    }

    private fun unitJson(name: String?): JsonElement {
        if (name == null) return JsonNull
        val fields = mapOf("id" to JsonPrimitive(foodId("unit:$name")), "name" to JsonPrimitive(name))
        return JsonObject(entryTemplate.getValue("unit").jsonObject + fields)
    }

    private fun listRecipeJson(recipe: ListRecipe): JsonObject = JsonObject(
        listRecipeTemplate + mapOf(
            "id" to JsonPrimitive(recipe.id),
            "recipe" to (recipe.recipe?.let { JsonPrimitive(it) } ?: JsonNull),
            "recipe_data" to JsonNull,
            "mealplan" to (recipe.mealplan?.let { JsonPrimitive(it) } ?: JsonNull),
            "servings" to JsonPrimitive(recipe.servings),
        ),
    )

    private fun user(id: Long): JsonElement {
        val template = entryTemplate.getValue("created_by").jsonObject
        if (id == OWN_USER) return template
        val partner = mapOf(
            "id" to JsonPrimitive(id),
            "username" to JsonPrimitive("partner"),
            "display_name" to JsonPrimitive(PARTNER_NAME),
        )
        return JsonObject(template + partner)
    }

    private fun listIds(body: JsonObject): Set<Long> = (body["shopping_lists"] as? JsonArray).orEmpty()
        .mapNotNull { ((it as? JsonObject)?.get("id") as? JsonPrimitive)?.longOrNull }
        .toSet()

    private fun listJson(id: Long): JsonObject = JsonObject(mapOf("id" to JsonPrimitive(id), "name" to JsonPrimitive("Lista $id")))

    private fun notFound(model: String): MockResponse =
        json(NOT_FOUND, JsonObject(mapOf("detail" to JsonPrimitive("No $model matches the given query."))))

    private fun millisOf(text: String): Long = OffsetDateTime.parse(text).toInstant().toEpochMilli()

    private fun foodId(name: String): Long = foodIds.getOrPut(name.lowercase()) { FIRST_FOOD_ID + foodIds.size }

    /** Midnight UTC, 7 days back (`shopping_recent_days` of fixture 12: the list's window). */
    private fun windowStart(): Long {
        val now = now()
        return now - now.mod(DAY_MILLIS) - RECENT_DAYS * DAY_MILLIS
    }

    /** The server's clock: real time, and a millisecond more for every change (so `updated_at` orders them). */
    private fun now(): Long = maxOf(System.currentTimeMillis(), lastMillis)

    private fun tick(): Long {
        lastMillis = now() + 1
        return lastMillis
    }

    private fun parse(text: String): JsonObject = if (text.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(text).jsonObject

    private fun json(code: Int, body: JsonElement?): MockResponse = MockResponse.Builder()
        .code(code)
        .addHeader("Content-Type", if (body == null) "text/html" else "application/json")
        .body(body?.toString() ?: "<html>Server Error</html>")
        .build()

    companion object {
        /** fas2 recordings: the logged-in user (11-user-by-id) and another member. */
        const val OWN_USER = 1L
        const val PARTNER = 2L
        const val PARTNER_NAME = "Partner"

        /** fas2/supermarket-category-list. */
        const val CATEGORY_VEGETABLES = 1L

        private const val DAY_MILLIS = 86_400_000L
        private const val RECENT_DAYS = 7L
        private const val OK = 200
        private const val CREATED = 201
        private const val NO_CONTENT = 204
        private const val BAD_REQUEST = 400
        private const val NOT_FOUND = 404
        private const val METHOD_NOT_ALLOWED = 405
        private const val SERVER_ERROR = 500
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
