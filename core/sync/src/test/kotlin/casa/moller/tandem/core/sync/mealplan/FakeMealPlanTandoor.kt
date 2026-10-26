package casa.moller.tandem.core.sync.mealplan

import casa.moller.tandem.core.sync.testing.FakeTandoorServer
import casa.moller.tandem.core.testing.fixtures.TandoorFixtures
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest

/**
 * A stateful Tandoor meal plan in Europe/Stockholm for the sync tests, answering in the recorded
 * shapes of [version] (`fas2/meal-plan-create-addshopping`, `fas2/meal-type-create`) with the facts
 * of FACTS "Fas 1 g" and "Fas 2 f":
 *
 * - a value without offset is read in the server's zone; on create a naive midnight gets the meal
 *   type's time (12:00 without one); dates come back with the server's offset;
 * - `?from_date&to_date` filters on the server-local date of `to_date`, both inclusive;
 * - `PATCH {from_date}` alone leaves `to_date`; 404 with Tandoor's body for a deleted meal.
 *
 * "The web" and "another phone" change the state directly ([web], [partnerEdit], [partnerDelete]).
 */
internal class FakeMealPlanTandoor(private val version: String) {
    data class Meal(
        val id: Long,
        var title: String,
        var recipe: Long?,
        var recipeName: String?,
        var servings: Double,
        var note: String,
        var from: ZonedDateTime,
        var to: ZonedDateTime,
        var mealType: Long,
        var shopping: Boolean = false,
    ) {
        val fromDay: LocalDate get() = from.toLocalDate()
        val toDay: LocalDate get() = to.toLocalDate()
    }

    data class MealType(val id: Long, val name: String, val order: Int, val time: LocalTime?)

    private val meals = LinkedHashMap<Long, Meal>()
    private val types = LinkedHashMap<Long, MealType>()
    private var nextMealId = 100L
    private var nextTypeId = 10L

    /** Every handled request as `METHOD path?query` with its body (empty when none). */
    val calls: MutableList<Pair<String, String>> = CopyOnWriteArrayList()

    /** Every answer with status 404 (a "404 in the Caddy log"). */
    val notFound: MutableList<String> = CopyOnWriteArrayList()

    private val mealTemplate = body("fas2/meal-plan-create-addshopping")
    private val typeTemplate = body("fas2/meal-type-create")

    init {
        types[DINNER] = MealType(DINNER, "Middag", 0, LocalTime.of(18, 0))
        types[BREAKFAST] = MealType(BREAKFAST, "Frukost", 2, LocalTime.of(7, 30))
    }

    fun install(server: FakeTandoorServer) {
        server.handler = { request -> answer(request) }
    }

    // ------------------------------------------------------------------------------ state

    /** A meal created on the web: [from] and [to] as the browser sent them (offset, `Z` or naive). */
    @Synchronized
    fun web(
        from: String,
        to: String = from,
        title: String = "",
        recipe: Long? = RECIPE,
        type: Long = DINNER,
        servings: Double = 4.0,
    ): Long {
        val id = nextMealId++
        val start = read(from, type, create = true)
        val end = read(to, type, create = true)
        meals[id] = Meal(id, title, recipe, recipe?.let { RECIPE_NAME }, servings, "", start, end, type)
        return id
    }

    @Synchronized
    fun partnerEdit(id: Long, change: Meal.() -> Unit) {
        meals.getValue(id).change()
    }

    @Synchronized
    fun partnerDelete(id: Long) {
        meals.remove(id)
    }

    @Synchronized
    fun meal(id: Long): Meal? = meals[id]?.copy()

    @Synchronized
    fun all(): List<Meal> = meals.values.map { it.copy() }

    @Synchronized
    fun mealType(name: String): MealType? = types.values.firstOrNull { it.name == name }

    fun requests(method: String, pathPrefix: String): List<Pair<String, String>> =
        calls.filter { (call, _) -> call.startsWith("$method $pathPrefix") }

    fun writes(): List<Pair<String, String>> = calls.filter { (call, _) -> !call.startsWith("GET ") }

    // ------------------------------------------------------------------------------ answers

    /**
     * Phase 2 (koppling lane): what the server does for `addshopping: true` (FACTS "Fas 2": it puts
     * the recipe's ingredients on the list, linked to the meal); a test wires it to a [FakeShoppingTandoor].
     */
    @Volatile
    var onAddShopping: ((Meal) -> Unit)? = null

