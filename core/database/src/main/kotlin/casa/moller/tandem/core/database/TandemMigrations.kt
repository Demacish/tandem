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

    /**
     * 0.2.0 (fix-ui): the step's photo (`step.image_url`, cook mode's "Stegbild om den finns").
     * Stored steps get NULL. A detail stored by 0.1.0 whose server JSON has a step photo (a step
     * `file` with a non-empty `preview`, FACTS "Fas 1 m") is marked for a new fetch with
     * [MARK_DETAILS_WITH_STEP_PHOTOS]: the next sync fetches it again (`RecipeDao.getIdsMarkedForRefetch`)
     * and the normal mapping fills the column. The URL cannot be filled here: it goes through
     * `MediaUrls.resolve` with the account's base URL, which the database does not know.
     */
    val MIGRATION_2_3: Migration = object : Migration(2, 3) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE `step` ADD COLUMN `image_url` TEXT")
            connection.execSQL(MARK_DETAILS_WITH_STEP_PHOTOS)
        }
    }

    /**
     * Marks stored details whose raw JSON has a step file with a non-empty `preview` (an image;
     * other files have "") for a new fetch: `fetched_at = NULL` with `raw_json` kept, which only
     * this migration writes (a removed detail clears both). `raw_json` is kotlinx.serialization's
     * compact form (`"preview":"https://…"`); the spaced form is matched too. `_` is one character
     * of any kind, so `"preview":""` does not match.
     */
    const val MARK_DETAILS_WITH_STEP_PHOTOS: String =
        "UPDATE `recipe_detail` SET `fetched_at` = NULL WHERE `raw_json` IS NOT NULL AND " +
            "(`raw_json` LIKE '%\"preview\":\"_%' OR `raw_json` LIKE '%\"preview\": \"_%')"

    val ALL: List<Migration> = listOf(MIGRATION_1_2, MIGRATION_2_3)
}
