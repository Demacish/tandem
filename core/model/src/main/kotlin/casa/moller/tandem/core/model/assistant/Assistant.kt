package casa.moller.tandem.core.model.assistant

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

// Fas 5 (funktioner lane, plan §8.7 "App Functions"): what an assistant on the phone (an app with
// Android's EXECUTE_APP_FUNCTIONS permission, such as Gemini) may ask Tandem for, and why Tandem
// says no. The App Functions themselves live in :app (docs/BYGGE.md "App Functions").

/**
 * "Mer → Assistenten" (plan §8.7, §5.5). Both are off until the user turns them on: the functions
 * hand the shopping list, the meal plan and recipe names to another app (and possibly its cloud
 * model), which plan §5.5 ("Inga tredjepartsanrop") never does by itself.
 *
 * @property enabled "Låt assistenten använda Tandem": the functions answer at all.
 * @property allowWhenLocked "Tillåt assistenten när appen är låst": with Mer → Lås appen on, the
 *   functions also answer while the app is locked (otherwise they refuse with [AssistantRefusal.LOCKED]).
 */
data class AssistantSettings(val enabled: Boolean = false, val allowWhenLocked: Boolean = false)

/** Why the assistant got no answer. Each one becomes a plain-language error for the assistant. */
enum class AssistantRefusal {
    /** "Låt assistenten använda Tandem" is off. */
    TURNED_OFF,

    /** The app is locked (Mer → Lås appen) and "Tillåt assistenten när appen är låst" is off. */
    LOCKED,

    /** Nobody is logged in and demo mode is off, or the app's storage needs recovery. */
    NOT_LOGGED_IN,

    /** The user is a guest in the Tandoor space: reading only (#42). */
    READ_ONLY,

    /** Safe mode (an untested Tandoor version, plan 3.6): changes would wait unsent, so none are taken. */
    SAFE_MODE,

    /** The text to add was empty. */
    NOTHING_TO_ADD,

    /** An amount or unit was given for several items at once. */
    AMOUNT_FOR_SEVERAL_ITEMS,

    /** The amount was negative or not a number. */
    INVALID_AMOUNT,

    /** No shopping list with the given name (the detail names the lists there are). */
    LIST_NOT_FOUND,

    /** The search text was empty. */
    EMPTY_QUERY,

    /** No stored recipe with the given id. */
    RECIPE_NOT_FOUND,

    /** The phone's own storage failed. */
    NOT_SAVED,
}

/**
 * What an assistant request gave: a value, or a [AssistantRefusal] with an optional [Refused.detail]
 * (for example the names of the lists there are).
 */
sealed interface AssistantResult<out T> {
    data class Done<out T>(val value: T) : AssistantResult<T>

    data class Refused(val refusal: AssistantRefusal, val detail: String? = null) : AssistantResult<Nothing>
}

/**
 * One item on the shopping list, as an assistant sees it.
 *
 * @property food the food ("mjölk").
 * @property amount the amount, null when none was given.
 * @property unit the unit ("l", "dl"), null when none.
 * @property note the entry's note, empty when none.
 * @property lists the names of the named lists the item is on (empty: no named list).
 * @property later "Köp senare" (delayed for now).
 * @property waitingToSync the item (or a change of it) has not reached the server yet.
 */
data class AssistantShoppingItem(
    val food: String,
    val amount: Double? = null,
    val unit: String? = null,
    val note: String = "",
    val lists: List<String> = emptyList(),
    val later: Boolean = false,
    val waitingToSync: Boolean = false,
)

/**
 * What "add to the shopping list" added: the items in order and the named list they went to
 * (null: no named list, like Handla with no list chosen).
 */
data class AssistantAddition(val items: List<AssistantShoppingItem>, val list: String? = null)

/**
 * One planned meal.
 *
 * @property title what the meal is called (its own title, else the recipe's name).
 * @property mealType the meal type's name ("Middag").
 * @property time the meal type's time, null when it has none.
 * @property recipeId the planned recipe's id (for opening it), null for a meal without a recipe.
 * @property servings the planned servings.
 * @property note the meal's note, empty when none.
 */
data class AssistantMeal(
    val title: String,
    val mealType: String,
    val time: LocalTime? = null,
    val recipeId: Long? = null,
    val servings: Double = 1.0,
    val note: String = "",
)

/** The meals planned for [date], in the meal types' order. */
data class AssistantMealDay(val date: LocalDate, val meals: List<AssistantMeal>)

/**
 * A recipe found by a search.
 *
 * @property totalMinutes working plus waiting time, null when unknown.
 * @property rating the user's rating (0–5), null when none.
 */
data class AssistantRecipe(val id: Long, val name: String, val totalMinutes: Int? = null, val rating: Double? = null)
