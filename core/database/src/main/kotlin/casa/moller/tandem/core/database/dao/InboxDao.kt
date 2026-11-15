package casa.moller.tandem.core.database.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import casa.moller.tandem.core.database.entity.InboxEntryEntity
import kotlinx.coroutines.flow.Flow

/**
 * Fas 5 (schema 7, rss lane): the recipe inbox's cached posts ([InboxEntryEntity]). Main-safe.
 *
 * A post is shown while it is not dismissed and either unread at the last refresh or imported
 * here. [applyRefresh] replaces the unread set with what the server answered and keeps the local
 * marks (imported, dismissed, "read" still to send).
 */
@Dao
abstract class InboxDao {
    /** The posts to show, newest first (posts without a date last). */
    @Query(
        "SELECT * FROM inbox_entry WHERE account_id = :accountId AND dismissed_at IS NULL " +
            "AND (unread = 1 OR imported_at IS NOT NULL) " +
            "ORDER BY published_at IS NULL, published_at DESC, entry_id DESC",
    )
    abstract fun observeVisible(accountId: String): Flow<List<InboxEntryEntity>>

    @Query("SELECT * FROM inbox_entry WHERE account_id = :accountId AND entry_id = :entryId")
    abstract fun observe(accountId: String, entryId: Long): Flow<InboxEntryEntity?>

    @Query("SELECT * FROM inbox_entry WHERE account_id = :accountId AND entry_id = :entryId")
    abstract suspend fun get(accountId: String, entryId: Long): InboxEntryEntity?

    @Query("SELECT * FROM inbox_entry WHERE account_id = :accountId")
    abstract suspend fun getAll(accountId: String): List<InboxEntryEntity>

    /** The posts whose "read" still has to go to Miniflux. */
    @Query("SELECT entry_id FROM inbox_entry WHERE account_id = :accountId AND read_pending = 1 ORDER BY entry_id")
    abstract suspend fun getReadPending(accountId: String): List<Long>

    @Upsert
    abstract suspend fun upsert(rows: List<InboxEntryEntity>)

    @Query("UPDATE inbox_entry SET dismissed_at = :atMillis, read_pending = 1 WHERE account_id = :accountId AND entry_id = :entryId")
    abstract suspend fun dismiss(accountId: String, entryId: Long, atMillis: Long)

    @Query(
        "UPDATE inbox_entry SET imported_at = :atMillis, read_pending = CASE WHEN :markRead THEN 1 ELSE read_pending END " +
            "WHERE account_id = :accountId AND entry_id = :entryId",
    )
    abstract suspend fun markImported(accountId: String, entryId: Long, atMillis: Long, markRead: Boolean)

    /** Miniflux took the "read" of [entryIds]. */
    @Query("UPDATE inbox_entry SET read_pending = 0, unread = 0 WHERE account_id = :accountId AND entry_id IN (:entryIds)")
    abstract suspend fun readSent(accountId: String, entryIds: List<Long>)

    /**
     * One refresh's answer: [fresh] are the unread posts of the chosen categories now. Their local
     * marks are kept; posts that are no longer unread are kept only while they are imported here
     * (within [keepImportedSinceMillis]) or still have a "read" to send; everything else goes.
     */
    @Transaction
    open suspend fun applyRefresh(accountId: String, fresh: List<InboxEntryEntity>, keepImportedSinceMillis: Long) {
        val stored = getAll(accountId).associateBy { it.entryId }
        val merged = fresh.map { row ->
            val old = stored[row.entryId] ?: return@map row
            row.copy(importedAt = old.importedAt, dismissedAt = old.dismissedAt, readPending = old.readPending)
        }
        val freshIds = fresh.mapTo(HashSet()) { it.entryId }
        val gone = stored.values.filter { it.entryId !in freshIds }
        val keep = gone.filter { old ->
            old.readPending || (old.importedAt != null && old.importedAt >= keepImportedSinceMillis && old.dismissedAt == null)
        }
        val delete = gone.filter { it !in keep }.map { it.entryId }
        if (delete.isNotEmpty()) deleteEntries(accountId, delete)
        upsert(merged + keep.map { it.copy(unread = false) })
    }

    @Query("DELETE FROM inbox_entry WHERE account_id = :accountId AND entry_id IN (:entryIds)")
    abstract suspend fun deleteEntries(accountId: String, entryIds: List<Long>)

    /** Everything of the account's inbox ("Koppla från"; logout goes through `AccountDao.deleteAccountData`). */
    @Query("DELETE FROM inbox_entry WHERE account_id = :accountId")
    abstract suspend fun deleteAccount(accountId: String)
}
