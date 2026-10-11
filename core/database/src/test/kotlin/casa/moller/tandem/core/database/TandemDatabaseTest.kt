package casa.moller.tandem.core.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.database.entity.SyncEntities
import casa.moller.tandem.core.database.entity.SyncStateEntity
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The Room wiring works on Robolectric: KSP generated the database, and rows round-trip. */
@RunWith(AndroidJUnit4::class)
class TandemDatabaseTest {
    private val database = newDatabase()

    @After
    fun close() {
        database.close()
    }

    @Test
    fun syncStateIsStoredPerAccount() = runTest {
        val dao = database.syncStateDao()
        dao.upsert(SyncStateEntity(accountId = "a", entity = SyncEntities.RECIPE, lastDate = "2026-10-01"))
        dao.upsert(SyncStateEntity(accountId = "a", entity = SyncEntities.MEAL_PLAN, windowFrom = "2026-09-22", windowTo = "2026-11-17"))
        dao.upsert(SyncStateEntity(accountId = "b", entity = SyncEntities.RECIPE, hash = "abc"))

        assertEquals("2026-10-01", dao.get("a", SyncEntities.RECIPE)?.lastDate)
        assertEquals("2026-11-17", dao.get("a", SyncEntities.MEAL_PLAN)?.windowTo)
        assertEquals(listOf(SyncEntities.MEAL_PLAN, SyncEntities.RECIPE), dao.observe("a").first().map { it.entity })

        dao.delete("a", SyncEntities.MEAL_PLAN)
        assertNull(dao.get("a", SyncEntities.MEAL_PLAN))
        dao.deleteAccount("a")
        assertNull(dao.get("a", SyncEntities.RECIPE))
        assertEquals("abc", dao.get("b", SyncEntities.RECIPE)?.hash)
    }

    @Test
    fun ftsTableUsesUnicode61WithoutDiacritics() = runTest {
        val sql = "SELECT COUNT(*) FROM sqlite_master WHERE name = 'recipe_fts' AND sql LIKE '%unicode61%remove_diacritics=2%'"
        assertEquals(1L, database.count(sql))
    }

    @Test
    fun schemaFolderIsPassedToTests() {
        val dir = System.getProperty("tandem.roomSchemaDir")
        assertTrue("tandem.roomSchemaDir is set by tandem.android.room", !dir.isNullOrBlank() && File(dir).path.endsWith("schemas"))
    }
}
