package casa.moller.tandem.core.model.shopping

import kotlin.time.Instant

// Phase 2 (grund lane): the shopping list as the screens see it (plan 6.2 "Handla", features
// #9-#14). Ids: an object created offline has a NEGATIVE id until the server knows it (plan 5.7);
// a repository resolves an old negative id to the server id once it has one, so a screen may keep
// using the id it got.

/**
 * A food on the list (Tandoor `FoodShopping`).
 *
 * @property id the server's food id; null for a food the server has not created yet (an offline
 *   free-text entry: Tandoor creates or reuses the food by name when the entry is sent).
 * @property categoryId the aisle (`supermarket_category`), null = "Övrigt".
 * @property onHand Tandoor's `food_onhand` ("har hemma"; per household).
 */
data class ShoppingFood(
    val id: Long?,
    val name: String,
    val pluralName: String? = null,
    val categoryId: Long? = null,
    val onHand: Boolean = false,
)

/** A unit on the list (Tandoor `Unit`); [id] null for a unit the server has not seen yet. */
data class ShoppingUnit(val id: Long?, val name: String, val pluralName: String? = null)

/**
 * One row of the shopping list (Tandoor `ShoppingListEntry`).
 *
 * @property id local id: the server id, or negative for an entry created offline and not sent yet.
 * @property amount the amount; 0 = no amount (Tandoor's parser gives 0 for "mjölk").
 * @property completedAt when it was checked (set by the server), null while unchecked.
 * @property delayUntil "köp senare" (#14): the entry is delayed while this lies in the future.
 * @property addedBy display name of the household member who added it, if known.
 * @property addedById that member's user id.
 * @property recipe the recipe or meal it came from (the small label), null for a manual entry.
 * @property listIds the named Tandoor shopping lists it is on ("listval", #10).
 * @property pending true while a change of this entry waits in the outbox ("3 ändringar väntar").
 */
data class ShoppingEntry(
    val id: Long,
    val food: ShoppingFood,
    val unit: ShoppingUnit? = null,
    val amount: Double = 0.0,
    val order: Int = 0,
    val checked: Boolean = false,
    val completedAt: Instant? = null,
    val delayUntil: Instant? = null,
    val addedBy: String? = null,
    val addedById: Long? = null,
    val recipe: ShoppingRecipeLabel? = null,
    val ingredientId: Long? = null,
    val listIds: Set<Long> = emptySet(),
    val pending: Boolean = false,
) {
    /** Not on the server yet (created offline, CREATE still in the outbox). */
    val isLocalOnly: Boolean
        get() = id < 0

    /** "Köp senare": delayed until a time after [now]. Tandoor's undo value (1970-01-01) is in the past. */
    fun isDelayed(now: Instant): Boolean = delayUntil?.let { it > now } == true
}

/**
 * Where an entry came from (Tandoor `list_recipe_data`): a recipe put on the list, or a meal.
 *
 * @property listRecipeId the `ShoppingListRecipe` (local id, negative while offline).
 * @property mealPlanId the meal, if the entries are for a planned meal.
 */
data class ShoppingRecipeLabel(
    val listRecipeId: Long,
    val recipeId: Long? = null,
    val recipeName: String? = null,
    val mealPlanId: Long? = null,
    val servings: Double = 1.0,
)

/** A named Tandoor shopping list (`/api/shopping-list/`), e.g. "Veckohandling". */
data class ShoppingList(val id: Long, val name: String, val description: String = "", val color: String? = null)

/**
 * What the list screen shows (plan 6.2): all entries, or those of one named list; sorted by one
 * store's aisle order.
 *
 * @property listId only entries on this named list; null = every entry.
 * @property supermarketId sort by this store's aisle order; null = by category name.
 */
data class ShoppingFilter(val listId: Long? = null, val supermarketId: Long? = null)

/**
 * A new entry from the "Lägg till vara…" field or a suggestion (#11).
 *
 * @property foodName the food as typed or parsed ("grädde"); Tandoor creates or reuses it by name.
 * @property foodId a known food (a suggestion), when there is one.
 * @property listIds named lists to put it on (the selected list).
 */
data class ShoppingEntryDraft(
    val foodName: String,
    val amount: Double = 0.0,
    val unitName: String? = null,
    val unitId: Long? = null,
    val foodId: Long? = null,
    val note: String = "",
    val listIds: Set<Long> = emptySet(),
)

/**
 * "Lägg på listan" from a recipe (#12): the chosen ingredients at [servings].
 *
 * @property ingredientIds the recipe's ingredient ids to add; things on hand are pre-deselected
 *   by the sheet (plan 6.2), so an empty set adds nothing.
 * @property mealPlanId links the entries to a meal ("Handla för veckan", #16), so the meal shows
 *   "redan på listan" and nothing is added twice.
 */
data class RecipeShoppingRequest(
    val recipeId: Long,
    val servings: Double,
    val ingredientIds: Set<Long>,
    val mealPlanId: Long? = null,
    val listIds: Set<Long> = emptySet(),
)

/**
 * Something another household member changed, noticed by polling (plan 6.2 "Synk": "Anna lade
 * till Mjölk"; who checked something is not in the API, so a check has no name).
 */
sealed interface ShoppingChange {
    val foodName: String

    data class Added(override val foodName: String, val by: String?) : ShoppingChange

    data class Checked(override val foodName: String) : ShoppingChange

    data class Removed(override val foodName: String) : ShoppingChange
}

/** An archived entry (plan 3.5 window rule, 8.2 "Lokal historik"), for "brukar köpa" (#29, Phase 4). */
data class ShoppingHistoryItem(
    val entryId: Long,
    val foodName: String,
    val foodId: Long? = null,
    val amount: Double = 0.0,
    val unitName: String? = null,
    val completedAt: Instant? = null,
)

/**
 * Free text turned into a list entry ("2 dl grädde", #11): Tandoor's ingredient parser online,
 * a simple local parser offline (number + known unit + the rest).
 *
 * @property amount 0 when the text has no amount ("mjölk").
 * @property foodName what is left after amount and unit ("grädde"); never blank for a valid line.
 * @property note text after a comma ("rumsvarmt"), empty when none.
 * @property fromServer true when Tandoor's parser produced it (it may also create the food:
 *   FACTS "Fas 2"), false for the local fallback.
 */
data class ParsedIngredient(
    val originalText: String,
    val amount: Double,
    val unitName: String?,
    val foodName: String,
    val note: String = "",
    val unitId: Long? = null,
    val foodId: Long? = null,
    val fromServer: Boolean = false,
)
