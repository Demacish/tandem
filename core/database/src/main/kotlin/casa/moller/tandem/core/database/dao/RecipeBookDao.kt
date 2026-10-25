package casa.moller.tandem.core.database.dao

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import casa.moller.tandem.core.database.entity.KeywordEntity
import casa.moller.tandem.core.database.entity.OutboxEntity
import casa.moller.tandem.core.database.entity.RecipeBookEntity
import casa.moller.tandem.core.database.entity.RecipeBookEntryEntity
import casa.moller.tandem.core.database.entity.RecipeKeywordCrossRef
import casa.moller.tandem.core.database.query.RecipeSql
import casa.moller.tandem.core.database.relation.RecipeListItem
import kotlinx.coroutines.flow.Flow

/**
 * Recipe books and their entries (Phase 2, schema 4; plan 3.7, feature #17). The grund lane's
 * skeleton plus the books lane's queries (docs/BYGGE.md "Böcker, favoriter och Vill laga").
 *
 * Keys as everywhere in Phase 2: a server row has `local_id = server_id`; a book or entry created
 * offline has a negative `local_id` and `server_id = null` until its CREATE is answered
 * (`IdRewriteDao.completeCreate` re-keys it and rewrites `recipe_book_entry.book_id`).
 */
@Dao
abstract class RecipeBookDao {
    @Query("SELECT * FROM recipe_book WHERE account_id = :accountId ORDER BY book_order, name, local_id")
    abstract fun observeBooks(accountId: String): Flow<List<RecipeBookEntity>>

    @Query("SELECT * FROM recipe_book_entry WHERE account_id = :accountId AND book_id = :bookId ORDER BY local_id")
    abstract fun observeEntries(accountId: String, bookId: Long): Flow<List<RecipeBookEntryEntity>>

    /** The books (local ids) recipe [recipeId] is in. */
    @Query("SELECT book_id FROM recipe_book_entry WHERE account_id = :accountId AND recipe_id = :recipeId")
    abstract fun observeBooksOf(accountId: String, recipeId: Long): Flow<List<Long>>

    @Upsert
    abstract suspend fun upsertBooks(rows: List<RecipeBookEntity>)

    @Upsert
    abstract suspend fun upsertEntries(rows: List<RecipeBookEntryEntity>)

    @Query("DELETE FROM recipe_book WHERE account_id = :accountId AND local_id IN (:localIds)")
    abstract suspend fun deleteBooks(accountId: String, localIds: List<Long>)

    @Query("DELETE FROM recipe_book_entry WHERE account_id = :accountId AND local_id IN (:localIds)")
    abstract suspend fun deleteEntries(accountId: String, localIds: List<Long>)

    // ------------------------------------------------------------------ books lane (Phase 2)

    /** Runs [block] in one transaction; other DAOs called inside join it (Room binds it to the coroutine). */
    @Transaction
    open suspend fun inTransaction(block: suspend () -> Unit) {
        block()
    }

    @Query("SELECT * FROM recipe_book WHERE account_id = :accountId ORDER BY book_order, name, local_id")
    abstract suspend fun getBooks(accountId: String): List<RecipeBookEntity>

    @Query("SELECT * FROM recipe_book WHERE account_id = :accountId AND local_id = :localId")
    abstract suspend fun getBook(accountId: String, localId: Long): RecipeBookEntity?

    /** Every entry of the account (book sizes, the favourites of everyone, a recipe's books). */
    @Query("SELECT * FROM recipe_book_entry WHERE account_id = :accountId ORDER BY local_id")
    abstract fun observeAllEntries(accountId: String): Flow<List<RecipeBookEntryEntity>>

    @Query("SELECT * FROM recipe_book_entry WHERE account_id = :accountId AND book_id = :bookId AND recipe_id = :recipeId")
    abstract suspend fun findEntries(accountId: String, bookId: Long, recipeId: Long): List<RecipeBookEntryEntity>

    @Query("SELECT * FROM recipe_book_entry WHERE account_id = :accountId AND local_id = :localId")
    abstract suspend fun getEntry(accountId: String, localId: Long): RecipeBookEntryEntity?

    /**
     * The stored list entries of [recipeIds] (recipes the index removed, or never read, are left
     * out), in no particular order; the caller sorts.
     */
    @Query(RecipeSql.SELECT_ITEMS + "WHERE o.account_id = :accountId AND o.server_id IN (:recipeIds)")
    abstract fun observeListItems(accountId: String, recipeIds: List<Long>): Flow<List<RecipeListItem>>

