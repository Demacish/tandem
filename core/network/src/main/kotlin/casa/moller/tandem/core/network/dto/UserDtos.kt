package casa.moller.tandem.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** `GET /api/user/{id}/`, and the nested user in user-preference and space (FACTS d). */
@Serializable
data class UserDto(
    val id: Long? = null,
    val username: String = "",
    @SerialName("first_name") val firstName: String = "",
    @SerialName("last_name") val lastName: String = "",
    /** First + last name, or the username when both are empty. */
    @SerialName("display_name") val displayName: String = "",
    @SerialName("is_staff") val isStaff: Boolean = false,
    @SerialName("is_superuser") val isSuperuser: Boolean = false,
    @SerialName("is_active") val isActive: Boolean = true,
)

/**
 * One element of `GET /api/user-preference/` (an array with exactly the caller's entry).
 * `user` is a full [UserDto] object, not an id; this is how a pasted token learns who it is.
 *
 * Phase 1 reads every field (plan 3.2: the app FOLLOWS these settings). Defaults are those of a
 * new Tandoor 2.6 account, like `UserPreferences.Default`. Enum-like values ([theme],
 * [defaultPage], [navTextColor]) stay strings.
 */
@Serializable
data class UserPreferenceDto(
    val user: UserDto? = null,
    val image: JsonElement? = null,
    val theme: String = "",
    @SerialName("nav_bg_color") val navBgColor: String = "",
    @SerialName("nav_text_color") val navTextColor: String = "",
    @SerialName("nav_show_logo") val navShowLogo: Boolean = true,
    @SerialName("default_unit") val defaultUnit: String = "g",
    @SerialName("default_page") val defaultPage: String = "",
    @SerialName("use_fractions") val useFractions: Boolean = false,
    @SerialName("use_kj") val useKj: Boolean = false,
    @SerialName("nav_sticky") val navSticky: Boolean = true,
    @SerialName("ingredient_decimals") val ingredientDecimals: Int = 2,
    /** Whether cook log comments are shown. */
    val comments: Boolean = true,
    /** Seconds. */
    @SerialName("shopping_auto_sync") val shoppingAutoSync: Int = 5,
    @SerialName("mealplan_autoadd_shopping") val mealplanAutoaddShopping: Boolean = false,
    @SerialName("food_inherit_default") val foodInheritDefault: JsonElement? = null,
    /** Hours. */
    @SerialName("default_delay") val defaultDelay: Double = 4.0,
    @SerialName("mealplan_autoinclude_related") val mealplanAutoincludeRelated: Boolean = true,
    @SerialName("mealplan_autoexclude_onhand") val mealplanAutoexcludeOnhand: Boolean = true,
    @SerialName("shopping_recent_days") val shoppingRecentDays: Int = 7,
    @SerialName("csv_delim") val csvDelim: String = ",",
    @SerialName("csv_prefix") val csvPrefix: String = "",
    @SerialName("shopping_update_food_lists") val shoppingUpdateFoodLists: Boolean = true,
    @SerialName("default_meal_type") val defaultMealType: MealTypeDto? = null,
    @SerialName("filter_to_supermarket") val filterToSupermarket: Boolean = false,
    @SerialName("shopping_add_onhand") val shoppingAddOnhand: Boolean = false,
    @SerialName("left_handed") val leftHanded: Boolean = false,
    @SerialName("show_step_ingredients") val showStepIngredients: Boolean = true,
    @SerialName("food_children_exist") val foodChildrenExist: Boolean = false,
)
