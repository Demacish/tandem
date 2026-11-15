package casa.moller.tandem.core.data.assistant

import casa.moller.tandem.core.model.assistant.AssistantAddition
import casa.moller.tandem.core.model.assistant.AssistantMealDay
import casa.moller.tandem.core.model.assistant.AssistantRecipe
import casa.moller.tandem.core.model.assistant.AssistantResult
import casa.moller.tandem.core.model.assistant.AssistantShoppingItem
import kotlinx.datetime.LocalDate

/**
 * Fas 5 (funktioner lane, plan §8.7 "App Functions"): what an assistant may do in Tandem, behind
 * the App Functions in :app (docs/BYGGE.md "App Functions"). Implemented in core:sync on the same
 * repositories the screens use, so everything works offline and in demo mode (the demo data, never
 * the network) and every write goes through the outbox. Nothing here deletes anything.
 *
 * Every function first checks, in this order, and refuses with the matching
 * [casa.moller.tandem.core.model.assistant.AssistantRefusal]:
 * 1. "Låt assistenten använda Tandem" is on (`TURNED_OFF`);
 * 2. the app lock lets content through, or "Tillåt assistenten när appen är låst" is on (`LOCKED`);
 * 3. someone is logged in or demo mode is on (`NOT_LOGGED_IN`);
 * 4. writes only: the user is not a guest (`READ_ONLY`) and the server is not in safe mode (`SAFE_MODE`).
 *
 * - **Threading:** main-safe.
 * - **Errors:** never throws for refusals or local failures (only cancellation propagates).
 */
interface AssistantActions {
    /**
     * Adds [text] to the shopping list exactly as typing it into Handla's "Lägg till" would
     * (core:data `ShoppingInput`: "mjölk, bröd, 6 ägg" is three items; Tandoor's parser online,
     * the local parser offline), through the outbox. [amount] and [unit], when given, replace what
     * the parser found and are only allowed for a single item. [list] names a Tandoor shopping list
     * (any case); null adds to no named list.
     */
    suspend fun addToShoppingList(
        text: String,
        amount: Double? = null,
        unit: String? = null,
        list: String? = null,
    ): AssistantResult<AssistantAddition>

    /** The items still to buy (not checked), on the named [list] or on every list when null, at most [MAX_ITEMS]. */
    suspend fun shoppingList(list: String? = null): AssistantResult<List<AssistantShoppingItem>>

    /** The meals planned for [date] (today on the phone when null). */
    suspend fun mealPlan(date: LocalDate? = null): AssistantResult<AssistantMealDay>

    /**
     * The stored recipes whose name or text matches [query] (the recipe list's offline search;
     * never the network), at most [limit] (1–[MAX_RECIPES]; 0 or less gives [DEFAULT_RECIPES]).
     */
    suspend fun searchRecipes(query: String, limit: Int = DEFAULT_RECIPES): AssistantResult<List<AssistantRecipe>>

    /** The stored recipe [recipeId], for opening it in the app (the recipe or cook mode). */
    suspend fun recipe(recipeId: Long): AssistantResult<AssistantRecipe>

    companion object {
        const val MAX_ITEMS: Int = 200
        const val DEFAULT_RECIPES: Int = 10
        const val MAX_RECIPES: Int = 25
    }
}