    /** Every recipe-keyword link of the account (smart books evaluated on the device, "Vill laga"). */
    @Query("SELECT recipe_id, keyword_id FROM recipe_keyword WHERE account_id = :accountId")
    abstract fun observeRecipeKeywords(accountId: String): Flow<List<RecipeKeywordRow>>

    /** Ids of the keywords named [name] (lower case, e.g. `vill-laga`). */
    @Query("SELECT id FROM keyword WHERE account_id = :accountId AND lower(trim(name)) = :name")
    abstract fun observeKeywordIdsNamed(accountId: String, name: String): Flow<List<Long>>

    @Query("SELECT id FROM keyword WHERE account_id = :accountId AND lower(trim(name)) = :name")
    abstract suspend fun getKeywordIdsNamed(accountId: String, name: String): List<Long>

    /** The queued book, entry and keyword changes of the account (pending state, offline overlay). */
    @Query(
        "SELECT * FROM outbox WHERE account_id = :accountId AND (entity IN ('recipe_book', 'recipe_book_entry') " +
            "OR op = 'RECIPE_KEYWORDS') ORDER BY id",
    )
    abstract fun observeOutbox(accountId: String): Flow<List<OutboxEntity>>

    @Query(
        "SELECT * FROM outbox WHERE account_id = :accountId AND (entity IN ('recipe_book', 'recipe_book_entry') " +
            "OR op = 'RECIPE_KEYWORDS') ORDER BY id",
    )
    abstract suspend fun getOutbox(accountId: String): List<OutboxEntity>

    /**
     * A full re-read of the books and entries (plan 3.5 "Små tabeller"): every server row is
     * replaced by [books] and [entries]; rows created on this device (no server id yet) stay.
     * The caller applies queued deletes again afterwards, in the same transaction.
     */
    @Transaction
    open suspend fun replaceServerRows(accountId: String, books: List<RecipeBookEntity>, entries: List<RecipeBookEntryEntity>) {
        deleteServerEntries(accountId)
        deleteServerBooks(accountId)
        if (books.isNotEmpty()) upsertBooks(books)
        if (entries.isNotEmpty()) upsertEntries(entries)
    }

    @Query("DELETE FROM recipe_book WHERE account_id = :accountId AND server_id IS NOT NULL")
    abstract suspend fun deleteServerBooks(accountId: String)

    @Query("DELETE FROM recipe_book_entry WHERE account_id = :accountId AND server_id IS NOT NULL")
    abstract suspend fun deleteServerEntries(accountId: String)

    /** Removes a book with its entries (an own book deleted here, or one that is gone on the server). */
    @Transaction
    open suspend fun deleteBookWithEntries(accountId: String, localId: Long) {
        deleteEntriesOfBook(accountId, localId)
        deleteBooks(accountId, listOf(localId))
    }

    @Query("DELETE FROM recipe_book_entry WHERE account_id = :accountId AND book_id = :bookId")
    abstract suspend fun deleteEntriesOfBook(accountId: String, bookId: Long)

    /** Demo mode: the books are seeded anew (the demo database has no server rows to keep). */
    @Query("DELETE FROM recipe_book WHERE account_id = :accountId")
    abstract suspend fun deleteAllBooks(accountId: String)

    @Query("DELETE FROM recipe_book_entry WHERE account_id = :accountId")
    abstract suspend fun deleteAllEntries(accountId: String)

    // Demo mode only ("Vill laga" without a server): the keyword and its links are written directly.

    @Query("SELECT IFNULL(MAX(id), 0) FROM keyword WHERE account_id = :accountId")
    abstract suspend fun getMaxKeywordId(accountId: String): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertKeywordIfAbsent(row: KeywordEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertKeywordRef(row: RecipeKeywordCrossRef)

    @Query("DELETE FROM recipe_keyword WHERE account_id = :accountId AND recipe_id = :recipeId AND keyword_id IN (:keywordIds)")
    abstract suspend fun deleteKeywordRefs(accountId: String, recipeId: Long, keywordIds: List<Long>)
}

/** One `recipe_keyword` link. */
data class RecipeKeywordRow(
    @ColumnInfo(name = "recipe_id") val recipeId: Long,
    @ColumnInfo(name = "keyword_id") val keywordId: Long,
)
