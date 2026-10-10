package casa.moller.tandem.core.database

import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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

    private val database = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        TandemDatabase::class.java,
    ).build()

    @After
    fun close() {
        database.close()
    }

    @Test
    fun syncStateIsStoredPerAccount() = runTest {
        val dao = database.syncStateDao()
        dao.upsert(SyncStateEntity(accountId = "a", entity = "recipe", lastDate = "2026-10-01"))
        dao.upsert(SyncStateEntity(accountId = "b", entity = "recipe"))

        assertEquals("2026-10-01", dao.get("a", "recipe")?.lastDate)
        assertEquals(listOf("recipe"), dao.observe("a").first().map { it.entity })

        dao.deleteAccount("a")
        assertNull(dao.get("a", "recipe"))
        assertEquals(1, dao.observe("b").first().size)
    }

    @Test
    fun schemaFolderIsPassedToTests() {
        val dir = System.getProperty("tandem.roomSchemaDir")
        assertTrue("tandem.roomSchemaDir is set by tandem.android.room", !dir.isNullOrBlank() && File(dir).path.endsWith("schemas"))
    }
}