    /** Answers a meal plan request, null for any other path (so another fake may answer it). */
    @Synchronized
    fun answer(request: RecordedRequest): MockResponse? {
        val method = request.method
        val path = request.url.encodedPath
        val text = request.body?.utf8().orEmpty()
        val one = ONE.find(path)?.groupValues?.get(1)?.toLong()
        val response = when {
            path == LIST && method == "GET" -> list(request.url.queryParameter("from_date"), request.url.queryParameter("to_date"))
            path == LIST && method == "POST" -> create(parse(text))
            one != null -> oneMeal(method, one, parse(text))
            path == TYPES && method == "GET" -> page(types.values.map(::typeJson))
            path == TYPES && method == "POST" -> createType(parse(text))
            else -> return null
        }
        val query = request.url.encodedQuery?.let { "?$it" }.orEmpty()
        calls += "$method $path$query" to text
        if (response.code == NOT_FOUND) notFound += "$method $path"
        return response
    }

    private fun list(fromDate: String?, toDate: String?): MockResponse {
        val from = fromDate?.let(LocalDate::parse) ?: LocalDate.MIN
        val to = toDate?.let(LocalDate::parse) ?: LocalDate.MAX
        return page(meals.values.filter { it.toDay in from..to }.map(::mealJson))
    }

    private fun create(body: JsonObject): MockResponse {
        val type = mealTypeOf(body) ?: return json(BAD_REQUEST, Json.parseToJsonElement("""{"meal_type":["This field is required."]}"""))
        val fromText = text(body, "from_date")
            ?: return json(BAD_REQUEST, Json.parseToJsonElement("""{"from_date":["This field is required."]}"""))
        val from = read(fromText, type, create = true)
        val to = text(body, "to_date")?.let { read(it, type, create = true) } ?: from
        val recipe = (body["recipe"] as? JsonObject)
        val meal = Meal(
            id = nextMealId++,
            title = text(body, "title").orEmpty(),
            recipe = recipe?.let { (it["id"] as? JsonPrimitive)?.longOrNull },
            recipeName = recipe?.let { (it["name"] as? JsonPrimitive)?.contentOrNull },
            servings = (body["servings"] as? JsonPrimitive)?.doubleOrNull ?: 1.0,
            note = text(body, "note").orEmpty(),
            from = from,
            to = to,
            mealType = type,
            shopping = (body["addshopping"] as? JsonPrimitive)?.contentOrNull == "true",
        )
        meals[meal.id] = meal
        if (meal.shopping) onAddShopping?.invoke(meal)
        return json(CREATED, mealJson(meal))
    }

    private fun oneMeal(method: String, id: Long, body: JsonObject): MockResponse {
        val meal = meals[id] ?: return json(NOT_FOUND, Json.parseToJsonElement("""{"detail":"No MealPlan matches the given query."}"""))
        return when (method) {
            "GET" -> json(OK, mealJson(meal))

            "DELETE" -> {
                meals.remove(id)
                MockResponse.Builder().code(NO_CONTENT).build()
            }

            "PATCH" -> {
                text(body, "title")?.let { meal.title = it }
                text(body, "note")?.let { meal.note = it }
                (body["servings"] as? JsonPrimitive)?.doubleOrNull?.let { meal.servings = it }
                mealTypeOf(body)?.let { meal.mealType = it }
                if (body.containsKey("recipe")) {
                    val recipe = body["recipe"] as? JsonObject
                    meal.recipe = recipe?.let { (it["id"] as? JsonPrimitive)?.longOrNull }
                    meal.recipeName = recipe?.let { (it["name"] as? JsonPrimitive)?.contentOrNull }
                }
                text(body, "from_date")?.let { meal.from = read(it, meal.mealType, create = false) }
                text(body, "to_date")?.let { meal.to = read(it, meal.mealType, create = false) }
                json(OK, mealJson(meal))
            }

            else -> json(METHOD_NOT_ALLOWED, null)
        }
    }

    private fun createType(body: JsonObject): MockResponse {
        val name = text(body, "name") ?: return json(BAD_REQUEST, null)
        val type = MealType(
            id = nextTypeId++,
            name = name,
            order = (body["order"] as? JsonPrimitive)?.longOrNull?.toInt() ?: 0,
            time = text(body, "time")?.let(LocalTime::parse),
        )
        types[type.id] = type
        return json(CREATED, typeJson(type))
    }

