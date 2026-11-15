package casa.moller.tandem.core.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.database.entity.InboxEntryEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fas 5 (schema 7, rss lane): the recipe inbox's cache. A refresh replaces the unread posts and keeps
 * the local marks; imported posts stay as "Importerad" for 30 days, a dismissed one is hidden and its
 * "read" waits until Miniflux took it; every query is per account.
 */
@RunWith(AndroidJUnit4::class)
class InboxDaoTest {
    private val database = newDatabase()
    private val dao = database.inboxDao()

    @After
    fun close() {
        database.close()
    }

    private fun post(id: Long, published: Long? = id * 1_000, account: String = ACCOUNT_A) = InboxEntryEntity(
        accountId = account,
        entryId = id,
        categoryId = 1,
        title = "Recept $id",
        url = "https://example.com/recept/$id",
        feedTitle = "Exempelköket",
        publishedAt = published,
        imageUrl = null,
        excerpt = "",
    )

    private suspend fun visible(account: String = ACCOUNT_A) = dao.observeVisible(account).first().map { it.entryId }

    @Test
    fun visiblePostsAreNewestFirstAndPerAccount() = runTest {
        dao.applyRefresh(ACCOUNT_A, listOf(post(1), post(3), post(2, published = null)), keepImportedSinceMillis = 0)
        dao.applyRefresh(ACCOUNT_B, listOf(post(7, account = ACCOUNT_B)), keepImportedSinceMillis = 0)

        assertEquals(listOf(3L, 1L, 2L), visible())
        assertEquals(listOf(7L), visible(ACCOUNT_B))
    }

    @Test
    fun aRefreshKeepsTheLocalMarksAndDropsPostsReadElsewhere() = runTest {
        dao.applyRefresh(ACCOUNT_A, listOf(post(1), post(2), post(3), post(4)), keepImportedSinceMillis = 0)
        dao.markImported(ACCOUNT_A, 1, atMillis = 50_000, markRead = false)
        dao.markImported(ACCOUNT_A, 2, atMillis = 50_000, markRead = true)
        dao.dismiss(ACCOUNT_A, 3, atMillis = 50_000)
        assertEquals(listOf(4L, 2L, 1L), visible())
        assertEquals(listOf(2L, 3L), dao.getReadPending(ACCOUNT_A))

        // The server no longer lists 1 (read in the web), 2 and 3 (still to be marked read here), 4.
        dao.applyRefresh(ACCOUNT_A, listOf(post(5)), keepImportedSinceMillis = 10_000)

        // 1: imported, kept; 2: imported and still to send; 3: dismissed, kept only for its "read"; 4 gone.
        assertEquals(listOf(5L, 2L, 1L), visible())
        assertEquals(listOf(2L, 3L), dao.getReadPending(ACCOUNT_A))
        assertNull(dao.get(ACCOUNT_A, 4))

        dao.readSent(ACCOUNT_A, listOf(2, 3))
        dao.applyRefresh(ACCOUNT_A, listOf(post(5)), keepImportedSinceMillis = 60_000)
        // Imported before the 30 days (here: before 60 000) and sent: gone; the dismissed one too.
        assertEquals(listOf(5L), visible())
        assertEquals(emptyList<Long>(), dao.getReadPending(ACCOUNT_A))
        assertNull(dao.get(ACCOUNT_A, 3))
    }

    @Test
    fun aStillUnreadPostKeepsItsMarks() = runTest {
        dao.applyRefresh(ACCOUNT_A, listOf(post(1)), keepImportedSinceMillis = 0)
        dao.dismiss(ACCOUNT_A, 1, atMillis = 9)
        dao.applyRefresh(ACCOUNT_A, listOf(post(1).copy(title = "Nytt namn")), keepImportedSinceMillis = 0)

        assertEquals(emptyList<Long>(), visible())
        val row = dao.get(ACCOUNT_A, 1)!!
        assertEquals("Nytt namn", row.title)
        assertEquals(9L, row.dismissedAt)
        assertEquals(true, row.readPending)
    }

    @Test
    fun removingTheAccountTakesItsPosts() = runTest {
        dao.applyRefresh(ACCOUNT_A, listOf(post(1)), keepImportedSinceMillis = 0)
        dao.applyRefresh(ACCOUNT_B, listOf(post(1, account = ACCOUNT_B)), keepImportedSinceMillis = 0)

        database.accountDao().deleteAccountData(ACCOUNT_A)

        assertEquals(emptyList<Long>(), visible())
        assertEquals(listOf(1L), visible(ACCOUNT_B))
    }
}
