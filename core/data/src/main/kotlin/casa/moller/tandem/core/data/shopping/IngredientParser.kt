package casa.moller.tandem.core.data.shopping

import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.shopping.ParsedIngredient

/**
 * Free text → amount, unit, food (#11, plan 6.2 "Lägg till manuellt"): Tandoor's
 * `POST /api/ingredient-parser/post/` when online (`{ingredients: [...]}` parses many lines in one
 * call), a simple local parser offline (a number, a known unit, the rest; a comma starts the
 * note). Never sends anything in demo mode.
 *
 * Tandoor's parser CREATES the foods (and units) it does not know (FACTS "Fas 2": "ca 200 g smör"
 * created the food "ca smör"), so call it only for text the user is about to add, never while
 * typing; suggestions use the stored foods.
 */
interface IngredientParser {
    /**
     * Parses [lines] (already split at line breaks and commas between items, empty ones dropped),
     * in order. Falls back to the local parser offline or on a server error, so it only fails for
     * blank input (`BadRequest`).
     */
    suspend fun parse(lines: List<String>): Outcome<List<ParsedIngredient>>

    /** The local parser alone (pure, no network): for previews while typing. */
    fun parseLocally(line: String): ParsedIngredient?
}
