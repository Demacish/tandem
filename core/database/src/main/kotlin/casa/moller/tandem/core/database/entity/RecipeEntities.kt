package casa.moller.tandem.core.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Fts4
import androidx.room3.FtsOptions
import androidx.room3.Index
import androidx.room3.PrimaryKey
import casa.moller.tandem.core.model.recipe.NutritionPresence

/**
 * One recipe as lists show it (plan 5.7 `recipe_overview`), filled from `recipe/flat` (only
 * [name] and [imageUrl]) and from `recipe` list responses (everything else).
 *
 * The natural key is (`account_id`, `server_id`) (unique index). [localId] is the SQLite rowid,
 * shared with `recipe_fts.rowid` (plan 5.7), so FTS rows are found and removed by rowid. Writes
 * go through `RecipeDao`, which looks up the existing [localId] first; never insert this entity
 * directly.
 *
 * Times are epoch milliseconds (UTC), so the table stays free of type converters.
 *
 * @property localId local rowid (0 = not stored yet).
 * @property accountId owning account.
 * @property serverId Tandoor recipe id.
 * @property name recipe name.
 * @property description description, if any.
 * @property imageUrl absolute image URL, if any.
 * @property workingMinutes `working_time`, 0 when unknown.
 * @property waitingMinutes `waiting_time`, 0 when unknown.
 * @property rating the user's rating (only some server responses carry it).
 * @property lastCookedEpochMillis last cook log entry (computed locally from `cook-log` in 2.6).
 * @property timesCooked how often cooked, when known.
 * @property isNew Tandoor's `new` flag.
 * @property isPrivate Tandoor's `private` flag.
 * @property createdAtEpochMillis server `created_at`, when known.
 * @property updatedAtEpochMillis server `updated_at`, when known.
 * @property searchName [name] lower case without diacritics (`SearchText.normalize`), for
 *   substring search inside compound words ("pasta" in "Svamppasta"). Set by `RecipeDao`.
 */
@Entity(
    tableName = "recipe_overview",
    indices = [Index(value = ["account_id", "server_id"], unique = true)],
)
data class RecipeOverviewEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "local_id")
    val localId: Long = 0,
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "server_id") val serverId: Long,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "description") val description: String? = null,
    @ColumnInfo(name = "image_url") val imageUrl: String? = null,
    @ColumnInfo(name = "working_minutes") val workingMinutes: Int = 0,
    @ColumnInfo(name = "waiting_minutes") val waitingMinutes: Int = 0,
    @ColumnInfo(name = "rating") val rating: Double? = null,
    @ColumnInfo(name = "last_cooked") val lastCookedEpochMillis: Long? = null,
    @ColumnInfo(name = "times_cooked") val timesCooked: Int? = null,
    @ColumnInfo(name = "is_new") val isNew: Boolean = false,
    @ColumnInfo(name = "is_private") val isPrivate: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAtEpochMillis: Long? = null,
    @ColumnInfo(name = "updated_at") val updatedAtEpochMillis: Long? = null,
    @ColumnInfo(name = "search_name") val searchName: String = "",
)

/**
 * The full recipe plus local per-recipe state (plan 5.7 `recipe_detail`).
 *
 * [rawJson] is the complete `GET /api/recipe/{id}/` body, so a later app version can re-map it
 * without downloading again (plan 3.6). The other server columns are extracted from it by the
 * sync layer. A row with [rawJson] = null is a "stub" that only holds local state ([pinned],
 * [lastViewedLocalEpochMillis], [portionsChosen]) for a recipe whose detail is not stored yet;
 * a recipe is available offline exactly when its [rawJson] is stored.
 *
 * @property accountId owning account.
 * @property recipeId Tandoor recipe id.
 * @property rawJson the raw recipe JSON, null for a stub.
 * @property servings `servings` (at least 1).
 * @property servingsText `servings_text`.
 * @property workingMinutes `working_time`.
 * @property waitingMinutes `waiting_time`.
 * @property diameter `diameter`, 0 when not used.
 * @property diameterText `diameter_text`.
 * @property sourceUrl `source_url`, if any.
 * @property showIngredientOverview `show_ingredient_overview`.
 * @property nutrition which nutrition data the recipe has.
 * @property fetchedAtEpochMillis when the detail was fetched, null for a stub.
 * @property pinned "Download for offline use" (kept, refreshed every 24 h, never evicted).
 * @property lastViewedLocalEpochMillis when the recipe was last opened on this device.
 * @property portionsChosen the servings the user picked on this device, null = recipe default.
 */
@Entity(tableName = "recipe_detail", primaryKeys = ["account_id", "recipe_id"])
data class RecipeDetailEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "recipe_id") val recipeId: Long,
    @ColumnInfo(name = "raw_json") val rawJson: String? = null,
    @ColumnInfo(name = "servings") val servings: Int = 1,
    @ColumnInfo(name = "servings_text") val servingsText: String = "",
    @ColumnInfo(name = "working_minutes") val workingMinutes: Int = 0,
    @ColumnInfo(name = "waiting_minutes") val waitingMinutes: Int = 0,
    @ColumnInfo(name = "diameter") val diameter: Int = 0,
    @ColumnInfo(name = "diameter_text") val diameterText: String = "",
    @ColumnInfo(name = "source_url") val sourceUrl: String? = null,
    @ColumnInfo(name = "show_ingredient_overview") val showIngredientOverview: Boolean = true,
    @ColumnInfo(name = "nutrition") val nutrition: NutritionPresence = NutritionPresence.NONE,
    @ColumnInfo(name = "fetched_at") val fetchedAtEpochMillis: Long? = null,
    @ColumnInfo(name = "pinned") val pinned: Boolean = false,
    @ColumnInfo(name = "last_viewed_local") val lastViewedLocalEpochMillis: Long? = null,
    @ColumnInfo(name = "portions_chosen") val portionsChosen: Double? = null,
)

