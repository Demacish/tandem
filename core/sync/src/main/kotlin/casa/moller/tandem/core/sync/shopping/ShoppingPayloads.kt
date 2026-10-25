package casa.moller.tandem.core.sync.shopping

import casa.moller.tandem.core.database.entity.OutboxEntities
import casa.moller.tandem.core.database.outbox.OutboxRefs
import casa.moller.tandem.core.network.json.TandoorDates
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The outbox payloads of the shopping list (docs/BYGGE.md "Fas 2 – kontrakt", table "Outbox";
 * FACTS "Fas 2" b-d): only the fields that change, temporary (negative) ids listed in `refs`.
 */
internal object ShoppingPayloads {
    /** Tandoor's "not delayed" (`delay_until`, FACTS "Fas 2 b"). */
    const val NOT_DELAYED: String = "1970-01-01T00:00:00Z"

    /** `batch_key` of the bulk checks: one `POST .../bulk/` per value (plan 3.5). */
    fun checkBatchKey(checked: Boolean): String = "${OutboxEntities.SHOPPING_ENTRY}:checked=$checked"

    /**
     * `POST /api/shopping-list-entry/`: `amount` is required (400 without), the food by name (and
     * id when known); Tandoor creates or reuses food and unit by name.
     */
    @Suppress("LongParameterList") // One field per argument of Tandoor's create body.
    fun createEntry(
        foodName: String,
        foodId: Long?,
        unitName: String?,
        unitId: Long?,
        amount: Double,
        listIds: Collection<Long>,
        listRecipeId: Long? = null,
        ingredientId: Long? = null,
    ): String = buildJsonObject {
        put(
            "food",
            buildJsonObject {
                put("name", foodName)
                foodId?.let { put("id", it) }
            },
        )
        val unit: JsonElement = if (unitName == null) {
            JsonNull
        } else {
            buildJsonObject {
                put("name", unitName)
                unitId?.let { put("id", it) }
            }
        }
        put("unit", unit)
        put("amount", amount)
        put("shopping_lists", listArray(listIds))
        listRecipeId?.let { put("list_recipe", it) }
        ingredientId?.let { put("ingredient", it) }
    }.toString()

    /** `refs` of an entry CREATE that points at a list recipe created offline. */
    fun createEntryRefs(listRecipeId: Long?): String? =
        if (listRecipeId != null && listRecipeId < 0) {
            OutboxRefs.encode(mapOf("list_recipe" to OutboxEntities.SHOPPING_LIST_RECIPE))
        } else {
            null
        }

    /** One BULK_CHECK row: `{ids: [id], checked}`; `refs` resolve a temporary id. */
    fun check(id: Long, checked: Boolean): String = buildJsonObject {
        put("ids", JsonArray(listOf(JsonPrimitive(id))))
        put("checked", checked)
    }.toString()

    fun checkSnapshot(checked: Boolean): String = buildJsonObject { put("checked", checked) }.toString()

    fun checkRefs(id: Long): String? = if (id < 0) OutboxRefs.encode(mapOf("ids" to OutboxEntities.SHOPPING_ENTRY)) else null

    /** "Köp senare" until [untilMillis], or undone (null → [NOT_DELAYED]). */
    fun delay(untilMillis: Long?): String = buildJsonObject {
        put("delay_until", untilMillis?.let { TandoorDates.format(java.time.Instant.ofEpochMilli(it)) } ?: NOT_DELAYED)
    }.toString()

    /** Moving between named lists: a PATCH of `shopping_lists` (bumps `updated_at`; bulk does not). */
    fun lists(listIds: Set<Long>): String = buildJsonObject { put("shopping_lists", listArray(listIds)) }.toString()

    /** Amount, unit or food of an entry (null fields are left out). */
    fun edit(amount: Double?, unitName: String?, foodName: String?): String = buildJsonObject {
        amount?.let { put("amount", it) }
        unitName?.let { name -> put("unit", if (name.isBlank()) JsonNull else buildJsonObject { put("name", name.trim()) }) }
        foodName?.let { name -> put("food", buildJsonObject { put("name", name.trim()) }) }
    }.toString()

    /** `POST /api/shopping-list-recipe/` `{recipe, servings, name[, mealplan]}`. */
    fun createListRecipe(recipeId: Long, servings: Double, name: String, mealPlanId: Long?): String = buildJsonObject {
        put("recipe", recipeId)
        put("servings", servings)
        put("name", name)
        mealPlanId?.let { put("mealplan", it) }
    }.toString()

    fun createListRecipeRefs(mealPlanId: Long?): String? =
        if (mealPlanId != null && mealPlanId < 0) OutboxRefs.encode(mapOf("mealplan" to OutboxEntities.MEAL_PLAN)) else null

    /** A food's aisle (`{supermarket_category: {id, name}}` or null). */
    fun foodCategory(categoryId: Long?, categoryName: String?): String = buildJsonObject {
        val category: JsonElement = if (categoryId == null) {
            JsonNull
        } else {
            buildJsonObject {
                put("id", categoryId)
                put("name", categoryName.orEmpty())
            }
        }
        put("supermarket_category", category)
    }.toString()

    fun foodOnHand(onHand: Boolean): String = buildJsonObject { put("food_onhand", onHand) }.toString()

    fun relationOrder(order: Int): String = buildJsonObject { put("order", order) }.toString()

    private fun listArray(listIds: Collection<Long>): JsonArray =
        JsonArray(listIds.distinct().sorted().map { id -> JsonObject(mapOf("id" to JsonPrimitive(id))) })
}
