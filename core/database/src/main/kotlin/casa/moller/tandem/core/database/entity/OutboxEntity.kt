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
 * @property refs (schema 4) which payload fields hold ids of other objects that may still be
 *   temporary (negative): a JSON object `{"<field or dotted path>": "<entity>"}`, e.g.
 *   `{"list_recipe": "shopping_list_recipe", "meal_type.id": "meal_type"}`; an array field (`ids`)
 *   has each element resolved. The sender replaces negative ids through `id_mapping`
 *   ([casa.moller.tandem.core.database.outbox.OutboxRefs]); null = no references.
 * @property batchKey (schema 4) rows with the same key may be sent in one request
 *   (`BULK_CHECK`: `shopping_entry:checked=true`), see `OutboxDao.nextReadyBatch`; null = alone.
 * @property updatedAtEpochMillis (schema 4) when a later change was merged into this row, if ever.
 */
@Entity(
    tableName = "outbox",
    indices = [
        Index(value = ["account_id", "state"]),
        Index(value = ["account_id", "entity", "server_id"]),
        Index(value = ["depends_on"]),
        Index(value = ["account_id", "batch_key"]),
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
    @ColumnInfo(name = "refs") val refs: String? = null,
    @ColumnInfo(name = "batch_key") val batchKey: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAtEpochMillis: Long? = null,
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

/**
 * Values for [OutboxEntity.op] (plan 5.7). Phase 1 sends [VIEW_LOG] and (from 0.2.0) [COOK_LOG];
 * Phase 2 (0.3.0) sends [CREATE], [PATCH], [DELETE] and [BULK_CHECK] for the entities in
 * [OutboxEntities] and [RECIPE_KEYWORDS] ("Vill laga"). The table of every (op, entity) pair, its
 * request, references and merge rule is in docs/BYGGE.md "Fas 2 – kontrakt".
 */
object OutboxOps {
    const val CREATE: String = "CREATE"
    const val PATCH: String = "PATCH"
    const val DELETE: String = "DELETE"
    const val BULK_CHECK: String = "BULK_CHECK"

    /**
     * Phase 2: add or remove keywords on a recipe (`PATCH /api/recipe/{id}/` with the WHOLE keyword
     * list, read fresh before sending; "Vill laga", plan 3.7 B). Payload `{"add": ["vill-laga"],
     * "remove": [<keyword id>]}`; entity [OutboxEntities.RECIPE], `server_id` = recipe id.
     */
    const val RECIPE_KEYWORDS: String = "RECIPE_KEYWORDS"

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

    // --- Phase 2 (schema 4) ---

    /** A group of entries from one recipe or meal (`/api/shopping-list-recipe/`). */
    const val SHOPPING_LIST_RECIPE: String = "shopping_list_recipe"

    /** A meal type (`/api/meal-type/`), created from the plan-meal sheet. */
    const val MEAL_TYPE: String = "meal_type"

    /** A recipe book (`/api/recipe-book/`), e.g. a person's "Favoriter". */
    const val RECIPE_BOOK: String = "recipe_book"

    /** A recipe in a book (`/api/recipe-book-entry/`). */
    const val RECIPE_BOOK_ENTRY: String = "recipe_book_entry"

    /** A food (`PATCH /api/food/{id}/`: aisle `supermarket_category`, `food_onhand`). */
    const val FOOD: String = "food"

    /** A store's aisle order (`PATCH /api/supermarket-category-relation/{id}/` `order`). */
    const val SUPERMARKET_CATEGORY_RELATION: String = "supermarket_category_relation"

    /** The user's Tandoor preferences (`PATCH /api/user-preference/{user}/`); `server_id` = user id. */
    const val USER_PREFERENCE: String = "user_preference"
}
