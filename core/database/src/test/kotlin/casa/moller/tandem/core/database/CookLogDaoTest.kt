package casa.moller.tandem.core.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.database.dao.CookLogFact
import casa.moller.tandem.core.database.dao.CookStatistics
import casa.moller.tandem.core.database.dao.CookStatsRules
import casa.moller.tandem.core.database.dao.RecipeIndexEntry
import casa.moller.tandem.core.database.entity.CookLogEntity
import casa.moller.tandem.core.database.entity.CookLogProblems
import casa.moller.tandem.core.database.entity.CookLogState
import casa.moller.tandem.core.database.entity.OutboxOps
import casa.moller.tandem.core.database.entity.OutboxState
import casa.moller.tandem.core.database.relation.RecipeListItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The cook log ("Klart!", feature #8): local entries, sending, reconciling and the statistics. */
@RunWith(AndroidJUnit4::class)
class CookLogDaoTest {
    private val database = newDatabase()
    private val dao = database.cookLogDao()
    private val real = CookStatsRules(ownUserId = OWN, ratingsFromLog = true)

    @After
    fun close() {
        database.close()
    }

    private suspend fun overview(recipeId: Long): RecipeListItem =
        checkNotNull(database.recipeDao().observeListItem(ACCOUNT_A, recipeId).first())

    private fun server(id: Long, recipeId: Long, rating: Int?, by: Long = OWN, at: Long = id * 1_000) = CookLogEntity(
        accountId = ACCOUNT_A,
        localId = id,
        serverId = id,
        recipeId = recipeId,
        rating = rating,
        createdBy = by,
        cookedAtEpochMillis = at,
    )

    private suspend fun log(recipeId: Long, rating: Int?, at: Long, payload: String? = "{}", rules: CookStatsRules = real): Long =
        dao.logLocally(
            CookLogEntity(
                accountId = ACCOUNT_A,
                localId = 0,
                serverId = null,
                recipeId = recipeId,
                servings = 4,
                rating = rating,
                createdBy = OWN,
                cookedAtEpochMillis = at,
            ),
            outboxPayload = payload,
            nowEpochMillis = at,
            rules = rules,
        )

    @Test
    fun beforeTheFirstServerListALogOnlyAddsToTheStoredStatistics() = runTest {
        database.store(ACCOUNT_A, TestRecipes.MushroomPasta)
        database.recipeDao().setCookStats(ACCOUNT_A, 1, lastCookedEpochMillis = 50_000L, timesCooked = 7)

        val localId = log(recipeId = 1, rating = 2, at = 40_000L)

        assertEquals(-1L, localId)
        val stats = overview(1).overview
        assertEquals("7 from the server + this one", 8, stats.timesCooked)
        assertEquals("an older entry keeps the later date", 50_000L, stats.lastCookedEpochMillis)
        assertEquals("the stored rating stays", 4.0, checkNotNull(stats.rating), 0.0)
        val queued = database.outboxDao().nextReady(ACCOUNT_A)
        assertEquals(OutboxOps.COOK_LOG, queued?.op)
        assertEquals(1L, queued?.serverId)
        assertNull("the outbox row is a plain recipe row", queued?.localId)
        assertEquals(-1L, dao.getEntryForOutbox(ACCOUNT_A, checkNotNull(queued).id)?.localId)
        assertEquals(1, dao.observePendingCount(ACCOUNT_A).first())
    }

    @Test
    fun afterAServerListEveryRecipeIsRecountedFromTheLogWithOwnRatingsOnly() = runTest {
        TestRecipes.All.forEach { database.store(ACCOUNT_A, it) }

        dao.reconcile(
            ACCOUNT_A,
            listOf(
                server(1, recipeId = 1, rating = 5),
                server(2, recipeId = 1, rating = 4),
                server(3, recipeId = 2, rating = 5, by = OTHER),
            ),
            listRequestedAtEpochMillis = 10_000L,
            nowEpochMillis = 10_000L,
            rules = real,
        )

        assertTrue(dao.isComplete(ACCOUNT_A))
        assertEquals(2, overview(1).overview.timesCooked)
        assertEquals("4.5 rounds half up", 5.0, checkNotNull(overview(1).overview.rating), 0.0)
        assertEquals(1, overview(2).overview.timesCooked)
        assertNull("only another member rated it", overview(2).overview.rating)
        assertNull("not in the log: never cooked, not rated", overview(3).overview.timesCooked)
        assertNull(overview(3).overview.rating)

        // A local entry is counted at once, with its rating in the own average.
        log(recipeId = 1, rating = 1, at = 20_000L)
        assertEquals(3, overview(1).overview.timesCooked)
        assertEquals(20_000L, overview(1).overview.lastCookedEpochMillis)
        assertEquals("(5 + 4 + 1) / 3", 3.0, checkNotNull(overview(1).overview.rating), 0.0)
    }

    @Test
    fun aSentEntryIsCountedOnceAndTheNextListReplacesIt() = runTest {
        database.store(ACCOUNT_A, TestRecipes.MushroomPasta)
        dao.reconcile(ACCOUNT_A, listOf(server(1, recipeId = 1, rating = 5)), 1_000L, 1_000L, real)
        val localId = log(recipeId = 1, rating = 3, at = 5_000L)
        val outboxId = checkNotNull(database.outboxDao().nextReady(ACCOUNT_A)).id
        assertEquals(2, overview(1).overview.timesCooked)

        val created = server(7, recipeId = 1, rating = 3, at = 5_000L)
        dao.markSent(ACCOUNT_A, localId, outboxId, created, sentAtEpochMillis = 6_000L, rules = real)

        assertEquals(listOf(1L, 7L), dao.getEntries(ACCOUNT_A).map { it.localId })
        assertEquals(CookLogState.SENT, dao.getEntry(ACCOUNT_A, 7)?.state)
        assertNull("the outbox row is gone with it", database.outboxDao().get(outboxId))
        assertEquals(0, dao.observePendingCount(ACCOUNT_A).first())
        assertEquals(2, overview(1).overview.timesCooked)

        // A list requested BEFORE the send does not have it yet: it stays and keeps counting.
        dao.reconcile(ACCOUNT_A, listOf(server(1, recipeId = 1, rating = 5)), 5_500L, 7_000L, real)
        assertEquals(2, overview(1).overview.timesCooked)
        // The next list has it: the server's copy replaces it, no double counting.
        dao.reconcile(ACCOUNT_A, listOf(server(1, recipeId = 1, rating = 5), server(7, 1, 3, at = 5_000L)), 8_000L, 8_000L, real)
        assertEquals(listOf(CookLogState.SYNCED, CookLogState.SYNCED), dao.getEntries(ACCOUNT_A).map { it.state })
        assertEquals(2, overview(1).overview.timesCooked)
        assertEquals("(5 + 3) / 2", 4.0, checkNotNull(overview(1).overview.rating), 0.0)
        // Deleted on the web later: a list requested after the send without it removes it.
        dao.reconcile(ACCOUNT_A, listOf(server(1, recipeId = 1, rating = 5)), 9_000L, 9_000L, real)
        assertEquals(1, overview(1).overview.timesCooked)
    }

    @Test
    fun aListThatAlreadyHasTheEntryBeforeTheAnswerArrivedCountsItOnceAfterTheAnswer() = runTest {
        database.store(ACCOUNT_A, TestRecipes.MushroomPasta)
        dao.reconcile(ACCOUNT_A, emptyList(), 1_000L, 1_000L, real)
        val localId = log(recipeId = 1, rating = null, at = 5_000L)
        val outboxId = checkNotNull(database.outboxDao().nextReady(ACCOUNT_A)).id
        // The POST reached the server, but its answer was lost; a sync lists the new entry.
        dao.reconcile(ACCOUNT_A, listOf(server(9, recipeId = 1, rating = null, at = 5_000L)), 6_000L, 6_000L, real)
        assertEquals("briefly both", 2, overview(1).overview.timesCooked)

        dao.markSent(ACCOUNT_A, localId, outboxId, server(9, recipeId = 1, rating = null, at = 5_000L), 7_000L, real)

        assertEquals(1, overview(1).overview.timesCooked)
        assertEquals(listOf(9L), dao.getEntries(ACCOUNT_A).map { it.localId })
    }

    @Test
    fun aPostWhoseAnswerWasLostIsCountedOnceWhileTheOutboxIsHeldBack() = runTest {
        database.store(ACCOUNT_A, TestRecipes.MushroomPasta)
        dao.reconcile(ACCOUNT_A, emptyList(), 1_000L, 1_000L, real)
        log(recipeId = 1, rating = 4, at = 5_000L)
        val outboxId = checkNotNull(database.outboxDao().nextReady(ACCOUNT_A)).id
        // Sent once, the answer was lost; then safe mode holds the row back.
        database.outboxDao().markInFlight(outboxId)
        database.outboxDao().markRetry(outboxId, "timeout")
        database.outboxDao().pauseAll(ACCOUNT_A)

        // The next sync lists the entry the POST created (the server stores whole seconds).
        dao.reconcile(ACCOUNT_A, listOf(server(9, recipeId = 1, rating = 4, at = 5_000L)), 6_000L, 6_000L, real)

        assertEquals("counted once", 1, overview(1).overview.timesCooked)
        assertEquals(4.0, checkNotNull(overview(1).overview.rating), 0.0)
        assertEquals(listOf(9L), dao.getEntries(ACCOUNT_A).map { it.localId })
        assertNull("nothing left to send", database.outboxDao().get(outboxId))
        assertEquals(0, dao.observePendingCount(ACCOUNT_A).first())
    }

    @Test
    fun anUnattemptedOrDifferentEntryIsNotSettledByAList() = runTest {
        database.store(ACCOUNT_A, TestRecipes.MushroomPasta)
        dao.reconcile(ACCOUNT_A, emptyList(), 1_000L, 1_000L, real)
        log(recipeId = 1, rating = null, at = 5_000L)
        val outboxId = checkNotNull(database.outboxDao().nextReady(ACCOUNT_A)).id
        database.outboxDao().markInFlight(outboxId)
        database.outboxDao().markRetry(outboxId, "timeout")

        // Another member's entry at the same second, and an own one a second later: not this one.
        dao.reconcile(
            ACCOUNT_A,
            listOf(server(9, recipeId = 1, rating = null, by = OTHER, at = 5_000L), server(10, recipeId = 1, rating = null, at = 6_000L)),
            7_000L,
            7_000L,
            real,
        )

        assertEquals(3, overview(1).overview.timesCooked)
        assertEquals(outboxId, database.outboxDao().get(outboxId)?.id)
    }

    @Test
    fun droppedAndRejectedEntriesStopCountingAndKeepTheirReason() = runTest {
        database.store(ACCOUNT_A, TestRecipes.MushroomPasta)
        dao.reconcile(ACCOUNT_A, emptyList(), 1_000L, 1_000L, real)
        val dropped = log(recipeId = 1, rating = 5, at = 2_000L)
        val rejected = log(recipeId = 1, rating = 1, at = 3_000L)
        val rows = database.outboxDao().getByState(ACCOUNT_A, OutboxState.PENDING)
        assertEquals(2, overview(1).overview.timesCooked)

        dao.markDropped(ACCOUNT_A, dropped, rows[0].id, CookLogProblems.NOT_FOUND, real)
        dao.markRejected(ACCOUNT_A, rejected, rows[1].id, "http.400", real)

        assertEquals(CookLogState.DROPPED, dao.getEntry(ACCOUNT_A, dropped)?.state)
        assertEquals(CookLogProblems.NOT_FOUND, dao.getEntry(ACCOUNT_A, dropped)?.problem)
        assertEquals(CookLogState.FAILED, dao.getEntry(ACCOUNT_A, rejected)?.state)
        assertNull(database.outboxDao().get(rows[0].id))
        assertEquals("kept for Pending changes", OutboxState.FAILED, database.outboxDao().get(rows[1].id)?.state)
        assertNull(overview(1).overview.timesCooked)
        assertNull(overview(1).overview.rating)
    }

    @Test
    fun aRecipeRemovedFromTheIndexDropsItsQueuedEntries() = runTest {
        database.recipeDao().applyIndex(ACCOUNT_A, listOf(RecipeIndexEntry(1, "Pasta"), RecipeIndexEntry(2, "Soppa")))
        val gone = log(recipeId = 2, rating = null, at = 2_000L)
        val kept = log(recipeId = 1, rating = null, at = 3_000L)

        database.recipeDao().applyIndex(ACCOUNT_A, listOf(RecipeIndexEntry(1, "Pasta")))

        assertEquals(CookLogState.DROPPED, dao.getEntry(ACCOUNT_A, gone)?.state)
        assertEquals(CookLogProblems.RECIPE_DELETED, dao.getEntry(ACCOUNT_A, gone)?.problem)
        assertEquals(CookLogState.PENDING, dao.getEntry(ACCOUNT_A, kept)?.state)
        assertEquals(1, dao.observePendingCount(ACCOUNT_A).first())
    }

    @Test
    fun demoRulesKeepStoredRatingsUnlessRatedOnThisDevice() = runTest {
        val demo = CookStatsRules(ownUserId = null, ratingsFromLog = false)
        database.store(ACCOUNT_A, TestRecipes.MushroomPasta)
        database.store(ACCOUNT_A, TestRecipes.ApplePie)
        dao.reconcile(
            ACCOUNT_A,
            listOf(server(1, recipeId = 1, rating = 5), server(2, recipeId = 3, rating = 3)),
            1_000L,
            1_000L,
            demo,
        )
        assertEquals("the detail's rating stays", 4.0, checkNotNull(overview(1).overview.rating), 0.0)
        assertEquals(5.0, checkNotNull(overview(3).overview.rating), 0.0)

        log(recipeId = 3, rating = 4, at = 2_000L, payload = null, rules = demo)

        assertEquals("never queued", 0, dao.observePendingCount(ACCOUNT_A).first())
        assertEquals(2, overview(3).overview.timesCooked)
        assertEquals("(3 + 4) / 2 with every demo entry own", 4.0, checkNotNull(overview(3).overview.rating), 0.0)
        assertEquals(4.0, checkNotNull(overview(1).overview.rating), 0.0)
    }

    @Test
    fun statisticsRoundLikeTheServer() {
        val facts = listOf(
            CookLogFact(1, 10L, 5, own = true),
            CookLogFact(1, 30L, 4, own = true),
            CookLogFact(1, null, null, own = true),
            CookLogFact(1, 20L, 1, own = false),
            CookLogFact(2, null, 2, own = false),
        )

        val stats = CookStatistics.of(facts).associateBy { it.recipeId }

        assertEquals(4, stats.getValue(1).timesCooked)
        assertEquals(30L, stats.getValue(1).lastCookedEpochMillis)
        assertEquals(5.0, checkNotNull(stats.getValue(1).rating), 0.0)
        assertNull(stats.getValue(2).rating)
        assertNull(stats.getValue(2).lastCookedEpochMillis)
        assertEquals(3.0, checkNotNull(CookStatistics.ownRating(listOf(2, 3))), 0.0)
    }

    private companion object {
        const val OWN = 1L
        const val OTHER = 2L
    }
}
