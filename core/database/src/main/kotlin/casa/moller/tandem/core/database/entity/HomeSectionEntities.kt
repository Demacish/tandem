package casa.moller.tandem.core.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import casa.moller.tandem.core.model.home.HomeSectionType

/**
 * Cache of one Today carousel (plan 6.2 "Idag"): when the server query behind [sectionType] was
 * last answered. The ordered recipe ids are in [HomeSectionRecipeEntity].
 */
@Entity(tableName = "home_section", primaryKeys = ["account_id", "section_type"])
data class HomeSectionEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "section_type") val sectionType: HomeSectionType,
    @ColumnInfo(name = "fetched_at") val fetchedAtEpochMillis: Long,
)

/** Recipe number [position] (0-based) of a cached Today carousel. */
@Entity(tableName = "home_section_recipe", primaryKeys = ["account_id", "section_type", "position"])
data class HomeSectionRecipeEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "section_type") val sectionType: HomeSectionType,
    @ColumnInfo(name = "position") val position: Int,
    @ColumnInfo(name = "recipe_id") val recipeId: Long,
)
