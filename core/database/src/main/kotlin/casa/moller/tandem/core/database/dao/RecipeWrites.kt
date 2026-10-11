package casa.moller.tandem.core.database.dao

import casa.moller.tandem.core.database.entity.IngredientEntity
import casa.moller.tandem.core.database.entity.KeywordEntity
import casa.moller.tandem.core.database.entity.RecipeDetailEntity
import casa.moller.tandem.core.database.entity.RecipeOverviewEntity
import casa.moller.tandem.core.database.entity.StepEntity

/** One entry of `GET /api/recipe/flat/`: the recipe index (plan 3.5). */
data class RecipeIndexEntry(val serverId: Long, val name: String, val imageUrl: String? = null)

/**
 * One recipe from a `GET /api/recipe/` list response.
 *
 * @property overview the overview columns; `localId` and `accountId` are ignored (set by the DAO).
 * @property keywords the recipe's keywords as listed (`{id, label}`), or null to keep the stored
 *   links. Unknown keywords are added; stored keywords are never overwritten by a label.
 */
data class RecipeOverviewWrite(val overview: RecipeOverviewEntity, val keywords: List<KeywordEntity>? = null)

/**
 * A full recipe from `GET /api/recipe/{id}/`, stored by `RecipeDao.replaceDetail` in one
 * transaction. `accountId`/`recipeId` inside the entities are overwritten with the call's values.
 *
 * @property overview the overview columns derived from the same JSON (name, image, times, rating...).
 * @property detail the detail row; its local columns (pinned, last viewed, portions) are ignored
 *   and the stored values kept.
 * @property steps every step, [StepEntity.position] 0..n-1.
 * @property ingredients every ingredient, linked by [IngredientEntity.stepPosition].
 * @property keywords the recipe's full keyword objects (upserted).
 */
data class RecipeDetailWrite(
    val overview: RecipeOverviewEntity,
    val detail: RecipeDetailEntity,
    val steps: List<StepEntity>,
    val ingredients: List<IngredientEntity>,
    val keywords: List<KeywordEntity>,
)

/**
 * What `RecipeDao.applyIndex` changed.
 *
 * @property inserted recipe ids that were new.
 * @property updated recipe ids whose name or image changed.
 * @property deleted recipe ids removed locally because the index no longer lists them (delete
 *   their cached images too).
 * @property kept recipe ids missing from the index but kept: pinned recipes with unsent edits,
 *   or everything when an empty index was refused.
 */
data class IndexChange(
    val inserted: List<Long> = emptyList(),
    val updated: List<Long> = emptyList(),
    val deleted: List<Long> = emptyList(),
    val kept: List<Long> = emptyList(),
)
