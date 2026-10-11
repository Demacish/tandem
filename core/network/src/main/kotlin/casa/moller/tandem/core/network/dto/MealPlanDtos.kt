package casa.moller.tandem.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `GET /api/meal-type/`: shared by the whole space in 2.6. [time] is `HH:mm:ss` or null. */
@Serializable
data class MealTypeDto(
    val id: Long? = null,
    val name: String = "",
    val order: Int = 0,
    val time: String? = null,
    /** e.g. `#C2185B`, or null. */
    val color: String? = null,
    @SerialName("created_by") val createdBy: Long? = null,
)

/**
 * One entry of `GET /api/meal-plan/?from_date&to_date` (FACTS "Fas 1 g"). Dates are date-times in
 * the server's offset (`2000-01-15T18:00:00+02:00`): take the date in THAT offset. There is no
 * `updated_at`. [recipeName] is missing entirely when the entry has no recipe.
 */
@Serializable
data class MealPlanDto(
    val id: Long? = null,
    val title: String = "",
    val recipe: RecipeOverviewDto? = null,
    /** A decimal (`4.0`). */
    val servings: Double = 1.0,
    val note: String = "",
    @SerialName("note_markdown") val noteMarkdown: String = "",
    @SerialName("from_date") val fromDate: String? = null,
    @SerialName("to_date") val toDate: String? = null,
    @SerialName("meal_type") val mealType: MealTypeDto? = null,
    @SerialName("created_by") val createdBy: Long? = null,
    @SerialName("recipe_name") val recipeName: String? = null,
    @SerialName("meal_type_name") val mealTypeName: String? = null,
    /** True when shopping list entries exist for this meal. */
    val shopping: Boolean = false,
)
