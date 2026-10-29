package casa.moller.tandem.core.sync.pantry

import casa.moller.tandem.core.sync.testing.FakeTandoorServer
import casa.moller.tandem.core.testing.fixtures.TandoorFixtures
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
 * A stateful Tandoor pantry for the sync tests, answering in the recorded shapes of [version]
 * (`fas-s/` of 2.6.13 and 2.7.1: the location, entry, automation and food bodies are the
 * recordings with the state filled in) and with the facts of FACTS "Fas S": locations and entries
 * are space-wide (every household sees them), a location needs a household, an entry needs a unit
 * and the WHOLE location (only `{id}` = 400, another name renames it), the list hides amount 0
 * unless `empty` is given (any value), DELETE of a location deletes its entries, FOOD_ALIAS has no
 * uniqueness and at most 128 characters, `food/batch_update/` answers `{}`, and `recipe?makenow=N`
 * answers what the test set in [makeNow].
 *
 * "Another phone" or "another household" changes the state directly ([seedLocation], [seedEntry],
 * [seedAlias], [partnerDeleteEntry]).
 */
internal class FakePantryTandoor(private val version: String) {
    data class Location(val id: Long, var name: String, var isFreezer: Boolean, val householdId: Long, var householdName: String)

    data class Entry(
        val id: Long,
        var locationId: Long,
        val foodId: Long,
        val foodName: String,
        val unit: String,
        var amount: Double,
        var expires: String?,
        var note: String,
        var subLocation: String?,
        val createdBy: Long,
    )

    data class Alias(val id: Long, val name: String, var param1: String, var param2: String, var disabled: Boolean = false)

    private val locations = LinkedHashMap<Long, Location>()
    private val entries = LinkedHashMap<Long, Entry>()
    private val aliases = LinkedHashMap<Long, Alias>()
    private val foods = LinkedHashMap<String, Long>()
    private var nextId = FIRST_ID

    /** "Har hemma" per food id (the household's; one household here). */
    val onHand: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    /** `recipe?makenow=N` → the recipe overviews of the answer (the test fills it from the recordings). */
    val makeNow: MutableMap<Int, List<JsonObject>> = ConcurrentHashMap()

    /** Every handled request as `METHOD path?query` and its body. */
    val calls: MutableList<Pair<String, String>> = CopyOnWriteArrayList()

    /** Every answer with status 404. */
    val notFound: MutableList<String> = CopyOnWriteArrayList()

    private val locationTemplate = body("fas-s/inventory-location-create")
    private val entryTemplate = body("fas-s/inventory-entry-create")
    private val aliasTemplate = body("fas-s/automation-create-barcode")
    private val foodTemplate = entryTemplate.getValue("food").jsonObject
    private val householdTemplate = locationTemplate.getValue("household").jsonObject

    /** Installs this fake in front of whatever [server] answered before (another fake, then the recordings). */
    fun install(server: FakeTandoorServer) {
        val previous = server.handler
        server.handler = { request -> answer(request) ?: previous?.invoke(request) }
    }

    // ------------------------------------------------------------------------------ state

    @Synchronized
    fun seedLocation(name: String, isFreezer: Boolean = false, householdId: Long = OWN_HOUSEHOLD): Long {
        val id = nextId++
        val householdName = if (householdId == OWN_HOUSEHOLD) OWN_HOUSEHOLD_NAME else OTHER_HOUSEHOLD_NAME
        locations[id] = Location(id, name, isFreezer, householdId, householdName)
        return id
    }

    @Synchronized
    fun seedEntry(
        locationId: Long,
        food: String,
        amount: Double = 1.0,
        expires: String? = null,
        by: Long = PARTNER,
        withFoodId: Long? = null,
    ): Long {
        val id = nextId++
        entries[id] = Entry(id, locationId, withFoodId ?: foodId(food), food, "st", amount, expires, "", null, by)
        return id
    }

    @Synchronized
    fun seedAlias(param1: String, param2: String, name: String = "Tandem: streckkod"): Long {
        val id = nextId++
        aliases[id] = Alias(id, name, param1, param2)
        return id
    }

    @Synchronized
    fun partnerDeleteEntry(id: Long) {
        entries.remove(id)
    }

    @Synchronized
    fun locations(): List<Location> = locations.values.map { it.copy() }

    @Synchronized
    fun entries(): List<Entry> = entries.values.map { it.copy() }

