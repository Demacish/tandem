package casa.moller.tandem.core.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index

// Phase 2 (schema 4, grund lane): recipe books, favourites per person and "Vill laga" (plan 3.7,
// 6.2 "Böcker/Favoriter", feature #17). Same keys as the shopping list: server rows have
// `local_id = server_id`, an offline-created book or entry a negative `local_id` until sent.

/**
 * A recipe book (Tandoor `RecipeBook`). Only the owner may write to it (plan 3.7: the server
 * answers 403 to a shared member, FACTS "Fas 2").
 *
 * @property createdBy the owner's user id (`created_by.id`).
 * @property createdByName the owner's display name ("Favoriter (Anna)").
 * @property sharedUserIds JSON array of the user ids it is shared with (read-only for them).
 * @property filterId the `CustomFilter` of a smart book, null for a plain book.
 * @property filterSearch that filter's `search` JSON (e.g. `{"keywords_or":[8]}`), if any.
 * @property bookOrder the server's `order`.
 * @property kind how the app treats the book, see [RecipeBookKinds].
 */
@Entity(
    tableName = "recipe_book",
    primaryKeys = ["account_id", "local_id"],
    indices = [Index(value = ["account_id", "server_id"], unique = true)],
)
data class RecipeBookEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "local_id") val localId: Long,
    @ColumnInfo(name = "server_id") val serverId: Long?,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "description") val description: String = "",
    @ColumnInfo(name = "created_by") val createdBy: Long? = null,
    @ColumnInfo(name = "created_by_name") val createdByName: String? = null,
    @ColumnInfo(name = "shared_user_ids") val sharedUserIds: String = "[]",
    @ColumnInfo(name = "filter_id") val filterId: Long? = null,
    @ColumnInfo(name = "filter_search") val filterSearch: String? = null,
    @ColumnInfo(name = "book_order") val bookOrder: Int = 0,
    @ColumnInfo(name = "kind") val kind: String = RecipeBookKinds.BOOK,
)

/** Values of [RecipeBookEntity.kind] (stored as text). */
object RecipeBookKinds {
    /** A plain book with entries. */
    const val BOOK: String = "BOOK"

    /** A smart book: its recipes come from `GET /api/recipe/?filter=<filterId>`, it has no entries. */
    const val SMART: String = "SMART"

    /** A person's "Favoriter" book (plan 3.7 alternative A: one book per person, merged in the view). */
    const val FAVORITES: String = "FAVORITES"

    /** The "Vill laga" smart book on the keyword (plan 3.7 alternative B). */
    const val WANT_TO_COOK: String = "WANT_TO_COOK"
}

/** A recipe in a book (Tandoor `RecipeBookEntry`). [bookId] is the book's server or local id. */
@Entity(
    tableName = "recipe_book_entry",
    primaryKeys = ["account_id", "local_id"],
    indices = [
        Index(value = ["account_id", "server_id"], unique = true),
        Index(value = ["account_id", "book_id"]),
        Index(value = ["account_id", "recipe_id"]),
    ],
)
data class RecipeBookEntryEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "local_id") val localId: Long,
    @ColumnInfo(name = "server_id") val serverId: Long?,
    @ColumnInfo(name = "book_id") val bookId: Long,
    @ColumnInfo(name = "recipe_id") val recipeId: Long,
)

/**
 * A temporary id that got its server id (plan 5.7 "id-omskrivning"): written by
 * `OutboxDao.completeCreate` in the same transaction that re-keys the local row. A screen or a
 * navigation key that still holds the temporary (negative) id resolves it here; the sender uses it
 * for payload references ([OutboxEntity.refs]).
 *
 * @property entity an [OutboxEntities] value.
 */
@Entity(tableName = "id_mapping", primaryKeys = ["account_id", "entity", "local_id"])
data class IdMappingEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "entity") val entity: String,
    @ColumnInfo(name = "local_id") val localId: Long,
    @ColumnInfo(name = "server_id") val serverId: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
