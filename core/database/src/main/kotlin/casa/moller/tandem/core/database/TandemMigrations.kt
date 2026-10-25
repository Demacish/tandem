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
     * compact form (`"preview":"https://…"`); the spaced form is matched too. GLOB, not LIKE: the
     * character after the opening quote must not be a quote (`[^"]`), so `"preview":""` (a file
     * that is not an image) does not match.
     */
    const val MARK_DETAILS_WITH_STEP_PHOTOS: String =
        "UPDATE `recipe_detail` SET `fetched_at` = NULL WHERE `raw_json` IS NOT NULL AND " +
            "(`raw_json` GLOB '*\"preview\":\"[^\"]*' OR `raw_json` GLOB '*\"preview\": \"[^\"]*')"

    /**
     * 0.2.0 -> 0.3.0 (Phase 2 "handla & planera"): the ONE migration of the whole phase (docs/BYGGE.md
     * "Fas 2 – kontrakt"). New tables: the shopping list (`shopping_entry`, `shopping_list_recipe`,
     * `shopping_list`, `shopping_history`), stores (`supermarket`, `supermarket_category`,
     * `supermarket_category_relation`), recipe books (`recipe_book`, `recipe_book_entry`) and the
     * temporary-id map (`id_mapping`). New columns: `outbox.refs`/`batch_key`/`updated_at`,
     * `server_account.household_name`/`shopping_min_autosync_seconds`, `meal_plan.recipe_name`,
     * `sync_state.delta_count`. Existing rows are untouched; the new tables fill with the next sync.
     * The SQL is Room's own for the entities (see `schemas/.../4.json`).
     */
    val MIGRATION_3_4: Migration = object : Migration(3, 4) {
        override suspend fun migrate(connection: SQLiteConnection) {
            SCHEMA_4_STATEMENTS.forEach { connection.execSQL(it) }
        }
    }

    /** The statements of [MIGRATION_3_4], in order. */
    internal val SCHEMA_4_STATEMENTS: List<String> = listOf(
        "ALTER TABLE `outbox` ADD COLUMN `refs` TEXT",
        "ALTER TABLE `outbox` ADD COLUMN `batch_key` TEXT",
        "ALTER TABLE `outbox` ADD COLUMN `updated_at` INTEGER",
        "CREATE INDEX IF NOT EXISTS `index_outbox_account_id_batch_key` ON `outbox` (`account_id`, `batch_key`)",
        "ALTER TABLE `server_account` ADD COLUMN `household_name` TEXT",
        "ALTER TABLE `server_account` ADD COLUMN `shopping_min_autosync_seconds` INTEGER",
        "ALTER TABLE `meal_plan` ADD COLUMN `recipe_name` TEXT",
        "ALTER TABLE `sync_state` ADD COLUMN `delta_count` INTEGER NOT NULL DEFAULT 0",
        "CREATE TABLE IF NOT EXISTS `shopping_entry` (`account_id` TEXT NOT NULL, `local_id` INTEGER NOT NULL, " +
            "`server_id` INTEGER, `food_id` INTEGER, `food_name` TEXT NOT NULL, `food_plural_name` TEXT, `category_id` INTEGER, " +
            "`unit_id` INTEGER, `unit_name` TEXT, `unit_plural_name` TEXT, `amount` REAL NOT NULL, `entry_order` INTEGER NOT NULL, " +
            "`checked` INTEGER NOT NULL, `completed_at` INTEGER, `delay_until` INTEGER, `created_at` INTEGER, `updated_at` INTEGER, " +
            "`created_by` INTEGER, `created_by_name` TEXT, `list_recipe_id` INTEGER, `ingredient_id` INTEGER, `meal_plan_id` INTEGER, " +
            "`recipe_id` INTEGER, `recipe_name` TEXT, `shopping_list_ids` TEXT NOT NULL, `fetched_at` INTEGER NOT NULL, " +
            "PRIMARY KEY(`account_id`, `local_id`))",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_shopping_entry_account_id_server_id` ON `shopping_entry` (`account_id`, `server_id`)",
        "CREATE INDEX IF NOT EXISTS `index_shopping_entry_account_id_list_recipe_id` ON `shopping_entry` (`account_id`, `list_recipe_id`)",
        "CREATE INDEX IF NOT EXISTS `index_shopping_entry_account_id_meal_plan_id` ON `shopping_entry` (`account_id`, `meal_plan_id`)",
        "CREATE INDEX IF NOT EXISTS `index_shopping_entry_account_id_checked` ON `shopping_entry` (`account_id`, `checked`)",
        "CREATE TABLE IF NOT EXISTS `shopping_list_recipe` (`account_id` TEXT NOT NULL, `local_id` INTEGER NOT NULL, " +
            "`server_id` INTEGER, `name` TEXT NOT NULL, `recipe_id` INTEGER, `recipe_name` TEXT, `meal_plan_id` INTEGER, " +
            "`servings` REAL NOT NULL, `created_by` INTEGER, PRIMARY KEY(`account_id`, `local_id`))",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_shopping_list_recipe_account_id_server_id` ON `shopping_list_recipe` " +
            "(`account_id`, `server_id`)",
        "CREATE INDEX IF NOT EXISTS `index_shopping_list_recipe_account_id_meal_plan_id` ON `shopping_list_recipe` " +
            "(`account_id`, `meal_plan_id`)",
        "CREATE TABLE IF NOT EXISTS `shopping_list` (`account_id` TEXT NOT NULL, `id` INTEGER NOT NULL, `name` TEXT NOT NULL, " +
            "`description` TEXT NOT NULL, `color` TEXT, PRIMARY KEY(`account_id`, `id`))",
        "CREATE TABLE IF NOT EXISTS `shopping_history` (`account_id` TEXT NOT NULL, `entry_id` INTEGER NOT NULL, `food_id` INTEGER, " +
            "`food_name` TEXT NOT NULL, `unit_id` INTEGER, `unit_name` TEXT, `amount` REAL NOT NULL, `category_id` INTEGER, " +
            "`recipe_name` TEXT, `completed_at` INTEGER, `archived_at` INTEGER NOT NULL, PRIMARY KEY(`account_id`, `entry_id`))",
        "CREATE INDEX IF NOT EXISTS `index_shopping_history_account_id_food_name` ON `shopping_history` (`account_id`, `food_name`)",
        "CREATE INDEX IF NOT EXISTS `index_shopping_history_account_id_completed_at` ON `shopping_history` (`account_id`, `completed_at`)",
        "CREATE TABLE IF NOT EXISTS `supermarket` (`account_id` TEXT NOT NULL, `id` INTEGER NOT NULL, `name` TEXT NOT NULL, " +
            "`description` TEXT, `shopping_list_ids` TEXT NOT NULL, PRIMARY KEY(`account_id`, `id`))",
        "CREATE TABLE IF NOT EXISTS `supermarket_category` (`account_id` TEXT NOT NULL, `id` INTEGER NOT NULL, `name` TEXT NOT NULL, " +
            "`description` TEXT, PRIMARY KEY(`account_id`, `id`))",
        "CREATE TABLE IF NOT EXISTS `supermarket_category_relation` (`account_id` TEXT NOT NULL, `id` INTEGER NOT NULL, " +
            "`supermarket_id` INTEGER NOT NULL, `category_id` INTEGER NOT NULL, `relation_order` INTEGER NOT NULL, " +
            "PRIMARY KEY(`account_id`, `id`))",
        "CREATE INDEX IF NOT EXISTS `index_supermarket_category_relation_account_id_supermarket_id` ON " +
            "`supermarket_category_relation` (`account_id`, `supermarket_id`)",
        "CREATE TABLE IF NOT EXISTS `recipe_book` (`account_id` TEXT NOT NULL, `local_id` INTEGER NOT NULL, `server_id` INTEGER, " +
            "`name` TEXT NOT NULL, `description` TEXT NOT NULL, `created_by` INTEGER, `created_by_name` TEXT, " +
            "`shared_user_ids` TEXT NOT NULL, `filter_id` INTEGER, `filter_search` TEXT, `book_order` INTEGER NOT NULL, " +
            "`kind` TEXT NOT NULL, PRIMARY KEY(`account_id`, `local_id`))",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_recipe_book_account_id_server_id` ON `recipe_book` (`account_id`, `server_id`)",
        "CREATE TABLE IF NOT EXISTS `recipe_book_entry` (`account_id` TEXT NOT NULL, `local_id` INTEGER NOT NULL, " +
            "`server_id` INTEGER, `book_id` INTEGER NOT NULL, `recipe_id` INTEGER NOT NULL, PRIMARY KEY(`account_id`, `local_id`))",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_recipe_book_entry_account_id_server_id` ON `recipe_book_entry` " +
            "(`account_id`, `server_id`)",
        "CREATE INDEX IF NOT EXISTS `index_recipe_book_entry_account_id_book_id` ON `recipe_book_entry` (`account_id`, `book_id`)",
        "CREATE INDEX IF NOT EXISTS `index_recipe_book_entry_account_id_recipe_id` ON `recipe_book_entry` (`account_id`, `recipe_id`)",
        "CREATE TABLE IF NOT EXISTS `id_mapping` (`account_id` TEXT NOT NULL, `entity` TEXT NOT NULL, `local_id` INTEGER NOT NULL, " +
            "`server_id` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, PRIMARY KEY(`account_id`, `entity`, `local_id`))",
    )

    val ALL: List<Migration> = listOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
}
