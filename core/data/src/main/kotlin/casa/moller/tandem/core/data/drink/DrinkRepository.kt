package casa.moller.tandem.core.data.drink

import casa.moller.tandem.core.model.drink.Drink
import casa.moller.tandem.core.model.drink.DrinkDraft
import casa.moller.tandem.core.model.drink.Pairing
import casa.moller.tandem.core.model.drink.RecipePairings
import casa.moller.tandem.core.model.error.Outcome
import kotlinx.coroutines.flow.Flow

/**
 * Drinks and "Passar med" (#43, B20, plan §8.8). A drink is a recipe with the keyword `Dryck`; a
 * pairing is a keyword under `Passar med` that the drink and the dishes carry, linked by the
 * keyword's id. Pairing changes PATCH the recipe's keywords like "Vill laga" (outbox
 * `RECIPE_KEYWORDS` with `RecipeKeywordFields`; the recipe is read fresh because a PATCH replaces
 * the whole list, FACTS "Fas S c") and work for every member of the household; guests see them
 * but get `DataProblem.READ_ONLY`. Implemented by the drycker lane in :core:sync.
 */
interface DrinkRepository {
    /** Every drink, by name; [type] narrows to one drink type keyword name ("Vin"). */
    fun observeDrinks(type: String? = null): Flow<List<Drink>>

    /** The drink type keywords in use (and the suggested ones), by name. */
    fun observeDrinkTypes(): Flow<List<String>>

    /** Every pairing keyword under `Passar med`, by name. */
    fun observePairings(): Flow<List<Pairing>>

    /** The pairings of a recipe from both sides: a dish's drinks, or the dishes of a drink. */
    fun observeRecipePairings(recipeId: Long): Flow<RecipePairings>

    /** Whether the recipe is a drink (it has the `Dryck` keyword). */
    fun observeIsDrink(recipeId: Long): Flow<Boolean>

    /** Adds the pairing [keywordId] to the recipe (dish or drink). */
    suspend fun addPairing(recipeId: Long, keywordId: Long): Outcome<Unit>

    /** Creates the pairing keyword [name] under `Passar med` (when missing) and adds it to the recipe. */
    suspend fun addNewPairing(recipeId: Long, name: String): Outcome<Pairing>

    suspend fun removePairing(recipeId: Long, keywordId: Long): Outcome<Unit>

    /** Creates a drink (offline: queued; the id is negative until the server answered). */
    suspend fun createDrink(draft: DrinkDraft): Outcome<Drink>

    suspend fun refresh(): Outcome<Unit>
}
