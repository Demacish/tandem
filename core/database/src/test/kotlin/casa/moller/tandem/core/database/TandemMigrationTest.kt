package casa.moller.tandem.core.database

import android.content.Context
import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        assertEquals(3, TandemDatabase.VERSION)
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
    fun versionTwoMigratesToThreeAndKeepsTheSteps() = runTest {
        helper.createDatabase(2).apply {
            execSQL(
                "INSERT INTO step (account_id, recipe_id, position, step_id, name, instruction, time_minutes, step_order, " +
                    "show_as_header, show_ingredients_table) VALUES ('$ACCOUNT_A', 2, 0, 11, 'Koka', 'Koka 10 min', 10, 0, 0, 1)",
            )
            close()
        }

        helper.runMigrationsAndValidate(3, TandemMigrations.ALL).apply {
            // The new column exists and starts empty; a fetched detail fills it.
            execSQL("UPDATE step SET image_url = 'https://example.com/media/step.jpg' WHERE step_id = 11")
            close()
        }

        val database = TandemDatabase.build(context, TEST_DB)
        try {
            assertEquals(listOf("https://example.com/media/step.jpg"), database.recipeDao().getStepImageUrls(ACCOUNT_A, listOf(2L)))
            assertEquals(emptyList<String>(), database.recipeDao().getStepImageUrls(ACCOUNT_A, listOf(3L)))
        } finally {
            database.close()
        }
    }

    /**
     * 0.1.0 -> 0.2.0: details whose stored server JSON has a step photo are marked for a new fetch
     * (fetched_at NULL, raw JSON kept); a PDF on a step (preview "") or no file changes nothing.
     */
    @Test
    fun versionOneMigratesToThreeAndMarksDetailsWithStepPhotos() = runTest {
        helper.createDatabase(1).apply {
            for (id in listOf(2, 3, 4)) {
                execSQL(
                    "INSERT INTO recipe_overview (account_id, server_id, name, working_minutes, waiting_minutes, is_new, is_private, " +
                        "search_name) VALUES ('$ACCOUNT_A', $id, 'Recept $id', 0, 0, 0, 0, 'recept $id')",
                )
            }
            val photo = """{"id":1,"name":"Stegbild","preview":"https://tandoor.example.com/media/files/a.png"}"""
            insertDetail(this, 2, stepJson(file = photo))
            insertDetail(this, 3, stepJson(file = """{"id":2,"name":"Receptblad","preview":""}"""))
            insertDetail(this, 4, stepJson(file = "null"))
            close()
        }

        helper.runMigrationsAndValidate(3, TandemMigrations.ALL).close()

        val database = TandemDatabase.build(context, TEST_DB)
        try {
            val recipes = database.recipeDao()
            assertEquals(listOf(2L), recipes.getIdsMarkedForRefetch(ACCOUNT_A, 10))
            assertNull(recipes.getDetailRow(ACCOUNT_A, 2)?.fetchedAtEpochMillis)
            assertNotNull("the stored copy stays readable offline", recipes.getDetailRow(ACCOUNT_A, 2)?.rawJson)
            assertEquals(FETCHED_AT, recipes.getDetailRow(ACCOUNT_A, 3)?.fetchedAtEpochMillis)
            assertEquals(FETCHED_AT, recipes.getDetailRow(ACCOUNT_A, 4)?.fetchedAtEpochMillis)
        } finally {
            database.close()
        }
    }

    /** A detail row of schema 1 with [rawJson] (single quotes would end the SQL literal). */
    private fun insertDetail(connection: SQLiteConnection, recipeId: Int, rawJson: String) {
        require('\'' !in rawJson)
        connection.execSQL(
            "INSERT INTO recipe_detail (account_id, recipe_id, raw_json, servings, servings_text, working_minutes, waiting_minutes, " +
                "diameter, diameter_text, show_ingredient_overview, nutrition, fetched_at, pinned) VALUES ('$ACCOUNT_A', $recipeId, " +
                "'$rawJson', 2, '', 0, 0, 0, '', 1, 'NONE', $FETCHED_AT, 0)",
        )
    }

    /** kotlinx.serialization's compact JSON, as DetailFetcher stores it. */
    private fun stepJson(file: String): String =
        """{"id":7,"name":"Recept","steps":[{"id":11,"instruction":"Koka","ingredients":[],"file":$file}],"servings":2}"""

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
        const val FETCHED_AT = 1_760_000_000_000L
    }
}
