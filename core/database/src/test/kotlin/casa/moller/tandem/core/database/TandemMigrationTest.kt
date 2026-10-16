package casa.moller.tandem.core.database

import android.content.Context
import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import casa.moller.tandem.core.model.recipe.RecipeQuery
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migrations (plan 5.6 "Databas: migreringar"). [MigrationTestHelper] reads the committed
 * `schemas/<database class>/<version>.json` from the unit-test assets (`tandem.android.room`).
 *
 * Version 1 (0.1.0) is frozen: a file created from `1.json` must migrate to the current version
 * with its rows (an offline view in the outbox survives the app update) and then open with the
 * current [TandemDatabase] (Room checks the identity hash, so an uncommitted schema fails here).
 */
@RunWith(AndroidJUnit4::class)
class TandemMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule
    val helper = MigrationTestHelper(
        instrumentation = InstrumentationRegistry.getInstrumentation(),
        file = context.getDatabasePath(TEST_DB),
        driver = AndroidSQLiteDriver(),
        databaseClass = TandemDatabase::class,
    )

    @Before
    @After
    fun deleteFile() {
        context.deleteDatabase(TEST_DB)
    }

    @Test
    fun everyVersionHasAMigration() {
        assertEquals(2, TandemDatabase.VERSION)
        assertEquals((1 until TandemDatabase.VERSION).toList(), TandemMigrations.ALL.map { it.startVersion })
    }

    @Test
    fun versionOneMigratesToTwoWithAnEmptyCookLog() = runTest {
        helper.createDatabase(1).apply {
            execSQL(
                "INSERT INTO outbox (account_id, op, entity, server_id, payload, attempts, created_at, state) " +
                    "VALUES ('$ACCOUNT_A', 'VIEW_LOG', 'recipe', 2, '{}', 0, 1, 'PENDING')",
            )
            close()
        }

        helper.runMigrationsAndValidate(2, TandemMigrations.ALL).apply {
            execSQL(
                "INSERT INTO cook_log (account_id, local_id, server_id, recipe_id, rating, state) " +
                    "VALUES ('$ACCOUNT_A', -1, NULL, 2, 5, 'PENDING')",
            )
            close()
        }

        val database = TandemDatabase.build(context, TEST_DB)
        try {
            assertEquals(2L, database.outboxDao().nextReady(ACCOUNT_A)?.serverId)
            assertEquals(listOf(-1L), database.cookLogDao().getEntries(ACCOUNT_A).map { it.localId })
            assertFalse("no server list yet: only adds to the stored counts", database.cookLogDao().isComplete(ACCOUNT_A))
        } finally {
            database.close()
        }
    }

    @Test
    fun committedSchemaOneOpensWithTheCurrentCode() = runTest {
        helper.createDatabase(1).apply {
            execSQL(
                "INSERT INTO recipe_overview (account_id, server_id, name, working_minutes, waiting_minutes, is_new, is_private, " +
                    "search_name) VALUES ('$ACCOUNT_A', 2, 'Ärtsoppa', 20, 100, 0, 0, 'artsoppa')",
            )
            execSQL(
                "INSERT INTO outbox (account_id, op, entity, server_id, payload, attempts, created_at, state) " +
                    "VALUES ('$ACCOUNT_A', 'VIEW_LOG', 'recipe', 2, '{}', 0, 1, 'PENDING')",
            )
            close()
        }

        val database = TandemDatabase.build(context, TEST_DB)
        try {
            val recipes = database.recipeDao().observeRecipeList(ACCOUNT_A, RecipeQuery.All, SWEDISH).first()
            assertEquals(listOf("Ärtsoppa"), recipes.map { it.overview.name })
            assertEquals(2L, database.outboxDao().nextReady(ACCOUNT_A)?.serverId)
        } finally {
            database.close()
        }
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
