package casa.moller.tandem.core.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity

/**
 * A keyword (plan 5.7 `keyword`). Filled by the full keyword list (all fields) and, for keywords
 * that only appear as `{id, label}` in recipe lists, by an insert that never overwrites a full row.
 *
 * @property fullName Tandoor's `full_name` ("Huvudrätt > Pasta"), when known.
 */
@Entity(tableName = "keyword", primaryKeys = ["account_id", "id"])
data class KeywordEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "description") val description: String = "",
    @ColumnInfo(name = "parent_id") val parentId: Long? = null,
    @ColumnInfo(name = "full_name") val fullName: String? = null,
)

/** A food (plan 5.7 `food`), from the full food list. Used by later phases (shopping). */
@Entity(tableName = "food", primaryKeys = ["account_id", "id"])
data class FoodEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "plural_name") val pluralName: String? = null,
    @ColumnInfo(name = "on_hand") val onHand: Boolean = false,
    @ColumnInfo(name = "ignore_shopping") val ignoreShopping: Boolean = false,
    @ColumnInfo(name = "supermarket_category_id") val supermarketCategoryId: Long? = null,
)

/** A unit of measurement (plan 5.7 `unit`), from the full unit list. */
@Entity(tableName = "unit", primaryKeys = ["account_id", "id"])
data class UnitEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "plural_name") val pluralName: String? = null,
    @ColumnInfo(name = "description") val description: String? = null,
    @ColumnInfo(name = "base_unit") val baseUnit: String? = null,
)

/**
 * A meal type (plan 5.7 `meal_type`).
 *
 * @property typeOrder the server's `order`.
 * @property time the server's `time` as `HH:mm[:ss]`, if set.
 * @property color hex colour, if set.
 */
@Entity(tableName = "meal_type", primaryKeys = ["account_id", "id"])
data class MealTypeEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "type_order") val typeOrder: Int = 0,
    @ColumnInfo(name = "time") val time: String? = null,
    @ColumnInfo(name = "color") val color: String? = null,
)

/**
 * The user's Tandoor preferences (plan 5.7 `user_preference`), one row per account. Mirrors
 * `UserPreferences` in core:model (see `toEntity` / `toModel` in the mapper package).
 */
@Entity(tableName = "user_preference", primaryKeys = ["account_id"])
data class UserPreferenceEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "use_fractions") val useFractions: Boolean,
    @ColumnInfo(name = "ingredient_decimals") val ingredientDecimals: Int,
    @ColumnInfo(name = "default_unit") val defaultUnit: String,
    @ColumnInfo(name = "use_kj") val useKj: Boolean,
    @ColumnInfo(name = "show_step_ingredients") val showStepIngredients: Boolean,
    @ColumnInfo(name = "default_meal_type_id") val defaultMealTypeId: Long?,
    @ColumnInfo(name = "shopping_auto_sync_seconds") val shoppingAutoSyncSeconds: Int,
    @ColumnInfo(name = "meal_plan_auto_add_shopping") val mealPlanAutoAddShopping: Boolean,
    @ColumnInfo(name = "meal_plan_auto_exclude_on_hand") val mealPlanAutoExcludeOnHand: Boolean,
    @ColumnInfo(name = "meal_plan_auto_include_related") val mealPlanAutoIncludeRelated: Boolean,
    @ColumnInfo(name = "default_delay_hours") val defaultDelayHours: Double,
    @ColumnInfo(name = "filter_to_supermarket") val filterToSupermarket: Boolean,
    @ColumnInfo(name = "shopping_recent_days") val shoppingRecentDays: Int,
    @ColumnInfo(name = "shopping_add_on_hand") val shoppingAddOnHand: Boolean,
    @ColumnInfo(name = "comments_enabled") val commentsEnabled: Boolean,
    @ColumnInfo(name = "fetched_at") val fetchedAtEpochMillis: Long = 0,
)
