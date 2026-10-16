package casa.moller.tandem.core.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * One queued write (plan 3.5 "Skrivningar", 5.7 `outbox`). Rows are sent in [id] order; a row
 * waits while the row in [dependsOn] still exists, and while an older row for the same object
 * (same [entity] and [serverId] or [localId]) is unsent. A sent row is deleted.
 *
 * @property id running number; gives the order.
 * @property accountId owning account.
 * @property op what to do, see [OutboxOps].
 * @property entity which kind of object, see [OutboxEntities].
 * @property localId local id of an object created offline (negative), if any.
 * @property serverId server id of the object, null until an offline-created object is sent.
 * @property payload JSON body (only changed fields).
 * @property baseSnapshot JSON of the values the change started from (conflict check), if any.
 * @property dependsOn id of a row that must be sent first, if any.
 * @property attempts how often sending was started.
 * @property lastError a short, non-sensitive description of the last failure, if any.
 * @property createdAtEpochMillis when the change was made.
 * @property state where the row stands.
 */
@Entity(
    tableName = "outbox",
    indices = [
        Index(value = ["account_id", "state"]),
        Index(value = ["account_id", "entity", "server_id"]),
        Index(value = ["depends_on"]),
    ],
)
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "op") val op: String,
    @ColumnInfo(name = "entity") val entity: String,
    @ColumnInfo(name = "local_id") val localId: Long? = null,
    @ColumnInfo(name = "server_id") val serverId: Long? = null,
    @ColumnInfo(name = "payload") val payload: String = "{}",
    @ColumnInfo(name = "base_snapshot") val baseSnapshot: String? = null,
    @ColumnInfo(name = "depends_on") val dependsOn: Long? = null,
    @ColumnInfo(name = "attempts") val attempts: Int = 0,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
    @ColumnInfo(name = "created_at") val createdAtEpochMillis: Long,
    @ColumnInfo(name = "state") val state: OutboxState = OutboxState.PENDING,
)

/** State of an [OutboxEntity] (stored as its name). */
enum class OutboxState {
    /** Waiting to be sent. */
    PENDING,

    /** Held back (safe mode, plan 3.6): queued but not sent until resumed. */
    PAUSED,

    /** Failed permanently (e.g. 400); shown in "Pending changes", never retried automatically. */
    FAILED,

    /** Being sent right now. Reset to [PENDING] at start-up in case the app died while sending. */
    IN_FLIGHT,
}

/** Values for [OutboxEntity.op] (plan 5.7). Phase 1 sends [VIEW_LOG] and (from 0.2.0) [COOK_LOG]. */
object OutboxOps {
    const val CREATE: String = "CREATE"
    const val PATCH: String = "PATCH"
    const val DELETE: String = "DELETE"
    const val BULK_CHECK: String = "BULK_CHECK"

    /**
     * `POST /api/cook-log/` for "Klart!" (Phase 1b): [OutboxEntity.serverId] is the recipe id and the
     * `cook_log` entry points to the row (`outbox_id`). Never merged. Queued by `CookLogDao.logLocally`.
     */
    const val COOK_LOG: String = "COOK_LOG"

    /** `POST /api/view-log/` `{recipe}` when a recipe is opened (plan 3.3). */
    const val VIEW_LOG: String = "VIEW_LOG"
}

/** Values for [OutboxEntity.entity]. */
object OutboxEntities {
    /** A recipe; [OutboxEntity.serverId] is the recipe id (also for [OutboxOps.VIEW_LOG] and [OutboxOps.COOK_LOG]). */
    const val RECIPE: String = "recipe"
    const val MEAL_PLAN: String = "meal_plan"
    const val SHOPPING_ENTRY: String = "shopping_entry"
}
