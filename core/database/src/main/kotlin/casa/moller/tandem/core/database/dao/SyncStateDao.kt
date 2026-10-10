package casa.moller.tandem.core.database.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import casa.moller.tandem.core.database.entity.SyncStateEntity
import kotlinx.coroutines.flow.Flow

/** Read and write [SyncStateEntity] rows. All functions are main-safe (Room runs them on its own threads). */
@Dao
interface SyncStateDao {
    @Upsert
    suspend fun upsert(state: SyncStateEntity)

    @Query("SELECT * FROM sync_state WHERE account_id = :accountId AND entity = :entity")
    suspend fun get(accountId: String, entity: String): SyncStateEntity?

    @Query("SELECT * FROM sync_state WHERE account_id = :accountId ORDER BY entity")
    fun observe(accountId: String): Flow<List<SyncStateEntity>>

    @Query("DELETE FROM sync_state WHERE account_id = :accountId")
    suspend fun deleteAccount(accountId: String)
}