    @Synchronized
    fun aliases(): List<Alias> = aliases.values.map { it.copy() }

    @Synchronized
    fun foodId(name: String): Long = foods.getOrPut(name.lowercase()) { FIRST_FOOD_ID + foods.size }

    fun requests(method: String, pathPrefix: String): List<Pair<String, String>> =
        calls.filter { (call, _) -> call.startsWith("$method $pathPrefix") }

    // ------------------------------------------------------------------------------ answers

    @Synchronized
    fun answer(request: RecordedRequest): MockResponse? {
        val method = request.method
        val path = request.url.encodedPath
        val text = request.body?.utf8().orEmpty()
        val response = when {
            path == LOCATIONS && method == "GET" -> page(locations.values.map { locationJson(it) })
            path == LOCATIONS && method == "POST" -> createLocation(parse(text))
            LOCATION.matches(path) -> locationAnswer(method, LOCATION.find(path)!!.groupValues[1].toLong(), parse(text))
            path == ENTRIES && method == "GET" -> listEntries(withEmpty = "empty" in request.url.queryParameterNames)
            path == ENTRIES && method == "POST" -> createEntry(parse(text))
            ENTRY.matches(path) -> entryAnswer(method, ENTRY.find(path)!!.groupValues[1].toLong(), parse(text))
            path == AUTOMATIONS && method == "GET" -> page(aliases.values.map { aliasJson(it) })
            path == AUTOMATIONS && method == "POST" -> createAlias(parse(text))
            AUTOMATION.matches(path) -> aliasAnswer(method, AUTOMATION.find(path)!!.groupValues[1].toLong(), parse(text))
            path == BATCH && method == "PUT" -> batch(parse(text))
            path == FOODS && method == "POST" -> createFood(parse(text))
            path == RECIPES && method == "GET" && request.url.queryParameter("makenow") != null -> makeNowPage(request)
            else -> return null
        }
        val query = request.url.encodedQuery?.let { "?$it" }.orEmpty()
        calls += "$method $path$query" to text
        if (response.code == NOT_FOUND) notFound += "$method $path"
        return response
    }

    private fun makeNowPage(request: RecordedRequest): MockResponse =
        page(makeNow[request.url.queryParameter("makenow")!!.toInt()].orEmpty())

    private fun createLocation(body: JsonObject): MockResponse {
        val household = body["household"] as? JsonObject ?: return badRequest("household", "This field is required.")
        val householdId = (household["id"] as? JsonPrimitive)?.longOrNull ?: return badRequest("household", "Invalid.")
        val id = nextId++
        val location = Location(
            id = id,
            name = (body["name"] as JsonPrimitive).content,
            isFreezer = (body["is_freezer"] as? JsonPrimitive)?.booleanOrNull == true,
            householdId = householdId,
            householdName = (household["name"] as? JsonPrimitive)?.content.orEmpty(),
        )
        locations[id] = location
        return json(201, locationJson(location))
    }

    private fun locationAnswer(method: String, id: Long, body: JsonObject): MockResponse {
        val location = locations[id] ?: return notFound("InventoryLocation")
        return when (method) {
            "PATCH" -> {
                (body["name"] as? JsonPrimitive)?.content?.let { location.name = it }
                (body["is_freezer"] as? JsonPrimitive)?.booleanOrNull?.let { location.isFreezer = it }
                json(200, locationJson(location))
            }

            "DELETE" -> {
                locations.remove(id)
                // CASCADE (FACTS "Fas S b").
                entries.values.removeAll { it.locationId == id }
                MockResponse.Builder().code(204).build()
            }

            else -> json(405, null)
        }
    }

    private fun listEntries(withEmpty: Boolean): MockResponse {
        val listed = entries.values.filter { withEmpty || it.amount > 0 }
            .sortedWith(compareBy<Entry> { it.expires == null }.thenBy { it.expires })
        return page(listed.map { entryJson(it) })
    }

    /** The nested location: the whole object or 400; another name renames it (FACTS "Fas S b"). */
    private fun nestedLocation(value: JsonElement?): Pair<Location?, MockResponse?> {
        val obj = value as? JsonObject ?: return null to badRequest("inventory_location", "This field is required.")
        val id = (obj["id"] as? JsonPrimitive)?.longOrNull
        if (obj["name"] == null || obj["household"] == null) {
            return null to badRequest("inventory_location", "name and household are required.")
        }
        val location = id?.let { locations[it] } ?: return null to badRequest("inventory_location", "Invalid pk.")
        (obj["name"] as? JsonPrimitive)?.content?.let { location.name = it }
        return location to null
    }

