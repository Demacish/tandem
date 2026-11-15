package casa.moller.tandem.assistant

import androidx.appfunctions.AppFunctionSerializable
import java.time.LocalDate
import java.time.LocalTime

// Fas 5 (funktioner lane, plan §8.7): what Tandem's App Functions return. The KDoc of every
// property is what an assistant reads (isDescribedByKDoc), so it describes the value, never the
// assistant's behaviour. Changing a name changes the functions' published schema.

/** One item on the shopping list. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class ShoppingListItem(
    /** The food, as the household named it (for example "mjölk"). */
    val food: String,
    /** The amount to buy; null when no amount was given. */
    val amount: Double?,
    /** The unit of the amount (for example "l" or "dl"); null when there is none. */
    val unit: String?,
    /** A note on the item; empty when there is none. */
    val note: String,
    /** The names of the named shopping lists the item is on; empty when it is on none. */
    val lists: List<String>,
    /** True when the user postponed the item ("buy later"). */
    val buyLater: Boolean,
    /** True when the item or a change of it has not reached the household's recipe server yet. */
    val waitingToSync: Boolean,
)

/** What was added to the shopping list. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class AddedToShoppingList(
    /** The items added, in order: one per food in the text. */
    val items: List<ShoppingListItem>,
    /** The named shopping list the items went to; null when they went to no named list. */
    val listName: String?,
)

/** One planned meal. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class PlannedMeal(
    /** What the meal is called: its own title or the planned recipe's name. */
    val title: String,
    /** The meal type, as the household named it (for example "Middag" for dinner). */
    val mealType: String,
    /** The meal type's usual time of day; null when it has none. */
    val time: LocalTime?,
    /** The planned recipe's id, for openRecipe; null for a meal without a recipe. */
    val recipeId: Long?,
    /** The number of servings planned. */
    val servings: Double,
    /** The meal's note; empty when there is none. */
    val note: String,
)

/** The meals planned for one day. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class MealPlanForDay(
    /** The day. */
    val date: LocalDate,
    /** The meals planned for the day, in the order of their meal types; empty when nothing is planned. */
    val meals: List<PlannedMeal>,
)

/** A recipe stored in Tandem. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class RecipeSummary(
    /** The recipe's id, for openRecipe. */
    val id: Long,
    /** The recipe's name. */
    val name: String,
    /** The working plus waiting time in minutes; null when unknown. */
    val totalMinutes: Int?,
    /** The household's rating from 0 to 5; null when not rated. */
    val rating: Double?,
)
