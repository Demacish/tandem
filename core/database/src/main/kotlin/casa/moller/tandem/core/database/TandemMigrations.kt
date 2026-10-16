package casa.moller.tandem.core.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Every migration of [TandemDatabase], in version order. Version 1 shipped with 0.1.0 and its
 * schema is frozen. Each schema change adds `MIGRATION_<n>_<n+1>` here, bumps
 * [TandemDatabase.VERSION], commits the new `schemas/.../<n+1>.json` (from CI's `room-schemas`
 * artifact) and adds a case to `TandemMigrationTest` (create at n with `MigrationTestHelper`,
 * insert rows, migrate and validate, check the rows). Destructive fallback is never enabled:
 * offline changes in the outbox must survive an app update.
 */
object TandemMigrations {
    /**
     * 0.1.0 -> 0.2.0: the `cook_log` table ("Klart!", feature #8). Nothing else changes; the
     * table starts empty and the next sync fills it (`CookLogDao.isComplete` is false until then).
     * The SQL is Room's own for `CookLogEntity` (see `schemas/.../2.json`).
     */
    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `cook_log` (`account_id` TEXT NOT NULL, `local_id` INTEGER NOT NULL, " +
                    "`server_id` INTEGER, `recipe_id` INTEGER NOT NULL, `servings` INTEGER, `rating` INTEGER, `comment` TEXT, " +
                    "`created_by` INTEGER, `cooked_at` INTEGER, `state` TEXT NOT NULL, `outbox_id` INTEGER, `sent_at` INTEGER, " +
                    "`problem` TEXT, " +
                    "PRIMARY KEY(`account_id`, `local_id`))",
            )
            connection.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_cook_log_account_id_server_id` ON `cook_log` (`account_id`, `server_id`)",
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_cook_log_account_id_recipe_id` ON `cook_log` (`account_id`, `recipe_id`)",
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_cook_log_account_id_outbox_id` ON `cook_log` (`account_id`, `outbox_id`)",
            )
        }
    }

    val ALL: List<Migration> = listOf(MIGRATION_1_2)
}
