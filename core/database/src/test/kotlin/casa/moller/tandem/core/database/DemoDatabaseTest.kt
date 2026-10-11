package casa.moller.tandem.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.model.recipe.RecipeQuery
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The demo database is a separate file with the same schema (plan 6.2 "Demoläge"). */
@RunWith(AndroidJUnit4::class)
class DemoDatabaseTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val factory = TandemDatabaseFactory.forFiles(context)

    @Before
    fun deleteFiles() {
        DatabaseKind.entries.forEach { context.deleteDatabase(it.fileName) }
    }

    @After
    fun closeAndDelete() {
        DatabaseKind.entries.forEach { factory.get(it).close() }
        DatabaseKind.entries.forEach { context.deleteDatabase(it.fileName) }
    }

    private suspend fun names(kind: DatabaseKind, accountId: String = ACCOUNT_A): List<String> =
        factory.get(kind).recipeDao().observeRecipeList(accountId, RecipeQuery.All, SWEDISH).first().map { it.overview.name }

    @Test
    fun demoDataNeverReachesTheRealDatabase() = runTest {
        val real = factory.get(DatabaseKind.REAL)
        val demo = factory.get(DatabaseKind.DEMO)
        assertNotSame(real, demo)
        assertSame(demo, factory.get(DatabaseKind.DEMO))

        // Same account id in both files on purpose: the files, not the ids, keep them apart.
        demo.store(ACCOUNT_A, TestRecipes.MushroomPasta)
        real.store(ACCOUNT_A, TestRecipes.PeaSoup)

        assertEquals(listOf("Krämig svamppasta"), names(DatabaseKind.DEMO))
        assertEquals(listOf("Ärtsoppa"), names(DatabaseKind.REAL))
        assertTrue(context.getDatabasePath(TandemDatabase.FILE_NAME).exists())
        assertTrue(context.getDatabasePath(TandemDatabase.DEMO_FILE_NAME).exists())
    }

    @Test
    fun clearingOneDatabaseLeavesTheOther() = runTest {
        factory.get(DatabaseKind.DEMO).store(ACCOUNT_A, TestRecipes.MushroomPasta)
        factory.get(DatabaseKind.REAL).store(ACCOUNT_A, TestRecipes.PeaSoup)
        factory.get(DatabaseKind.REAL).store(ACCOUNT_B, TestRecipes.ApplePie)

        factory.clear(DatabaseKind.DEMO)
        assertEquals(emptyList<String>(), names(DatabaseKind.DEMO))
        assertEquals(listOf("Ärtsoppa"), names(DatabaseKind.REAL))

        factory.clearAccount(DatabaseKind.REAL, ACCOUNT_A)
        assertEquals(emptyList<String>(), names(DatabaseKind.REAL))
        assertEquals(listOf("Äppelkaka"), names(DatabaseKind.REAL, ACCOUNT_B))
    }

    @Test
    fun inMemoryFactoryAlsoSeparatesKinds() = runTest {
        val memory = TandemDatabaseFactory.inMemory(context)
        try {
            memory.get(DatabaseKind.DEMO).store(ACCOUNT_A, TestRecipes.MushroomPasta)
            assertEquals(emptyList<Long>(), memory.get(DatabaseKind.REAL).recipeDao().getRecipeIds(ACCOUNT_A))
            assertEquals(listOf(1L), memory.get(DatabaseKind.DEMO).recipeDao().getRecipeIds(ACCOUNT_A))
        } finally {
            DatabaseKind.entries.forEach { memory.get(it).close() }
        }
    }
}
