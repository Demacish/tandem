package casa.moller.tandem.smoke

import java.util.concurrent.ConcurrentHashMap
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
 * Fas S (integration): a stateful Tandoor 2.6.13 pantry for the release smoke test, a port of
 * core:sync's test fake (`FakePantryTandoor`) to the test APK. It answers in the recorded `fas-s/`
 * shapes (location, entry and automation bodies filled with the state) and with the facts of
 * FACTS "Fas S": a location needs a household, an entry needs a unit and the WHOLE location, the
 * entry list hides amount 0 unless `empty` is given, FOOD_ALIAS is at most 128 characters,
 * `food/batch_update/` answers `{}`.
 *
 * `recipe?makenow=N` is computed like Tandoor does (plan §8.8): every recorded recipe with at most
 * N foods that are neither marked as at home ([onHand]) nor in the household's inventory with an
 * amount above 0; a recipe without ingredients is always in. [foodsOf] gives each recipe's foods.
 *
 * Food ids are the recorded ones (`fas1/food-list`), so the app's synced foods, the inventory and
 * the shopping list talk about the same food.
 */
class FakePantry(
    private val template: (String) -> JsonObject,
    private val foodsOf: (Long) -> List<String>,
    private val recipeIds: () -> List<Long>,
) {
    data class Location(val id: Long, var name: String, var isFreezer: Boolean, val householdId: Long)

    data class Entry(
        val id: Long,
        var locationId: Long,
        val foodId: Long,
        val foodName: String,
        val unit: String,
        var amount: Double,
        var expires: String?,
        var note: String,
    )

    data class Alias(val id: Long, val name: String, var param1: String, var param2: String)

    private val locations = LinkedHashMap<Long, Location>()
    private val entries = LinkedHashMap<Long, Entry>()
    private val aliases = LinkedHashMap<Long, Alias>()
    private val extraFoods = LinkedHashMap<String, Long>()
    private var nextId = FIRST_ID

    /** "Har hemma" per food id (the household's). */
    val onHand: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    /** Every pantry request this fake answered, as `METHOD path?query` with its body. */
    val calls: MutableList<Pair<String, String>> = CopyOnWriteArrayList()

    private val locationTemplate by lazy { template("fas-s/inventory-location-create") }
    private val entryTemplate by lazy { template("fas-s/inventory-entry-create") }
    private val aliasTemplate by lazy { template("fas-s/automation-create-barcode") }
    private val householdTemplate by lazy { locationTemplate.getValue("household").jsonObject }
    private val foodTemplate by lazy { entryTemplate.getValue("food").jsonObject }

    /** The recorded foods: name (lower case) -> id. */
    private val recordedFoods: Map<String, Long> by lazy {
        template("fas1/food-list").getValue("results").jsonArray.associate { food ->
            food.jsonObject.string("name").orEmpty().lowercase() to food.jsonObject.long("id")!!
        }
    }

    /** The recipe overviews of `fas1/recipe-list-default` by id (the rows of a makenow answer). */
    private val overviews: Map<Long, JsonObject> by lazy {
        template("fas1/recipe-list-default").getValue("results").jsonArray.associate { it.jsonObject.long("id")!! to it.jsonObject }
    }

    // ------------------------------------------------------------------------------ state

    @Synchronized
    fun foodId(name: String): Long =
        recordedFoods[name.lowercase()] ?: extraFoods.getOrPut(name.lowercase()) { FIRST_FOOD_ID + extraFoods.size }

    /** Marks [foods] as at home on the server ("the web"). */
    @Synchronized
    fun markOnHand(vararg foods: String) {
        foods.forEach { onHand += foodId(it) }
    }

    @Synchronized
    fun seedLocation(name: String, isFreezer: Boolean = false): Long {
        val id = nextId++
        locations[id] = Location(id, name, isFreezer, OWN_HOUSEHOLD)
        return id
    }

    @Synchronized
    fun seedEntry(locationId: Long, food: String, amount: Double, unit: String, expires: String?): Long {
        val id = nextId++
        entries[id] = Entry(id, locationId, foodId(food), food, unit, amount, expires, "")
        return id
    }

    @Synchronized
    fun locations(): List<Location> = locations.values.map { it.copy() }

    @Synchronized
    fun entries(): List<Entry> = entries.values.map { it.copy() }

    @Synchronized
    fun aliases(): List<Alias> = aliases.values.map { it.copy() }

    fun requests(method: String, pathPrefix: String): List<Pair<String, String>> =
        calls.filter { (call, _) -> call.startsWith("$method $pathPrefix") }

    /** What `recipe?makenow=N` answers now: the recipe ids with at most [n] missing foods. */
    @Synchronized
    fun makeNow(n: Int): List<Long> = recipeIds().filter { id -> missing(id).size <= n }

    /** The foods of [recipe] that are not at home (Tandoor's rule, see the class comment). */
    @Synchronized
    fun missing(recipe: Long): List<String> {
        val stocked = entries.values.filter { it.amount > 0 && locations[it.locationId]?.householdId == OWN_HOUSEHOLD }
            .map { it.foodId }
            .toSet()
        return foodsOf(recipe).filter { food -> foodId(food).let { it !in onHand && it !in stocked } }
    }

    // ------------------------------------------------------------------------------ answers

    /** Answers a pantry request, null for every other path. */
    @Synchronized
    fun answer(request: RecordedRequest): MockResponse? {
        val method = request.method
        val path = request.url.encodedPath
        val text = request.body?.utf8().orEmpty()
        if (FOOD.matches(path) && method == "PATCH") {
            // A single food marked at home (Phase 2's PATCH): remembered, answered by the shopping fake.
            rememberOnHand(FOOD.find(path)!!.groupValues[1].toLong(), parse(text))
            return null
        }
        val response = when (path) {
            LOCATIONS -> if (method == "GET") page(locations.values.map { locationJson(it) }) else createLocation(parse(text))

            ENTRIES -> if (method == "GET") listEntries("empty" in request.url.queryParameterNames) else createEntry(parse(text))

            AUTOMATIONS -> if (method == "GET") page(aliases.values.map { aliasJson(it) }) else createAlias(parse(text))

            BATCH -> batch(parse(text))

            FOODS -> if (method == "POST") createFood(parse(text)) else null

            RECIPES -> request.url.queryParameter("makenow")?.let(::makeNowPage)

            else -> byId(method, path, parse(text))
        } ?: return null
        val query = request.url.encodedQuery?.let { "?$it" }.orEmpty()
        calls += "$method $path$query" to text
        return response
    }

    private fun byId(method: String, path: String, body: JsonObject): MockResponse? = when {
        LOCATION.matches(path) -> locationAnswer(method, LOCATION.find(path)!!.groupValues[1].toLong(), body)
        ENTRY.matches(path) -> entryAnswer(method, ENTRY.find(path)!!.groupValues[1].toLong(), body)
        AUTOMATION.matches(path) -> aliasAnswer(method, AUTOMATION.find(path)!!.groupValues[1].toLong(), body)
        else -> null
    }

    private fun rememberOnHand(id: Long, body: JsonObject) {
        when ((body["food_onhand"] as? JsonPrimitive)?.booleanOrNull) {
            true -> onHand += id
            false -> onHand -= id
            null -> Unit
        }
    }

    /** `GET /api/food/` as recorded, with [onHand] as `food_onhand`. */
    @Synchronized
    fun withOnHand(recorded: JsonElement): JsonElement {
        val body = recorded.jsonObject
        val results = body.getValue("results").jsonArray.map { food ->
            val id = food.jsonObject.long("id")
            JsonObject(food.jsonObject + ("food_onhand" to JsonPrimitive(id != null && id in onHand)))
        }
        return JsonObject(body + ("results" to JsonArray(results)))
    }

    private fun makeNowPage(value: String): MockResponse {
        // Tandoor: an integer N (`true` = 0); anything else is ignored (FACTS "Fas S a").
        val n = when (value) {
            "true" -> 0
            else -> value.toIntOrNull() ?: return page(recipeIds().mapNotNull { overviews[it] })
        }
        return page(makeNow(n).mapNotNull { overviews[it] })
    }

    private fun createLocation(body: JsonObject): MockResponse {
        val household = body["household"] as? JsonObject ?: return badRequest("household", "This field is required.")
        val householdId = household.long("id") ?: return badRequest("household", "Invalid.")
        val id = nextId++
        val freezer = (body["is_freezer"] as? JsonPrimitive)?.booleanOrNull == true
        val location = Location(id, body.string("name").orEmpty(), freezer, householdId)
        locations[id] = location
        return json(CREATED, locationJson(location))
    }

    private fun locationAnswer(method: String, id: Long, body: JsonObject): MockResponse {
        val location = locations[id] ?: return notFound("InventoryLocation")
        return when (method) {
            "PATCH" -> {
                body.string("name")?.let { location.name = it }
                (body["is_freezer"] as? JsonPrimitive)?.booleanOrNull?.let { location.isFreezer = it }
                json(OK, locationJson(location))
            }

            "DELETE" -> {
                locations.remove(id)
                entries.values.removeAll { it.locationId == id }
                MockResponse.Builder().code(NO_CONTENT).build()
            }

            else -> json(METHOD_NOT_ALLOWED, null)
        }
    }

    private fun listEntries(withEmpty: Boolean): MockResponse =
        page(entries.values.filter { withEmpty || it.amount > 0 }.map { entryJson(it) })

    /** The nested location: the whole object or 400 (FACTS "Fas S b"). */
    private fun nestedLocation(value: JsonElement?): Location? {
        val obj = value as? JsonObject ?: return null
        if (obj["name"] == null || obj["household"] == null) return null
        val location = obj.long("id")?.let { locations[it] } ?: return null
        obj.string("name")?.let { location.name = it }
        return location
    }

    private fun createEntry(body: JsonObject): MockResponse {
        val location = nestedLocation(body["inventory_location"])
            ?: return badRequest("inventory_location", "The whole location is required.")
        val unit = body["unit"] as? JsonObject ?: return badRequest("unit", "This field is required.")
        val food = body["food"] as? JsonObject ?: return badRequest("food", "This field is required.")
        val name = food.string("name").orEmpty()
        val id = nextId++
        val entry = Entry(
            id = id,
            locationId = location.id,
            foodId = food.long("id") ?: foodId(name),
            foodName = name,
            unit = unit.string("name").orEmpty(),
            amount = (body["amount"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
            expires = body.string("expires"),
            note = body.string("note").orEmpty(),
        )
        entries[id] = entry
        return json(CREATED, entryJson(entry))
    }

    private fun entryAnswer(method: String, id: Long, body: JsonObject): MockResponse {
        val entry = entries[id] ?: return notFound("InventoryEntry")
        return when (method) {
            "GET" -> json(OK, entryJson(entry))

            "PATCH" -> {
                if ("inventory_location" in body) {
                    val location = nestedLocation(body["inventory_location"]) ?: return badRequest("inventory_location", "Invalid.")
                    entry.locationId = location.id
                }
                (body["amount"] as? JsonPrimitive)?.doubleOrNull?.let { entry.amount = it }
                if ("expires" in body) entry.expires = body.string("expires")
                body.string("note")?.let { entry.note = it }
                json(OK, entryJson(entry))
            }

            "DELETE" -> {
                entries.remove(id)
                MockResponse.Builder().code(NO_CONTENT).build()
            }

            else -> json(METHOD_NOT_ALLOWED, null)
        }
    }

    private fun createAlias(body: JsonObject): MockResponse {
        val param1 = body.string("param_1").orEmpty()
        if (param1.length > MAX_PARAM) return badRequest("param_1", "Ensure this field has no more than 128 characters.")
        val id = nextId++
        val alias = Alias(id, body.string("name").orEmpty(), param1, body.string("param_2").orEmpty())
        aliases[id] = alias
        return json(CREATED, aliasJson(alias))
    }

    private fun aliasAnswer(method: String, id: Long, body: JsonObject): MockResponse {
        val alias = aliases[id] ?: return notFound("Automation")
        return when (method) {
            "PATCH" -> {
                body.string("param_2")?.let { alias.param2 = it }
                json(OK, aliasJson(alias))
            }

            "DELETE" -> {
                aliases.remove(id)
                MockResponse.Builder().code(NO_CONTENT).build()
            }

            else -> json(METHOD_NOT_ALLOWED, null)
        }
    }

    private fun batch(body: JsonObject): MockResponse {
        val ids = (body["foods"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.longOrNull }
        when ((body["on_hand"] as? JsonPrimitive)?.booleanOrNull) {
            true -> onHand += ids
            false -> onHand -= ids.toSet()
            null -> Unit
        }
        // An empty object (FACTS "Fas S e").
        return json(OK, JsonObject(emptyMap()))
    }

    private fun createFood(body: JsonObject): MockResponse {
        val name = body.string("name").orEmpty()
        val fields = mapOf("id" to JsonPrimitive(foodId(name)), "name" to JsonPrimitive(name), "full_name" to JsonPrimitive(name))
        val food = foodTemplate + fields
        return json(CREATED, JsonObject(food))
    }

    // ------------------------------------------------------------------------------ shapes

    private fun locationJson(location: Location): JsonObject = JsonObject(
        locationTemplate + mapOf(
            "id" to JsonPrimitive(location.id),
            "name" to JsonPrimitive(location.name),
            "is_freezer" to JsonPrimitive(location.isFreezer),
            "household" to JsonObject(householdTemplate + ("id" to JsonPrimitive(location.householdId))),
        ),
    )

    private fun entryJson(entry: Entry): JsonObject = JsonObject(
        entryTemplate + mapOf(
            "id" to JsonPrimitive(entry.id),
            "inventory_location" to (locations[entry.locationId]?.let { locationJson(it) } ?: JsonNull),
            "sub_location" to JsonNull,
            "code" to JsonPrimitive(entry.id.toString()),
            "food" to JsonObject(
                foodTemplate + mapOf(
                    "id" to JsonPrimitive(entry.foodId),
                    "name" to JsonPrimitive(entry.foodName),
                    "full_name" to JsonPrimitive(entry.foodName),
                    "food_onhand" to JsonPrimitive(entry.foodId in onHand),
                ),
            ),
            "unit" to JsonObject(entryTemplate.getValue("unit").jsonObject + ("name" to JsonPrimitive(entry.unit))),
            "amount" to JsonPrimitive(entry.amount),
            "expires" to (entry.expires?.let { JsonPrimitive(it) } ?: JsonNull),
            "note" to JsonPrimitive(entry.note),
        ),
    )

    private fun aliasJson(alias: Alias): JsonObject = JsonObject(
        aliasTemplate + mapOf(
            "id" to JsonPrimitive(alias.id),
            "name" to JsonPrimitive(alias.name),
            "param_1" to JsonPrimitive(alias.param1),
            "param_2" to JsonPrimitive(alias.param2),
        ),
    )

    private fun page(results: List<JsonObject>): MockResponse = json(
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

    private fun badRequest(field: String, message: String): MockResponse =
        json(BAD_REQUEST, JsonObject(mapOf(field to JsonArray(listOf(JsonPrimitive(message))))))

    private fun parse(text: String): JsonObject =
        if (text.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(text) as? JsonObject ?: JsonObject(emptyMap())

    private fun json(code: Int, body: JsonElement?): MockResponse = MockResponse.Builder()
        .code(code)
        .addHeader("Content-Type", "application/json")
        .body(body?.toString().orEmpty())
        .build()

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

    companion object {
        /** The household of the recordings (`fas2/user-space-list-admin`, FakeTandoor.household). */
        const val OWN_HOUSEHOLD = 1L

        private const val OK = 200
        private const val CREATED = 201
        private const val NO_CONTENT = 204
        private const val BAD_REQUEST = 400
        private const val NOT_FOUND = 404
        private const val METHOD_NOT_ALLOWED = 405
        private const val FIRST_ID = 500L
        private const val FIRST_FOOD_ID = 900L
        private const val MAX_PARAM = 128
        private const val LOCATIONS = "/api/inventory-location/"
        private val LOCATION = Regex("^/api/inventory-location/(\\d+)/$")
        private const val ENTRIES = "/api/inventory-entry/"
        private val ENTRY = Regex("^/api/inventory-entry/(\\d+)/$")
        private const val AUTOMATIONS = "/api/automation/"
        private val AUTOMATION = Regex("^/api/automation/(\\d+)/$")
        private const val BATCH = "/api/food/batch_update/"
        private const val FOODS = "/api/food/"
        private val FOOD = Regex("^/api/food/(\\d+)/$")
        private const val RECIPES = "/api/recipe/"
    }
}
