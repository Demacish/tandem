package casa.moller.tandem.core.database.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import casa.moller.tandem.core.database.entity.RecipeBookEntity
import casa.moller.tandem.core.database.entity.RecipeBookEntryEntity
import kotlinx.coroutines.flow.Flow

/** Recipe books and their entries (Phase 2, schema 4; plan 3.7, feature #17). Skeleton: the books lane adds queries. */
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
}