/**
 * One step of a stored recipe (plan 5.7 `step`), rebuilt whenever the detail is stored.
 *
 * Keyed by [position] (0-based index in the recipe after sorting by the server's `order`),
 * because the server's `order` is not guaranteed to be unique.
 *
 * @property stepId Tandoor step id.
 * @property stepOrder the server's `order`.
 * @property stepRecipeId `step_recipe`, if the step embeds another recipe.
 * @property stepRecipeName that recipe's name, if sent.
 * @property imageUrl the step's photo (schema 3, 0.2.0), null without one.
 */
@Entity(tableName = "step", primaryKeys = ["account_id", "recipe_id", "position"])
data class StepEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "recipe_id") val recipeId: Long,
    @ColumnInfo(name = "position") val position: Int,
    @ColumnInfo(name = "step_id") val stepId: Long,
    @ColumnInfo(name = "name") val name: String = "",
    @ColumnInfo(name = "instruction") val instruction: String = "",
    @ColumnInfo(name = "time_minutes") val timeMinutes: Int = 0,
    @ColumnInfo(name = "step_order") val stepOrder: Int = 0,
    @ColumnInfo(name = "show_as_header") val showAsHeader: Boolean = false,
    @ColumnInfo(name = "show_ingredients_table") val showIngredientsTable: Boolean = true,
    @ColumnInfo(name = "step_recipe_id") val stepRecipeId: Long? = null,
    @ColumnInfo(name = "step_recipe_name") val stepRecipeName: String? = null,
    @ColumnInfo(name = "image_url") val imageUrl: String? = null,
)

/**
 * One ingredient row of a stored recipe (plan 5.7 `ingredient`), rebuilt with the detail. Food
 * and unit names are copied from the recipe JSON so cook mode and FTS need no joins.
 *
 * @property stepPosition [StepEntity.position] of the step the ingredient belongs to.
 * @property position 0-based index within the step (after sorting by the server's `order`).
 * @property ingredientId Tandoor ingredient id.
 * @property ingredientOrder the server's `order`.
 * @property conversionsJson the server-computed `conversions` as a JSON array of
 *   `{"amount": 1.0, "unit": "g", "unit_plural": null}` objects (see `IngredientConversions`).
 */
@Entity(tableName = "ingredient", primaryKeys = ["account_id", "recipe_id", "step_position", "position"])
data class IngredientEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "recipe_id") val recipeId: Long,
    @ColumnInfo(name = "step_position") val stepPosition: Int,
    @ColumnInfo(name = "position") val position: Int,
    @ColumnInfo(name = "ingredient_id") val ingredientId: Long,
    @ColumnInfo(name = "food_id") val foodId: Long? = null,
    @ColumnInfo(name = "food_name") val foodName: String? = null,
    @ColumnInfo(name = "food_plural_name") val foodPluralName: String? = null,
    @ColumnInfo(name = "food_on_hand") val foodOnHand: Boolean = false,
    @ColumnInfo(name = "unit_id") val unitId: Long? = null,
    @ColumnInfo(name = "unit_name") val unitName: String? = null,
    @ColumnInfo(name = "unit_plural_name") val unitPluralName: String? = null,
    @ColumnInfo(name = "unit_base_unit") val unitBaseUnit: String? = null,
    @ColumnInfo(name = "amount") val amount: Double = 0.0,
    @ColumnInfo(name = "note") val note: String? = null,
    @ColumnInfo(name = "is_header") val isHeader: Boolean = false,
    @ColumnInfo(name = "no_amount") val noAmount: Boolean = false,
    @ColumnInfo(name = "original_text") val originalText: String? = null,
    @ColumnInfo(name = "ingredient_order") val ingredientOrder: Int = 0,
    @ColumnInfo(name = "conversions_json") val conversionsJson: String? = null,
)

/** Recipe to keyword link (plan 5.7 `recipe_keyword`), from list responses and the detail. */
@Entity(
    tableName = "recipe_keyword",
    primaryKeys = ["account_id", "recipe_id", "keyword_id"],
    indices = [Index(value = ["account_id", "keyword_id"])],
)
data class RecipeKeywordCrossRef(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "recipe_id") val recipeId: Long,
    @ColumnInfo(name = "keyword_id") val keywordId: Long,
)

/**
 * Full-text index over recipes (plan 5.7 `recipe_fts`): FTS4 with `unicode61 remove_diacritics=2`,
 * so "kram" finds "Krämig" and "arter" finds "Ärtsoppa" (case and diacritics folded).
 *
 * Content is maintained manually (not an external-content table), because it combines four
 * tables: [rowId] = `recipe_overview.local_id`, [name] and [description] from the overview,
 * [keywords] from `recipe_keyword` + `keyword`, [ingredients] from the stored `ingredient` food
 * names. `RecipeDao` rewrites the rows in the same transaction as every write that changes one
 * of those sources; nothing else may write this table.
 */
@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61, tokenizerArgs = ["remove_diacritics=2"])
@Entity(tableName = "recipe_fts")
data class RecipeFtsEntity(
    @PrimaryKey
    @ColumnInfo(name = "rowid")
    val rowId: Long,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "description") val description: String,
    @ColumnInfo(name = "keywords") val keywords: String,
    @ColumnInfo(name = "ingredients") val ingredients: String,
)
