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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migration scaffold (plan 5.6 "Databas: migreringar"). [MigrationTestHelper] reads the committed
 * `schemas/<database class>/<version>.json` from the unit-test assets (`tandem.android.room`).
 *
 * Today it proves that the committed version 1 schema is exactly what the code expects: a file
 * created from `1.json` opens with the current [TandemDatabase] (Room checks the identity hash)
 * and its rows are readable. When version 2 arrives, add a test per migration:
 * ```
 * helper.createDatabase(1).apply { execSQL("INSERT ..."); close() }
 * helper.runMigrationsAndValidate(2, TandemMigrations.ALL).close()
 * // then open TandemDatabase.build(context, TEST_DB) and check the rows survived
 * ```
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
    fun noMigrationsYetAtVersionOne() {
        assertEquals(1, TandemDatabase.VERSION)
        assertTrue(TandemMigrations.ALL.isEmpty())
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
