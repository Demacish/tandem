package casa.moller.tandem.core.model.preferences

/**
 * The user's Tandoor preferences (`GET /api/user-preference/`, plan 3.2). The app FOLLOWS these
 * instead of keeping its own copies; they are changed in Tandoor (or, in a later phase, with
 * `PATCH /api/user-preference/{user}/`). Defaults match a new Tandoor 2.6 account.
 *
 * @property useFractions show amounts as fractions (½, ¼, ⅓) instead of decimals.
 * @property ingredientDecimals decimals for amounts when [useFractions] is off.
 * @property defaultUnit the unit new ingredients get on the server (e.g. "g").
 * @property useKj show energy in kJ instead of kcal.
 * @property showStepIngredients show each step's ingredients next to the step text.
 * @property defaultMealTypeId the meal type preselected when planning, if set.
 * @property shoppingAutoSyncSeconds how often the web app syncs the shopping list (Phase 2).
 * @property mealPlanAutoAddShopping the server adds a planned recipe's ingredients to the
 *   shopping list when the meal is created (Phase 2: avoid adding them twice).
 * @property mealPlanAutoExcludeOnHand foods marked "have at home" are left out when adding to the list.
 * @property mealPlanAutoIncludeRelated sub-recipes are included when adding to the list.
 * @property defaultDelayHours "buy later" delay in hours (Phase 2).
 * @property filterToSupermarket only show categories of the chosen supermarket (Phase 2).
 * @property shoppingRecentDays how long checked entries stay visible on the list (Phase 2).
 * @property shoppingAddOnHand checking off an entry marks the food "have at home" for the
 *   whole household (Phase 2, explained in Settings).
 * @property commentsEnabled the space shows cook log comments.
 */
data class UserPreferences(
    val useFractions: Boolean = false,
    val ingredientDecimals: Int = 2,
    val defaultUnit: String = "g",
    val useKj: Boolean = false,
    val showStepIngredients: Boolean = true,
    val defaultMealTypeId: Long? = null,
    val shoppingAutoSyncSeconds: Int = 5,
    val mealPlanAutoAddShopping: Boolean = false,
    val mealPlanAutoExcludeOnHand: Boolean = true,
    val mealPlanAutoIncludeRelated: Boolean = true,
    val defaultDelayHours: Double = 4.0,
    val filterToSupermarket: Boolean = false,
    val shoppingRecentDays: Int = 7,
    val shoppingAddOnHand: Boolean = false,
    val commentsEnabled: Boolean = true,
) {
    companion object {
        /** The defaults of a new Tandoor 2.6 account; used until the server's values are known. */
        val Default: UserPreferences = UserPreferences()
    }
}
