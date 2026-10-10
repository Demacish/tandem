package casa.moller.tandem.core.database

import androidx.room3.Database
import androidx.room3.RoomDatabase
import casa.moller.tandem.core.database.dao.SyncStateDao
import casa.moller.tandem.core.database.entity.SyncStateEntity

/**
 * The app's Room 3 database (plan 5.7). Every table has `account_id` as the first part of its key
 * (plan 5.3, decision B14: one active account per device, switching later).
 *
 * Phase 1 foundation: only `sync_state` exists so the module, KSP, the schema export and the CI
 * schema check are wired. The database lane adds the recipe, step, ingredient, keyword, food,
 * unit, meal plan, outbox and FTS tables of plan 5.7. Until the first release that ships the
 * database (0.1.0) the version stays 1 and `schemas/.../1.json` is simply regenerated; after that
 * every change needs a new version, a migration and a migration test.
 */
@Database(
    entities = [SyncStateEntity::class],
    version = TandemDatabase.VERSION,
    exportSchema = true,
)
abstract class TandemDatabase : RoomDatabase() {
    abstract fun syncStateDao(): SyncStateDao

    companion object {
        const val VERSION: Int = 1

        /** File name in the app's database folder (excluded from backup, data_extraction_rules.xml). */
        const val FILE_NAME: String = "tandem.db"
    }
}
