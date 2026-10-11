package casa.moller.tandem.core.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index

/**
 * One planned meal (plan 5.7 `meal_plan`). Phase 1 only reads them.
 *
 * Keyed by (`account_id`, `local_id`) with a unique (`account_id`, `server_id`): rows from the
 * server use `local_id = server_id`; a later phase creates entries offline with a negative
 * `local_id` and `server_id = null` until the server answers (plan 3.5).
 *
 * @property fromDate first day as `yyyy-MM-dd`, in the server's time zone (string order = date order).
 * @property toDate last day as `yyyy-MM-dd`.
 * @property fromRaw the server's `from_date` date-time as sent (with offset), for round trips.
 * @property toRaw the server's `to_date` date-time as sent.
 * @property shopping the server's read-only `shopping`.
 * @property baseSnapshot reserved for the conflict check of later phases (JSON of the values at
 *   the last fetch, plan 3.5); null in Phase 1.
 */
@Entity(
    tableName = "meal_plan",
    primaryKeys = ["account_id", "local_id"],
    indices = [
        Index(value = ["account_id", "server_id"], unique = true),
        Index(value = ["account_id", "to_date"]),
    ],
)
data class MealPlanEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "local_id") val localId: Long,
    @ColumnInfo(name = "server_id") val serverId: Long?,
    @ColumnInfo(name = "title") val title: String = "",
    @ColumnInfo(name = "recipe_id") val recipeId: Long? = null,
    @ColumnInfo(name = "servings") val servings: Double = 1.0,
    @ColumnInfo(name = "note") val note: String = "",
    @ColumnInfo(name = "from_date") val fromDate: String,
    @ColumnInfo(name = "to_date") val toDate: String,
    @ColumnInfo(name = "from_raw") val fromRaw: String? = null,
    @ColumnInfo(name = "to_raw") val toRaw: String? = null,
    @ColumnInfo(name = "meal_type_id") val mealTypeId: Long,
    @ColumnInfo(name = "shopping") val shopping: Boolean = false,
    @ColumnInfo(name = "created_by") val createdBy: Long? = null,
    @ColumnInfo(name = "base_snapshot") val baseSnapshot: String? = null,
)