    /** How Django reads a date-time: with an offset as that instant, without one in the server's zone. */
    private fun read(value: String, type: Long, create: Boolean): ZonedDateTime {
        val text = value.trim()
        if (text.length > DATE_LENGTH && OFFSET.containsMatchIn(text)) return OffsetDateTime.parse(text).atZoneSameInstant(ZONE)
        val local = if (text.length == DATE_LENGTH) LocalDate.parse(text).atStartOfDay() else LocalDateTime.parse(text)
        val filled = if (create && local.toLocalTime() == LocalTime.MIDNIGHT) {
            local.toLocalDate().atTime(types[type]?.time ?: LocalTime.NOON)
        } else {
            local
        }
        return filled.atZone(ZONE)
    }

    private fun mealTypeOf(body: JsonObject): Long? = ((body["meal_type"] as? JsonObject)?.get("id") as? JsonPrimitive)?.longOrNull

    private fun mealJson(meal: Meal): JsonObject {
        val type = types[meal.mealType]
        val recipe: JsonElement = meal.recipe?.let { id ->
            val template = mealTemplate.getValue("recipe").jsonObject
            JsonObject(template + mapOf("id" to JsonPrimitive(id), "name" to JsonPrimitive(meal.recipeName.orEmpty())))
        } ?: JsonNull
        val fields = mutableMapOf<String, JsonElement>(
            "id" to JsonPrimitive(meal.id),
            "title" to JsonPrimitive(meal.title),
            "recipe" to recipe,
            "servings" to JsonPrimitive(meal.servings),
            "note" to JsonPrimitive(meal.note),
            "note_markdown" to JsonPrimitive(meal.note),
            "from_date" to JsonPrimitive(FORMAT.format(meal.from)),
            "to_date" to JsonPrimitive(FORMAT.format(meal.to)),
            "meal_type" to (type?.let(::typeJson) ?: JsonNull),
            "meal_type_name" to JsonPrimitive(type?.name.orEmpty()),
            "shopping" to JsonPrimitive(meal.shopping),
        )
        val template = mealTemplate - "recipe_name"
        // `recipe_name` is missing entirely without a recipe (FACTS "Fas 1 g").
        meal.recipe?.let { fields["recipe_name"] = JsonPrimitive(meal.recipeName.orEmpty()) }
        return JsonObject(template + fields)
    }

    private fun typeJson(type: MealType): JsonObject = JsonObject(
        typeTemplate + mapOf(
            "id" to JsonPrimitive(type.id),
            "name" to JsonPrimitive(type.name),
            "order" to JsonPrimitive(type.order),
            "time" to (type.time?.let { JsonPrimitive(it.toString() + if (it.second == 0) ":00" else "") } ?: JsonNull),
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

    private fun text(body: JsonObject, field: String): String? = (body[field] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

    private fun parse(text: String): JsonObject = if (text.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(text).jsonObject

    private fun body(name: String): JsonObject = Json.parseToJsonElement(TandoorFixtures.exchange(name, version).body!!).jsonObject

    private fun json(code: Int, body: JsonElement?): MockResponse = MockResponse.Builder()
        .code(code)
        .addHeader("Content-Type", if (body == null) "text/html" else "application/json")
        .body(body?.toString() ?: "<html>Server Error</html>")
        .build()

    companion object {
        const val DINNER = 1L
        const val BREAKFAST = 3L
        const val RECIPE = 14L
        const val RECIPE_NAME = "Potatisgratäng"

        val ZONE: ZoneId = ZoneId.of("Europe/Stockholm")
        private val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")
        private const val LIST = "/api/meal-plan/"
        private const val TYPES = "/api/meal-type/"
        private val ONE = Regex("^/api/meal-plan/(\\d+)/$")
        private val OFFSET = Regex("(Z|[+-]\\d{2}:\\d{2})$")
        private const val DATE_LENGTH = 10
        private const val OK = 200
        private const val CREATED = 201
        private const val NO_CONTENT = 204
        private const val BAD_REQUEST = 400
        private const val NOT_FOUND = 404
        private const val METHOD_NOT_ALLOWED = 405
    }
}
