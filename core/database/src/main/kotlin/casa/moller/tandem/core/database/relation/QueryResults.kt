package casa.moller.tandem.core.database.relation

import androidx.room3.ColumnInfo
import androidx.room3.Embedded
import androidx.room3.Relation
import casa.moller.tandem.core.database.entity.IngredientEntity
import casa.moller.tandem.core.database.entity.MealPlanEntity
import casa.moller.tandem.core.database.entity.MealTypeEntity
import casa.moller.tandem.core.database.entity.RecipeDetailEntity
import casa.moller.tandem.core.database.entity.RecipeOverviewEntity
import casa.moller.tandem.core.database.entity.StepEntity
import casa.moller.tandem.core.model.home.HomeSectionType

/**
 * One recipe row of a list, search result or carousel: the overview plus the local state that
 * lists show (pinned, available offline) and the keywords packed into one string (see
 * [keywordList]; `toModel()` unpacks it).
 *
 * @property keywordList `id<US>name` pairs separated by `<RS>` (ASCII 31 and 30), or null.
 */
data class RecipeListItem(
    @Embedded val overview: RecipeOverviewEntity,
    @ColumnInfo(name = "pinned") val pinned: Boolean,
    @ColumnInfo(name = "available_offline") val availableOffline: Boolean,
    @ColumnInfo(name = "last_viewed_local") val lastViewedLocalEpochMillis: Long?,
    @ColumnInfo(name = "keyword_list") val keywordList: String?,
)

/**
 * A stored detail with its steps and ingredients, read in one transaction. Ingredients are
 * linked to their step by [IngredientEntity.stepPosition] = [StepEntity.position].
 */
data class RecipeDetailWithSteps(
    @Embedded val detail: RecipeDetailEntity,
    @Relation(parentColumns = ["account_id", "recipe_id"], entityColumns = ["account_id", "recipe_id"])
    val steps: List<StepEntity>,
    @Relation(parentColumns = ["account_id", "recipe_id"], entityColumns = ["account_id", "recipe_id"])
    val ingredients: List<IngredientEntity>,
)

/** One recipe of a cached Today carousel, in carousel order. */
data class HomeSectionRow(
    @ColumnInfo(name = "section_type") val sectionType: HomeSectionType,
    @ColumnInfo(name = "position") val position: Int,
    @Embedded val item: RecipeListItem,
)

/**
 * A planned meal with its meal type and recipe, when those are stored. Either can be null when
 * the reference data was not synced yet (or the recipe is not visible to this user).
 */
data class MealPlanRow(
    @Embedded val entry: MealPlanEntity,
    @Embedded(prefix = "mt_") val mealType: MealTypeEntity?,
    @Embedded(prefix = "r_") val recipe: RecipeOverviewEntity?,
)

/**
 * A food for the recipe list's food filter: from the full food list, or from the stored
 * ingredients when the food list does not have it (demo mode, or before the first full sync).
 */
data class FoodNameRow(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "plural_name") val pluralName: String?,
)
