package casa.moller.tandem.core.database

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import casa.moller.tandem.core.database.dao.AccountDao
import casa.moller.tandem.core.database.dao.CookLogDao
import casa.moller.tandem.core.database.dao.HomeSectionDao
import casa.moller.tandem.core.database.dao.MealPlanDao
import casa.moller.tandem.core.database.dao.OutboxDao
import casa.moller.tandem.core.database.dao.RecipeDao
import casa.moller.tandem.core.database.dao.ReferenceDao
import casa.moller.tandem.core.database.dao.SyncStateDao
import casa.moller.tandem.core.database.entity.CookLogEntity
import casa.moller.tandem.core.database.entity.FoodEntity
import casa.moller.tandem.core.database.entity.HomeSectionEntity
import casa.moller.tandem.core.database.entity.HomeSectionRecipeEntity
import casa.moller.tandem.core.database.entity.IngredientEntity
import casa.moller.tandem.core.database.entity.KeywordEntity
import casa.moller.tandem.core.database.entity.MealPlanEntity
import casa.moller.tandem.core.database.entity.MealTypeEntity
import casa.moller.tandem.core.database.entity.OutboxEntity
import casa.moller.tandem.core.database.entity.RecipeDetailEntity
import casa.moller.tandem.core.database.entity.RecipeFtsEntity
import casa.moller.tandem.core.database.entity.RecipeKeywordCrossRef
import casa.moller.tandem.core.database.entity.RecipeOverviewEntity
import casa.moller.tandem.core.database.entity.ServerAccountEntity
import casa.moller.tandem.core.database.entity.StepEntity
import casa.moller.tandem.core.database.entity.SyncStateEntity
import casa.moller.tandem.core.database.entity.UnitEntity
import casa.moller.tandem.core.database.entity.UserPreferenceEntity

/**
 * The app's Room 3 database (plan 5.7). Every table except `server_account` and `outbox` has
 * `account_id` as the first part of its key, and every DAO function takes the account id
 * (plan 5.3, decision B14: one active account per device, switching later).
 *
 * There are two files with this schema (plan 6.2 "Demoläge"): the real one ([FILE_NAME]) and a
 * separate demo database ([DEMO_FILE_NAME]), so demo data never mixes with a real account. Open
 * them through [TandemDatabaseFactory].
 *
 * Version 1 shipped with 0.1.0 and `schemas/.../1.json` is frozen: every change needs a new
 * version, a migration in [TandemMigrations] and a migration test (see `TandemMigrationTest`).
 */
@Database(
    entities = [
        ServerAccountEntity::class,
        RecipeOverviewEntity::class,
        RecipeDetailEntity::class,
        StepEntity::class,
        IngredientEntity::class,
        RecipeKeywordCrossRef::class,
        RecipeFtsEntity::class,
        KeywordEntity::class,
        FoodEntity::class,
        UnitEntity::class,
        MealTypeEntity::class,
        MealPlanEntity::class,
        UserPreferenceEntity::class,
        HomeSectionEntity::class,
        HomeSectionRecipeEntity::class,
        SyncStateEntity::class,
        OutboxEntity::class,
        CookLogEntity::class,
    ],
    version = TandemDatabase.VERSION,
    exportSchema = true,
)
abstract class TandemDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao

    abstract fun recipeDao(): RecipeDao

    abstract fun referenceDao(): ReferenceDao

    abstract fun mealPlanDao(): MealPlanDao

    abstract fun homeSectionDao(): HomeSectionDao

    abstract fun outboxDao(): OutboxDao

    abstract fun syncStateDao(): SyncStateDao

    abstract fun cookLogDao(): CookLogDao

    companion object {
        /**
         * Schema version. 1 = 0.1.0 (frozen); 2 = 0.2.0 adds `cook_log` ([TandemMigrations.MIGRATION_1_2]).
         */
        const val VERSION: Int = 2

        /** File name of the real database (the whole database folder is excluded from backup, data_extraction_rules.xml). */
        const val FILE_NAME: String = "tandem.db"

        /** File name of the demo database (same schema, never holds real account data). */
        const val DEMO_FILE_NAME: String = "tandem-demo.db"

        /** Opens (creating or migrating) the database file [fileName] in the app's database folder. */
        fun build(context: Context, fileName: String): TandemDatabase =
            Room.databaseBuilder(context.applicationContext, TandemDatabase::class.java, fileName)
                .addMigrations(*TandemMigrations.ALL.toTypedArray())
                .build()

        /** An in-memory database, for tests. */
        fun buildInMemory(context: Context): TandemDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, TandemDatabase::class.java).build()
    }
}
