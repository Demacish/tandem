package casa.moller.tandem.core.model.drink

// Fas S (grund lane): drinks and "Passar med" (#43, B20, plan §8.8 "Drycker"). A drink is an
// ordinary Tandoor recipe with the keyword [DrinkKeywords.DRINK]; the link to dishes is a keyword
// under [DrinkKeywords.PAIRS_WITH] that both the drink and the dishes carry. The link hangs on the
// keyword's id, so a rename does not break it.

/**
 * The keyword names the app looks for and creates (server data, shown as they are; a household
 * can rename them on the web, the app then follows the ids it stored).
 */
object DrinkKeywords {
    /** Every drink carries it. */
    const val DRINK: String = "Dryck"

    /** The parent of every pairing keyword. */
    const val PAIRS_WITH: String = "Passar med"

    /**
     * Suggested drink types. The drycker lane creates them as children of [DRINK] ("Dryck > Vin");
     * a root keyword with one of these names (created on the web) is read as a type too.
     */
    val TYPES: List<String> = listOf("Vin", "Öl", "Cider", "Alkoholfritt", "Drink")

    // Fas S (drycker lane): the server names stay Swedish whatever the UI language (docs/BYGGE.md
    // "Drycker"), so both phones of a household find the same keywords; a household that already
    // has an English root keyword for drinks or pairings keeps using it.

    /** Root keyword names that mean "drinks" (lower case). "Drink" is a TYPE (a cocktail), never the root. */
    val DRINK_ROOT_NAMES: Set<String> = setOf("dryck", "drycker", "beverage", "beverages")

    /** Root keyword names that mean "goes with" (lower case). */
    val PAIRS_WITH_ROOT_NAMES: Set<String> = setOf("passar med", "pairs with", "goes with")

    /** Whether [name] is a root keyword for drinks ("Dryck", or an English variant). */
    fun isDrinkRoot(name: String): Boolean = key(name) in DRINK_ROOT_NAMES

    /** Whether [name] is the parent of the pairing keywords ("Passar med", or an English variant). */
    fun isPairsWithRoot(name: String): Boolean = key(name) in PAIRS_WITH_ROOT_NAMES

    /** Whether [name] is one of the suggested [TYPES] (ignoring case). */
    fun isSuggestedType(name: String): Boolean = TYPES.any { it.equals(name.trim(), ignoreCase = true) }

    /** How keyword names are compared: Tandoor reuses a keyword whose name differs only in case (FACTS "Fas S c"). */
    fun key(name: String): String = name.trim().lowercase()
}

/** A drink in lists ([id] = recipe id; negative = created in the app and not on the server yet). */
data class Drink(
    val id: Long,
    val name: String,
    val imageUrl: String? = null,
    val types: List<String> = emptyList(),
    val rating: Double? = null,
    val note: String? = null,
    val pending: Boolean = false,
)

/**
 * A pairing keyword (`Passar med › <name>`).
 *
 * @property keywordId the keyword's id (negative while created offline).
 * @property isTypeOnly a pairing with a kind of drink, not a bottle ("Kraftigt rött"): no drink
 *   carries it, it is shown as text (plan §8.8).
 */
data class Pairing(val keywordId: Long, val name: String, val isTypeOnly: Boolean = false)

/** The pairings of one recipe from both directions: the drinks that go with a dish, or the dishes a drink goes with. */
data class RecipePairings(
    val recipeId: Long,
    val pairings: List<Pairing> = emptyList(),
    val drinks: List<Drink> = emptyList(),
    val dishIds: List<Long> = emptyList(),
)

/** "Skapa dryck": becomes `POST /api/recipe/` with the keywords and no steps (FACTS "Fas S f"). */
data class DrinkDraft(
    val name: String,
    val description: String = "",
    val servings: Int = 1,
    val types: List<String> = emptyList(),
    val pairingKeywordIds: List<Long> = emptyList(),
    val newPairingNames: List<String> = emptyList(),
    /** A `content://` URI of the chosen photo; the repository copies it before queueing the upload. */
    val imageUri: String? = null,
)
