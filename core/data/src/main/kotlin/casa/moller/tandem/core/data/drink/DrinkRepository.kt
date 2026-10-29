package casa.moller.tandem.core.data.drink

import casa.moller.tandem.core.model.drink.Drink
import casa.moller.tandem.core.model.drink.DrinkDraft
import casa.moller.tandem.core.model.drink.Pairing
import casa.moller.tandem.core.model.drink.RecipePairings
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
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

    /**
     * Creates the pairing keyword [name] under `Passar med` (when missing) and adds it to the recipe
     * (a pairing with a kind of drink, "Kraftigt rött"). A name another keyword already has (a dish
     * keyword "Vin") is refused with [DrinkProblem.NAME_TAKEN]: Tandoor would reuse that keyword.
     */
    suspend fun addNewPairing(recipeId: Long, name: String): Outcome<Pairing>

    /**
     * Fas S (drycker lane): pairs dish [recipeId] with drink [drinkId] from either side: the drink's
     * own pairing keyword (`Passar med › <drink name>`, created on the drink first when it has none)
     * is added to the dish. Works for a drink created offline (negative id) too.
     */
    suspend fun pairWithDrink(recipeId: Long, drinkId: Long): Outcome<Pairing>

    suspend fun removePairing(recipeId: Long, keywordId: Long): Outcome<Unit>

    /** Creates a drink (offline: queued; the id is negative until the server answered). */
    suspend fun createDrink(draft: DrinkDraft): Outcome<Drink>

    suspend fun refresh(): Outcome<Unit>
}

/** Fas S (drycker lane): why a drink write was refused before anything was stored (`TandemError.Unknown(detail)`). */
enum class DrinkProblem(val detail: String) {
    /** The name is empty. */
    NAME_EMPTY("tandem.drink.name-empty"),

    /** Another keyword (not a pairing) already has the name; Tandoor would reuse it. */
    NAME_TAKEN("tandem.drink.name-taken"),

    /** The recipe or drink is not known on this device. */
    UNKNOWN_RECIPE("tandem.drink.unknown-recipe"),
    ;

    fun toError(): TandemError = TandemError.Unknown(detail)

    companion object {
        /** The [DrinkProblem] this error stands for, or null. */
        fun of(error: TandemError): DrinkProblem? = entries.firstOrNull { (error as? TandemError.Unknown)?.detail == it.detail }
    }
}
