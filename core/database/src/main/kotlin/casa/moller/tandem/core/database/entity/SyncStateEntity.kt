package casa.moller.tandem.core.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity

/**
 * Sync bookkeeping per account and entity type (plan 5.7 `sync_state`).
 *
 * @property accountId the local account id (`ServerAccount.id`).
 * @property entity which data the row is about, e.g. `recipe`, `meal_plan`, `keyword`.
 * @property lastServerTimestamp the server's `timestamp` of the last delta fetch (shopping list), if any.
 * @property lastDate the last `updatedon_gte` date used for recipes (`YYYY-MM-DD`), if any.
 * @property lastFullRelistEpochMillis when the whole list was last read (deletion detection), if ever.
 */
@Entity(tableName = "sync_state", primaryKeys = ["account_id", "entity"])
data class SyncStateEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "entity") val entity: String,
    @ColumnInfo(name = "last_server_timestamp") val lastServerTimestamp: String? = null,
    @ColumnInfo(name = "last_date") val lastDate: String? = null,
    @ColumnInfo(name = "last_full_relist") val lastFullRelistEpochMillis: Long? = null,
)
