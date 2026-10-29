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

    // --- Fas S (schema 5, grund lane; docs/BYGGE.md "Fas S – kontrakt", table "Outbox") ---

    /**
     * "Har hemma" for many foods at once (staples, "Packa upp"): `PUT /api/food/batch_update/`
     * `{foods: [ids], on_hand}`. Entity [OutboxEntities.FOOD], one row per food (`server_id` = food
     * id), payload `{"on_hand": true}`, `base_snapshot` `{"on_hand": <before>}`, batch key
     * `food:on_hand=true|false` (`OutboxDao.nextReadyBatch` → one request). Merged like
     * [BULK_CHECK]: the last value wins, back to the base cancels. One food alone may use PATCH
     * `food_onhand` (Phase 2) instead.
     */
    const val BATCH_ON_HAND: String = "BATCH_ON_HAND"

    /**
     * A drink's photo: `PUT /api/recipe/{id}/image/` (multipart `image`). Entity
     * [OutboxEntities.DRINK]; queued with the drink's temporary `local_id` and `depends_on` its
     * CREATE (the id rewrite then gives it `server_id`). Payload `{"file": "<app-private path>"}`;
     * a newer photo replaces the payload of an unsent one.
     */
    const val RECIPE_IMAGE: String = "RECIPE_IMAGE"
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

    // --- Fas S (schema 5) ---

    /** A storage place (`/api/inventory-location/`): CREATE/PATCH/DELETE, offline creates (temporary ids). */
    const val INVENTORY_LOCATION: String = "inventory_location"

    /**
     * A stock entry (`/api/inventory-entry/`): CREATE/PATCH/DELETE, offline creates. A payload's
     * `inventory_location` holds the location's id (refs `{"inventory_location": "inventory_location"}`
     * while it is temporary); the sender expands it to the whole location object (FACTS "Fas S b").
     */
    const val INVENTORY_ENTRY: String = "inventory_entry"

    /** A learned barcode or receipt name: a `FOOD_ALIAS` automation (`/api/automation/`), CREATE/PATCH/DELETE. */
    const val FOOD_ALIAS: String = "food_alias"

    /**
     * A keyword (`POST /api/keyword/`, then `PUT /api/keyword/{id}/move/{parent}/` when it has a
     * parent: POST ignores `parent`, FACTS "Fas S c"): "Passar med" and its children, drink types.
     * Offline creates use a negative `keyword.id`.
     */
    const val KEYWORD: String = "keyword"

    /**
     * A drink (#43, B20): CREATE = `POST /api/recipe/` `{name, description, keywords:[{name}],
     * servings, steps: []}` from a `drink_draft` row; [OutboxOps.RECIPE_IMAGE] its photo. Once the
     * drink exists it is an ordinary recipe ([RECIPE]).
     */
    const val DRINK: String = "drink"
}

/**
 * Fas S: the top-level fields of a [OutboxOps.RECIPE_KEYWORDS] payload. Fields merge one by one
 * (`OutboxMerge` merges payloads at the top level), so "Vill laga" and every pairing toggle of the
 * same recipe live side by side in one unsent row:
 *
 * - `"keywords": {"vill-laga": true}`: Phase 2's want-to-cook (unchanged);
 * - `"keyword:<id>": true|false`: add/remove an existing keyword by its server id (pairings and
 *   drink types: the link hangs on the id, so a rename does not break it, plan §8.8);
 * - `"pairing:<name>": true`: add the pairing keyword `<name>` under "Passar med", which the
 *   sender creates and moves first when the server has none.
 *
 * `base_snapshot` holds the same fields with the values before the change. The recipe is read
 * fresh and the WHOLE keyword list is PATCHed (a PATCH replaces the list, FACTS "Fas S c").
 */
object RecipeKeywordFields {
    const val WANT_TO_COOK: String = "keywords"
    const val KEYWORD_PREFIX: String = "keyword:"
    const val PAIRING_PREFIX: String = "pairing:"

    fun keyword(id: Long): String = KEYWORD_PREFIX + id

    fun pairing(name: String): String = PAIRING_PREFIX + name

    /** The keyword id of a `keyword:<id>` field, null for any other field. */
    fun keywordIdOf(field: String): Long? = field.takeIf { it.startsWith(KEYWORD_PREFIX) }?.removePrefix(KEYWORD_PREFIX)?.toLongOrNull()

    /** The name of a `pairing:<name>` field, null for any other field. */
    fun pairingNameOf(field: String): String? = field.takeIf { it.startsWith(PAIRING_PREFIX) }?.removePrefix(PAIRING_PREFIX)
}
