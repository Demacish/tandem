package casa.moller.tandem.core.sync.mealplan

import casa.moller.tandem.core.database.entity.MealPlanEntity
import casa.moller.tandem.core.database.entity.OutboxEntities
import casa.moller.tandem.core.database.outbox.OutboxRefs
import casa.moller.tandem.core.domain.mealplan.MealPlanDates
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The outbox payloads of the meal plan (docs/BYGGE.md "Fas 2 – kontrakt", table "Outbox"; FACTS
 * "Fas 1 g", "Fas 2 f") and the field comparison before a PATCH (plan 3.5 "Konflikter: Matsedel").
 *
 * - **Dates** are sent as naive server-local date-times ([MealPlanDates.toServer]): Tandoor reads
 *   them in its own zone, so a meal stays on its day across daylight saving time. A new meal sends
 *   midnight (the meal type's time is filled in), a moved one keeps its time of day.
 * - **PATCH** carries only the changed fields; its `base_snapshot` holds the same fields as they
 *   were before (dates as `yyyy-MM-dd`), which the sender compares with the server's current meal.
 * - A **temporary meal type** (created offline, negative id) is listed in `refs` as `meal_type.id`.
 */
internal object MealPlanPayloads {
    const val TITLE = "title"
    const val RECIPE = "recipe"
    const val SERVINGS = "servings"
    const val NOTE = "note"
    const val FROM_DATE = "from_date"
    const val TO_DATE = "to_date"
    const val MEAL_TYPE = "meal_type"
    const val ADD_SHOPPING = "addshopping"

    /** `last_error` of a PATCH whose fields collided with someone else's change; it waits for "behåll min" / "ta deras". */
    const val CONFLICT = "tandem.mealplan.conflict"

    /** The fields compared before a PATCH, in the order a conflict is shown. */
    val COMPARED: List<String> = listOf(FROM_DATE, TO_DATE, MEAL_TYPE, RECIPE, TITLE, SERVINGS, NOTE)

    /** The two dates move together: a conflict on one holds back both. */
    val DATES: Set<String> = setOf(FROM_DATE, TO_DATE)

    private val json = Json { ignoreUnknownKeys = true }

    /** `POST /api/meal-plan/` for [meal]; `to_date` only for a meal of several days. */
    fun create(meal: MealPlanEntity, mealTypeName: String, recipeName: String?, addToShopping: Boolean): String = buildJsonObject {
        put(TITLE, meal.title)
        put(RECIPE, recipe(meal.recipeId, recipeName))
        put(SERVINGS, meal.servings)
        put(NOTE, meal.note)
        val from = LocalDate.parse(meal.fromDate)
        put(FROM_DATE, MealPlanDates.toServer(from))
        val to = LocalDate.parse(meal.toDate)
        if (to != from) put(TO_DATE, MealPlanDates.toServer(to))
        put(MEAL_TYPE, mealType(meal.mealTypeId, mealTypeName))
        if (addToShopping) put(ADD_SHOPPING, true)
    }.toString()

    /** `refs` of a meal plan row: its meal type when that is still temporary. */
    fun refs(mealTypeId: Long): String? =
        if (mealTypeId < 0) OutboxRefs.encode(mapOf("$MEAL_TYPE.id" to OutboxEntities.MEAL_TYPE)) else null

    /** `POST /api/meal-type/` (`time` as `HH:mm`, FACTS "Fas 2 f"). */
    fun createMealType(name: String, time: LocalTime?, color: String?, order: Int): String = buildJsonObject {
        put("name", name)
        time?.let { put("time", "${it.hour.toString().padStart(2, '0')}:${it.minute.toString().padStart(2, '0')}") }
        color?.let { put("color", it) }
        put("order", order)
    }.toString()

    /**
     * The PATCH of a change from [before] to [after] (both local rows): the changed fields, and the
     * same fields as they were (the conflict check's base). Null when nothing changed.
     */
    fun patch(before: MealPlanEntity, after: MealPlanEntity, mealTypeName: String, recipeName: String?): Pair<String, String>? {
        val payload = LinkedHashMap<String, JsonElement>()
        val base = LinkedHashMap<String, JsonElement>()
        if (after.fromDate != before.fromDate || after.toDate != before.toDate) {
            // Always both dates: Tandoor keeps to_date when only from_date changes (FACTS "Fas 2 f").
            val fromTime = MealPlanDates.serverTime(before.fromRaw)
            val toTime = MealPlanDates.serverTime(before.toRaw)
            payload[FROM_DATE] = JsonPrimitive(MealPlanDates.toServer(LocalDate.parse(after.fromDate), fromTime))
            payload[TO_DATE] = JsonPrimitive(MealPlanDates.toServer(LocalDate.parse(after.toDate), toTime))
            base[FROM_DATE] = JsonPrimitive(before.fromDate)
            base[TO_DATE] = JsonPrimitive(before.toDate)
        }
        if (after.mealTypeId != before.mealTypeId) {
            payload[MEAL_TYPE] = mealType(after.mealTypeId, mealTypeName)
            base[MEAL_TYPE] = buildJsonObject { put("id", before.mealTypeId) }
        }
        if (after.recipeId != before.recipeId) {
            payload[RECIPE] = recipe(after.recipeId, recipeName)
            base[RECIPE] = before.recipeId?.let { id -> buildJsonObject { put("id", id) } } ?: JsonNull
        }
        if (after.title != before.title) {
            payload[TITLE] = JsonPrimitive(after.title)
            base[TITLE] = JsonPrimitive(before.title)
        }
        if (after.servings != before.servings) {
            payload[SERVINGS] = JsonPrimitive(after.servings)
            base[SERVINGS] = JsonPrimitive(before.servings)
        }
        if (after.note != before.note) {
            payload[NOTE] = JsonPrimitive(after.note)
            base[NOTE] = JsonPrimitive(before.note)
        }
        if (payload.isEmpty()) return null
        return JsonObject(payload).toString() to JsonObject(base).toString()
    }

    /** What the sender does with a PATCH after reading the meal, see [compare]. */
    data class Comparison(
        /** The fields to send now (unchanged on the server since the change started, or no base to compare). */
        val send: JsonObject,
        /** The user's values of fields someone else changed meanwhile; they wait for a decision. */
        val conflicting: JsonObject,
        /** The server's current values of [conflicting], as the server sent them. */
        val theirs: JsonObject,
    )

    /**
     * The field comparison before a PATCH (plan 3.5): for each field of [payload], the server's
     * current value ([server], the meal as Tandoor answers it) is compared with the value the change
     * started from ([base]): unchanged → sent; already the user's value → left out; changed by
     * someone else → a conflict. Other fields are never sent, so an unrelated change on the web (a
     * note, the servings) is never overwritten. The two dates count as one field.
     */
    fun compare(payload: JsonObject, base: JsonObject?, server: JsonObject): Comparison {
        val send = LinkedHashMap<String, JsonElement>()
        val conflicting = LinkedHashMap<String, JsonElement>()
        for ((field, mine) in payload) {
            if (field == ADD_SHOPPING) continue
            val theirs = normalized(field, server[field])
            val started = base?.get(field)?.let { normalized(field, it) }
            when {
                theirs == normalized(field, mine) -> Unit
                base == null || !base.containsKey(field) || theirs == started -> send[field] = mine
                else -> conflicting[field] = mine
            }
        }
        if (conflicting.keys.any { it in DATES }) {
            DATES.forEach { field -> send.remove(field)?.let { conflicting[field] = it } }
        }
        val theirs = conflicting.keys.associateWith { server[it] ?: JsonNull }
        return Comparison(JsonObject(send), JsonObject(conflicting), JsonObject(theirs))
    }

    /** A field's value for comparing: the day of a date, the id of a recipe or meal type, the text or number. */
    fun normalized(field: String, value: JsonElement?): String = when (field) {
        FROM_DATE, TO_DATE -> MealPlanDates.serverDay((value as? JsonPrimitive)?.contentOrNull)?.toString().orEmpty()
        RECIPE, MEAL_TYPE -> ((value as? JsonObject)?.get("id") as? JsonPrimitive)?.longOrNull?.toString().orEmpty()
        SERVINGS -> (value as? JsonPrimitive)?.doubleOrNull?.toString().orEmpty()
        else -> (value as? JsonPrimitive)?.contentOrNull.orEmpty()
    }

    /** A field's value as the user reads it in the conflict dialog (a date, a name, a number or the text). */
    fun display(field: String, value: JsonElement?): String? = when (field) {
        FROM_DATE, TO_DATE -> normalized(field, value).ifEmpty { null }
        RECIPE, MEAL_TYPE -> ((value as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull
        else -> (value as? JsonPrimitive)?.contentOrNull
    }

    fun parse(text: String?): JsonObject? = text?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }

    private fun mealType(id: Long, name: String): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
    }

    private fun recipe(id: Long?, name: String?): JsonElement = if (id == null) {
        JsonNull
    } else {
        buildJsonObject {
            put("id", id)
            put("name", name.orEmpty())
        }
    }
}
