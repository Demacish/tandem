package casa.moller.tandem.smoke

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
 * Phase 2 (integration lane): a stateful Tandoor 2.6.13 meal plan in Europe/Stockholm for the
 * release smoke test, a port of core:sync's test fake (`FakeMealPlanTandoor`). It answers in the
 * recorded shapes (`fas2/meal-plan-create-addshopping`, `fas1/meal-type-list`) with the facts of
 * FACTS "Fas 1 g" and "Fas 2 f":
 *
 * - a value without offset is read in the server's zone; on create a naive midnight gets the meal
 *   type's time (12:00 without one); dates come back with the server's offset;
 * - `?from_date&to_date` filters on the server-local date of `to_date`, both inclusive;
 * - `addshopping: true` puts the recipe on the list ([onAddShopping]); `shopping` says whether a
 *   list recipe is linked to the meal ([onList]).
 *
 * "The web" creates meals directly ([web]).
 */
class FakeMealPlan(private val template: (String) -> JsonObject) {
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
        val addShopping: Boolean = false,
    ) {
        val fromDay: LocalDate get() = from.toLocalDate()
        val toDay: LocalDate get() = to.toLocalDate()
    }

    data class MealType(val id: Long, val name: String, val order: Int, val time: LocalTime?)

    private val meals = LinkedHashMap<Long, Meal>()
    private val types = LinkedHashMap<Long, MealType>()
    private var nextMealId = 100L
    private var nextTypeId = 10L

    /** The JSON body of every `POST /api/meal-plan/`, in order. */
    val creates: MutableList<String> = CopyOnWriteArrayList()

    /** What the server does for `addshopping: true` (FACTS "Fas 2": the recipe's ingredients go on the list, linked to the meal). */
    @Volatile
    var onAddShopping: ((Meal) -> Unit)? = null

    /** Whether a meal is on the shopping list (Tandoor's `shopping` field). */
    @Volatile
    var onList: (Long) -> Boolean = { false }

    private val mealTemplate by lazy { template("fas2/meal-plan-create-addshopping") }
    private val typeTemplate by lazy { template("fas2/meal-type-create") }

    init {
        // fas1/meal-type-list: Middag (18:00) and Lunch (no time).
        types[DINNER] = MealType(DINNER, "Middag", 0, LocalTime.of(18, 0))
        types[LUNCH] = MealType(LUNCH, "Lunch", 1, null)
    }

    /** A meal created on the web: [from] as the browser sent it (offset, `Z` or naive server-local). */
    @Synchronized
    fun web(from: String, recipe: Long, recipeName: String, type: Long = DINNER, servings: Double = 4.0): Long {
        val id = nextMealId++
        val start = read(from, type, create = true)
        meals[id] = Meal(id, "", recipe, recipeName, servings, "", start, start, type)
        return id
    }

    @Synchronized
    fun all(): List<Meal> = meals.values.map { it.copy() }

    /** Answers a meal plan or meal type request, null for any other path. */
    @Synchronized
    fun answer(request: RecordedRequest): MockResponse? {
        val method = request.method
        val path = request.url.encodedPath
        val text = request.body?.utf8().orEmpty()
        val one = ONE.find(path)?.groupValues?.get(1)?.toLong()
        return when {
            path == LIST && method == "GET" -> list(request.url.queryParameter("from_date"), request.url.queryParameter("to_date"))

            path == LIST && method == "POST" -> {
                creates += text
                create(parse(text))
            }

            one != null -> oneMeal(method, one, parse(text))

            path == TYPES && method == "GET" -> page(types.values.map(::typeJson))

            path == TYPES && method == "POST" -> createType(parse(text))

            else -> null
        }
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
        val recipe = body["recipe"] as? JsonObject
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
            addShopping = (body["addshopping"] as? JsonPrimitive)?.contentOrNull == "true",
        )
        meals[meal.id] = meal
        if (meal.addShopping && meal.recipe != null) onAddShopping?.invoke(meal)
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
            "shopping" to JsonPrimitive(onList(meal.id)),
        )
        // `recipe_name` is missing entirely without a recipe (FACTS "Fas 1 g").
        meal.recipe?.let { fields["recipe_name"] = JsonPrimitive(meal.recipeName.orEmpty()) }
        return JsonObject(mealTemplate - "recipe_name" + fields)
    }

    private fun typeJson(type: MealType): JsonObject = JsonObject(
        typeTemplate + mapOf(
            "id" to JsonPrimitive(type.id),
            "name" to JsonPrimitive(type.name),
            "order" to JsonPrimitive(type.order),
            "time" to (type.time?.let { JsonPrimitive(TIME.format(it)) } ?: JsonNull),
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

    private fun json(code: Int, body: JsonElement?): MockResponse = MockResponse.Builder()
        .code(code)
        .addHeader("Content-Type", if (body == null) "text/html" else "application/json")
        .body(body?.toString() ?: "<html>Server Error</html>")
        .build()

    companion object {
        /** fas1/meal-type-list. */
        const val DINNER = 1L
        const val LUNCH = 2L

        /** The recorded server's zone (FACTS "Fas 1 g": answers carry its offset). */
        val ZONE: ZoneId = ZoneId.of("Europe/Stockholm")
        private val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")
        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
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
