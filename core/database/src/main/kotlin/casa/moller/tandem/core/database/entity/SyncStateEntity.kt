package casa.moller.tandem.core.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity

/**
 * Sync bookkeeping per account and entity type (plan 5.7 `sync_state`).
 *
 * @property accountId the local account id (`ServerAccount.id`).
 * @property entity which data the row is about; use the constants in [SyncEntities].
 * @property lastServerTimestamp the server's `timestamp` of the last delta fetch (shopping list), if any.
 * @property lastDate the last `updatedon_gte` date used for recipes (`YYYY-MM-DD`), if any.
 * @property lastFullRelistEpochMillis when the whole list was last read (deletion detection), if ever.
 * @property windowFrom first day (`YYYY-MM-DD`) of the last fetched meal plan window, if any.
 * @property windowTo last day (`YYYY-MM-DD`) of the last fetched meal plan window, if any.
 * @property hash a hash of the last full response of a small table, to skip unchanged rewrites.
 */
@Entity(tableName = "sync_state", primaryKeys = ["account_id", "entity"])
data class SyncStateEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "entity") val entity: String,
    @ColumnInfo(name = "last_server_timestamp") val lastServerTimestamp: String? = null,
    @ColumnInfo(name = "last_date") val lastDate: String? = null,
    @ColumnInfo(name = "last_full_relist") val lastFullRelistEpochMillis: Long? = null,
    @ColumnInfo(name = "window_from") val windowFrom: String? = null,
    @ColumnInfo(name = "window_to") val windowTo: String? = null,
    @ColumnInfo(name = "hash") val hash: String? = null,
)

/** Values for [SyncStateEntity.entity]. */
object SyncEntities {
    const val RECIPE_INDEX: String = "recipe_index"
    const val RECIPE: String = "recipe"
    const val KEYWORD: String = "keyword"
    const val FOOD: String = "food"
    const val UNIT: String = "unit"
    const val MEAL_TYPE: String = "meal_type"
    const val MEAL_PLAN: String = "meal_plan"
    const val USER_PREFERENCE: String = "user_preference"
    const val HOME_SECTIONS: String = "home_sections"

    /** `last_full_relist`: when a complete server cook log was last stored in `cook_log` (Phase 1b). */
    const val COOK_LOG: String = "cook_log"
}
