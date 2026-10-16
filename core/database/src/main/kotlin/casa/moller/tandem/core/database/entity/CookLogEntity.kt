package casa.moller.tandem.core.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index

/**
 * One cook-log entry (Tandoor's `cook-log`, feature #8 "Klart!"), the source of "times cooked",
 * "last cooked" and the user's own rating (FACTS "Fas 1 c": the server's lists carry none of them).
 *
 * Keyed by (`account_id`, `local_id`) with a unique (`account_id`, `server_id`), like `meal_plan`
 * (plan 5.7): entries read from the server use `local_id = server_id`; an entry logged on this
 * device gets a negative `local_id` and `server_id = null` until the outbox has sent it, and is
 * then rewritten to the server's id in the same transaction that removes its outbox row. The
 * entry points to its outbox row ([outboxId]); the outbox row itself is an ordinary `recipe` row
 * (`server_id` = recipe id, no `local_id`), so a later offline-created object's id rewrite can
 * never touch it.
 *
 * @property recipeId the Tandoor recipe id.
 * @property servings whole servings, as Tandoor stores them, if any.
 * @property rating 0–5, or null when not rated.
 * @property comment the comment, if any.
 * @property createdBy the Tandoor user id of the author; null for an entry logged in demo mode.
 * @property cookedAtEpochMillis `created_at` (the time "Klart!" was pressed for local entries,
 *   whole seconds, sent as `created_at`); null when the server's value was unreadable.
 * @property state where the entry stands, see [CookLogState].
 * @property outboxId the queued `COOK_LOG` outbox row while there is one (PENDING, or FAILED after a 400).
 * @property sentAtEpochMillis when this device's POST succeeded ([CookLogState.SENT]), if it did.
 * @property problem why a local entry is [CookLogState.FAILED] or [CookLogState.DROPPED], see [CookLogProblems].
 */
@Entity(
    tableName = "cook_log",
    primaryKeys = ["account_id", "local_id"],
    indices = [
        Index(value = ["account_id", "server_id"], unique = true),
        Index(value = ["account_id", "recipe_id"]),
        Index(value = ["account_id", "outbox_id"]),
    ],
)
data class CookLogEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "local_id") val localId: Long,
    @ColumnInfo(name = "server_id") val serverId: Long?,
    @ColumnInfo(name = "recipe_id") val recipeId: Long,
    @ColumnInfo(name = "servings") val servings: Int? = null,
    @ColumnInfo(name = "rating") val rating: Int? = null,
    @ColumnInfo(name = "comment") val comment: String? = null,
    @ColumnInfo(name = "created_by") val createdBy: Long? = null,
    @ColumnInfo(name = "cooked_at") val cookedAtEpochMillis: Long? = null,
    @ColumnInfo(name = "state") val state: CookLogState = CookLogState.SYNCED,
    @ColumnInfo(name = "outbox_id") val outboxId: Long? = null,
    @ColumnInfo(name = "sent_at") val sentAtEpochMillis: Long? = null,
    @ColumnInfo(name = "problem") val problem: String? = null,
)

/**
 * State of a [CookLogEntity] (stored as its name). [PENDING], [SENT] and [SYNCED] entries count
 * in the statistics; [FAILED] and [DROPPED] ones are kept only as a record of what happened.
 */
enum class CookLogState {
    /** Read from the server's cook log by the last complete sync. */
    SYNCED,

    /** Logged on this device and queued in the outbox (`COOK_LOG`); not on the server yet. */
    PENDING,

    /**
     * Sent by this device (the server answered 201), not yet seen in a complete server list.
     * The next list replaces it with the server's copy, or removes it when the list (fetched
     * after the send) no longer has it.
     */
    SENT,

    /** The server rejected it (400); its outbox row is FAILED and shown in "Pending changes". */
    FAILED,

    /** Never sent: the recipe was deleted (on the server or from the index). */
    DROPPED,
}

/** Values for [CookLogEntity.problem]. */
object CookLogProblems {
    /** The recipe left the recipe index before the entry was sent; the outbox row went with it. */
    const val RECIPE_DELETED: String = "recipe_deleted"

    /** The recipe was no longer stored when the outbox came to the entry. */
    const val RECIPE_GONE: String = "recipe_gone"

    /** `POST /api/cook-log/` answered 404 (the recipe was deleted on the server). */
    const val NOT_FOUND: String = "not_found"
}