    private fun createEntry(body: JsonObject): MockResponse {
        val (location, failed) = nestedLocation(body["inventory_location"])
        if (failed != null) return failed
        if ("unit" !in body) return badRequest("unit", "This field is required.")
        val unit = body["unit"] as? JsonObject ?: return badRequest("unit", "This field may not be null.")
        val food = body.getValue("food").jsonObject
        val name = (food["name"] as JsonPrimitive).content
        val id = nextId++
        val entry = Entry(
            id = id,
            locationId = location!!.id,
            foodId = (food["id"] as? JsonPrimitive)?.longOrNull ?: foodId(name),
            foodName = name,
            unit = (unit["name"] as JsonPrimitive).content,
            amount = (body["amount"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
            expires = (body["expires"] as? JsonPrimitive)?.contentOrNull,
            note = (body["note"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            subLocation = (body["sub_location"] as? JsonPrimitive)?.contentOrNull,
            createdBy = OWN_USER,
        )
        entries[id] = entry
        return json(201, entryJson(entry))
    }

    private fun entryAnswer(method: String, id: Long, body: JsonObject): MockResponse {
        val entry = entries[id] ?: return notFound("InventoryEntry")
        return when (method) {
            "GET" -> json(200, entryJson(entry))

            "PATCH" -> {
                if ("inventory_location" in body) {
                    val (location, failed) = nestedLocation(body["inventory_location"])
                    if (failed != null) return failed
                    entry.locationId = location!!.id
                }
                (body["amount"] as? JsonPrimitive)?.doubleOrNull?.let { entry.amount = it }
                if ("expires" in body) entry.expires = (body["expires"] as? JsonPrimitive)?.contentOrNull
                (body["note"] as? JsonPrimitive)?.contentOrNull?.let { entry.note = it }
                if ("sub_location" in body) entry.subLocation = (body["sub_location"] as? JsonPrimitive)?.contentOrNull
                json(200, entryJson(entry))
            }

            "DELETE" -> {
                entries.remove(id)
                MockResponse.Builder().code(204).build()
            }

            else -> json(405, null)
        }
    }

    private fun createAlias(body: JsonObject): MockResponse {
        val param1 = (body["param_1"] as? JsonPrimitive)?.content.orEmpty()
        if (param1.length > MAX_PARAM) return badRequest("param_1", "Ensure this field has no more than 128 characters.")
        val id = nextId++
        val name = (body["name"] as? JsonPrimitive)?.content.orEmpty()
        val alias = Alias(id, name, param1, (body["param_2"] as? JsonPrimitive)?.content.orEmpty())
        aliases[id] = alias
        return json(201, aliasJson(alias))
    }

    private fun aliasAnswer(method: String, id: Long, body: JsonObject): MockResponse {
        val alias = aliases[id] ?: return notFound("Automation")
        return when (method) {
            "PATCH" -> {
                (body["param_2"] as? JsonPrimitive)?.content?.let { alias.param2 = it }
                json(200, aliasJson(alias))
            }

            "DELETE" -> {
                aliases.remove(id)
                MockResponse.Builder().code(204).build()
            }

            else -> json(405, null)
        }
    }

    private fun batch(body: JsonObject): MockResponse {
        val ids = body.getValue("foods").jsonArray.mapNotNull { (it as JsonPrimitive).longOrNull }
        when ((body["on_hand"] as? JsonPrimitive)?.booleanOrNull) {
            true -> onHand += ids
            false -> onHand -= ids.toSet()
            null -> Unit
        }
        // An empty object (FACTS "Fas S e").
        return json(200, JsonObject(emptyMap()))
    }

    private fun createFood(body: JsonObject): MockResponse {
        val name = (body["name"] as JsonPrimitive).content
        val food = foodTemplate + mapOf("id" to num(foodId(name)), "name" to JsonPrimitive(name), "full_name" to JsonPrimitive(name))
        return json(201, JsonObject(food))
    }

    // ------------------------------------------------------------------------------ shapes

    private fun householdJson(location: Location): JsonObject =
        JsonObject(householdTemplate + mapOf("id" to num(location.householdId), "name" to JsonPrimitive(location.householdName)))

    private fun locationJson(location: Location): JsonObject = JsonObject(
        locationTemplate + mapOf(
            "id" to num(location.id),
            "name" to JsonPrimitive(location.name),
            "is_freezer" to JsonPrimitive(location.isFreezer),
            "household" to householdJson(location),
        ),
    )

    private fun entryJson(entry: Entry): JsonObject {
        val location = locations[entry.locationId]
        return JsonObject(
            entryTemplate + mapOf(
                "id" to num(entry.id),
                "inventory_location" to (location?.let { locationJson(it) } ?: JsonNull),
                "sub_location" to (entry.subLocation?.let { JsonPrimitive(it) } ?: JsonNull),
                "code" to JsonPrimitive(entry.id.toString(HEX).uppercase()),
                "food" to JsonObject(
                    foodTemplate + mapOf(
                        "id" to num(entry.foodId),
                        "name" to JsonPrimitive(entry.foodName),
                        "full_name" to JsonPrimitive(entry.foodName),
                        "food_onhand" to JsonPrimitive(entry.foodId in onHand),
                    ),
                ),
                "unit" to JsonObject(entryTemplate.getValue("unit").jsonObject + ("name" to JsonPrimitive(entry.unit))),
                "amount" to JsonPrimitive(entry.amount),
                "expires" to (entry.expires?.let { JsonPrimitive(it) } ?: JsonNull),
                "note" to JsonPrimitive(entry.note),
                "created_by" to num(entry.createdBy),
            ),
        )
    }

    private fun aliasJson(alias: Alias): JsonObject = JsonObject(
        aliasTemplate + mapOf(
            "id" to num(alias.id),
            "name" to JsonPrimitive(alias.name),
            "param_1" to JsonPrimitive(alias.param1),
            "param_2" to JsonPrimitive(alias.param2),
            "disabled" to JsonPrimitive(alias.disabled),
        ),
    )

    private fun page(results: List<JsonObject>): MockResponse = json(
        200,
        JsonObject(
            mapOf(
                "count" to num(results.size.toLong()),
                "next" to JsonNull,
                "previous" to JsonNull,
                "results" to JsonArray(results),
            ),
        ),
    )

    private fun notFound(model: String): MockResponse =
        json(404, JsonObject(mapOf("detail" to JsonPrimitive("No $model matches the given query."))))

    private fun badRequest(field: String, message: String): MockResponse =
        json(400, JsonObject(mapOf(field to JsonArray(listOf(JsonPrimitive(message))))))

    private fun body(name: String): JsonObject = Json.parseToJsonElement(TandoorFixtures.exchange(name, version).body!!).jsonObject

    companion object {
        const val OWN_USER = 1L
        const val PARTNER = 2L
        const val OWN_HOUSEHOLD = 1L
        const val OWN_HOUSEHOLD_NAME = "Contract Household"
        const val OTHER_HOUSEHOLD = 2L
        const val OTHER_HOUSEHOLD_NAME = "Other Household"
        const val NOT_FOUND = 404
        private const val FIRST_ID = 500L
        private const val FIRST_FOOD_ID = 900L
        private const val MAX_PARAM = 128
        private const val HEX = 16

        private const val LOCATIONS = "/api/inventory-location/"
        private val LOCATION = Regex("^/api/inventory-location/(\\d+)/$")
        private const val ENTRIES = "/api/inventory-entry/"
        private val ENTRY = Regex("^/api/inventory-entry/(\\d+)/$")
        private const val AUTOMATIONS = "/api/automation/"
        private val AUTOMATION = Regex("^/api/automation/(\\d+)/$")
        private const val BATCH = "/api/food/batch_update/"
        private const val FOODS = "/api/food/"
        private const val RECIPES = "/api/recipe/"

        fun num(value: Long): JsonPrimitive = JsonPrimitive(value)

        fun parse(text: String): JsonObject =
            if (text.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(text) as? JsonObject ?: JsonObject(emptyMap())

        fun json(code: Int, body: JsonElement?): MockResponse = MockResponse.Builder()
            .code(code)
            .addHeader("Content-Type", "application/json")
            .body(body?.toString().orEmpty())
            .build()

        /** The recipe overviews of a recorded `recipe/` answer of [version] ([name] below `fas-s/`). */
        fun overviews(name: String, version: String): List<JsonObject> =
            Json.parseToJsonElement(TandoorFixtures.exchange("fas-s/$name", version).body!!).jsonObject
                .getValue("results").jsonArray.map { it.jsonObject }
    }
}
