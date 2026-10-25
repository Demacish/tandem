package casa.moller.tandem.core.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.database.dao.CookStatsRow
import casa.moller.tandem.core.database.dao.IndexChange
import casa.moller.tandem.core.database.dao.RecipeIndexEntry
import casa.moller.tandem.core.database.entity.OutboxEntities
import casa.moller.tandem.core.database.entity.OutboxEntity
import casa.moller.tandem.core.database.entity.OutboxOps
import casa.moller.tandem.core.database.entity.RecipeOverviewEntity
import casa.moller.tandem.core.database.mapper.toDetailWrite
import casa.moller.tandem.core.database.mapper.toModel
import casa.moller.tandem.core.database.mapper.toOverviewWrite
import casa.moller.tandem.core.database.mapper.toRecipe
import casa.moller.tandem.core.database.query.RecipeSorting
import casa.moller.tandem.core.database.relation.RecipeListItem
import casa.moller.tandem.core.model.home.HomeSectionType
import casa.moller.tandem.core.model.recipe.RecipeOverview
import casa.moller.tandem.core.model.recipe.RecipeQuery
import casa.moller.tandem.core.model.recipe.RecipeSort
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Transactional recipe writes (plan 5.7): detail replace, index deletion rule, merge rules, list filters. */
@RunWith(AndroidJUnit4::class)
class RecipeDaoTest {
    private val database = newDatabase()
    private val dao = database.recipeDao()

    @After
    fun close() {
        database.close()
    }

    private suspend fun names(query: RecipeQuery, accountId: String = ACCOUNT_A): List<String> =
        dao.observeRecipeList(accountId, query, SWEDISH).first().map { it.overview.name }

    @Test
    fun detailRoundTripsThroughTheTables() = runTest {
        database.store(ACCOUNT_A, TestRecipes.MushroomPasta)

        val stored = checkNotNull(dao.getDetail(ACCOUNT_A, 1))
        val overview = dao.getOverviews(ACCOUNT_A, listOf(1)).single()
        val keywords = dao.getKeywordsOfRecipe(ACCOUNT_A, 1).map { it.toModel() }
        assertEquals(TestRecipes.MushroomPasta, stored.toRecipe(overview, keywords))
        assertEquals("""{"id":1}""", stored.detail.rawJson)
        assertEquals(listOf("Champinjoner", "Grädde", "Pasta"), stored.ingredients.sortedBy { it.position }.map { it.foodName })

        val item = checkNotNull(dao.observeListItem(ACCOUNT_A, 1).first())
        assertTrue(item.availableOffline)
        assertEquals(listOf("Pasta"), item.toModel().keywords.map { it.name })
    }

    @Test
    fun replaceDetailIsAtomic() = runTest {
        database.store(ACCOUNT_A, TestRecipes.MushroomPasta)
        val broken = TestRecipes.MushroomPasta.copy(name = "Trasig pasta").toDetailWrite(ACCOUNT_A, """{"id":1,"v":2}""", 2_000L)
        // Two ingredients with the same key make the last insert of the transaction fail.
        val duplicate = broken.copy(ingredients = broken.ingredients + broken.ingredients.first())

        val result = runCatching { dao.replaceDetail(ACCOUNT_A, duplicate) }

        assertTrue(result.isFailure)
        val stored = checkNotNull(dao.getDetail(ACCOUNT_A, 1))
        assertEquals("""{"id":1}""", stored.detail.rawJson)
        assertEquals(1_000L, stored.detail.fetchedAtEpochMillis)
        assertEquals(1, stored.steps.size)
        assertEquals(3, stored.ingredients.size)
        assertEquals("Krämig svamppasta", dao.getOverviews(ACCOUNT_A, listOf(1)).single().name)
        assertEquals(listOf("Krämig svamppasta"), names(RecipeQuery(text = "krämig")))
        assertEquals(emptyList<String>(), names(RecipeQuery(text = "trasig")))
    }

    @Test
    fun replaceDetailRebuildsStepsAndKeepsLocalState() = runTest {
        database.store(ACCOUNT_A, TestRecipes.MushroomPasta)
        dao.setPinned(ACCOUNT_A, 1, pinned = true)
        dao.setPortionsChosen(ACCOUNT_A, 1, 2.0)
        dao.markViewed(ACCOUNT_A, 1, nowEpochMillis = 5_000L)

        val fewerFoods = TestRecipes.recipe(id = 1, name = "Krämig svamppasta", foods = listOf("Champinjoner"))
        database.store(ACCOUNT_A, fewerFoods, fetchedAtEpochMillis = 9_000L)

        val stored = checkNotNull(dao.getDetail(ACCOUNT_A, 1))
        assertEquals(1, stored.ingredients.size)
        assertEquals(9_000L, stored.detail.fetchedAtEpochMillis)
        assertTrue(stored.detail.pinned)
        assertEquals(2.0, checkNotNull(stored.detail.portionsChosen), 0.0)
        assertEquals(5_000L, stored.detail.lastViewedLocalEpochMillis)
        assertEquals(2.0, checkNotNull(dao.observePortionsChosen(ACCOUNT_A, 1).first()), 0.0)
        assertEquals(emptyList<String>(), names(RecipeQuery(text = "grädde")))
    }

    @Test
    fun localStateWorksBeforeTheDetailIsStored() = runTest {
        dao.applyIndex(ACCOUNT_A, listOf(RecipeIndexEntry(1, "Krämig svamppasta")))
        dao.setPinned(ACCOUNT_A, 1, pinned = true)

        val item = checkNotNull(dao.observeListItem(ACCOUNT_A, 1).first())
        assertTrue(item.pinned)
        assertFalse(item.availableOffline)
        assertEquals(listOf(1L), dao.getPinnedIdsToRefresh(ACCOUNT_A, olderThanEpochMillis = 0))
        assertNull(checkNotNull(dao.getDetail(ACCOUNT_A, 1)).toRecipe(null))

        database.store(ACCOUNT_A, TestRecipes.MushroomPasta, fetchedAtEpochMillis = 1_000L)
        assertEquals(emptyList<Long>(), dao.getPinnedIdsToRefresh(ACCOUNT_A, olderThanEpochMillis = 500L))
        assertEquals(listOf(1L), dao.getPinnedIdsToRefresh(ACCOUNT_A, olderThanEpochMillis = 2_000L))

        // A copy fetched "in the future" (the clock was ahead then) counts as stale too.
        assertEquals(listOf(1L), dao.getPinnedIdsToRefresh(ACCOUNT_A, olderThanEpochMillis = 500L, newerThanEpochMillis = 900L))

        dao.evictDetail(ACCOUNT_A, 1)
        val evicted = checkNotNull(dao.observeListItem(ACCOUNT_A, 1).first())
        assertTrue(evicted.pinned)
        assertFalse(evicted.availableOffline)
        assertEquals(emptyList<String>(), names(RecipeQuery(text = "champinjon")))
    }

    @Test
    fun indexDeletesMissingRecipesExceptPinnedOnesBeingEdited() = runTest {
        TestRecipes.All.take(4).forEach { database.store(ACCOUNT_A, it) }
        val outbox = database.outboxDao()
        // 2: pinned with an unsent edit -> kept. 3: pinned without edits -> deleted.
        dao.setPinned(ACCOUNT_A, 2, pinned = true)
        dao.setPinned(ACCOUNT_A, 3, pinned = true)
        outbox.insert(
            OutboxEntity(
                accountId = ACCOUNT_A,
                op = OutboxOps.PATCH,
                entity = OutboxEntities.RECIPE,
                serverId = 2,
                createdAtEpochMillis = 1,
            ),
        )
        // 4: only a view log queued -> deleted, and the view log is dropped unsent.
        outbox.enqueueViewLog(ACCOUNT_A, recipeId = 4, payload = """{"recipe":4}""", nowEpochMillis = 2)
        database.homeSectionDao().replaceSection(ACCOUNT_A, HomeSectionType.NEW, listOf(4, 1), 3)

        val change = dao.applyIndex(ACCOUNT_A, listOf(RecipeIndexEntry(1, "Krämig svamppasta")))

        assertEquals(IndexChange(deleted = listOf(3L, 4L), kept = listOf(2L)), change)
        assertEquals(listOf(1L, 2L), dao.getRecipeIds(ACCOUNT_A).sorted())
        assertNull(dao.getDetail(ACCOUNT_A, 3))
        assertEquals(0L, database.count("SELECT COUNT(*) FROM ingredient WHERE recipe_id IN (3, 4)"))
        assertEquals(listOf(OutboxOps.PATCH), outbox.observeAll(ACCOUNT_A).first().map { it.op })
        assertEquals(listOf(1L), database.homeSectionDao().getSectionRecipeIds(ACCOUNT_A))
    }

    @Test
    fun anEmptyIndexDeletesNothingUnlessAllowed() = runTest {
        TestRecipes.All.take(2).forEach { database.store(ACCOUNT_A, it) }

        assertEquals(IndexChange(kept = listOf(1L, 2L)), dao.applyIndex(ACCOUNT_A, emptyList()))
        assertEquals(listOf(1L, 2L), dao.getRecipeIds(ACCOUNT_A).sorted())

        assertEquals(IndexChange(deleted = listOf(1L, 2L)), dao.applyIndex(ACCOUNT_A, emptyList(), allowEmpty = true))
        assertEquals(emptyList<Long>(), dao.getRecipeIds(ACCOUNT_A))
        assertEquals(0L, database.count("SELECT COUNT(*) FROM recipe_fts"))
    }

    @Test
    fun indexOnlyUpdatesNameAndImage() = runTest {
        database.store(ACCOUNT_A, TestRecipes.MushroomPasta)

        val change = dao.applyIndex(ACCOUNT_A, listOf(RecipeIndexEntry(1, "Svamppasta", "https://example.com/media/a.jpg")))

        assertEquals(IndexChange(updated = listOf(1L)), change)
        val overview = dao.getOverviews(ACCOUNT_A, listOf(1)).single()
        assertEquals("Svamppasta", overview.name)
        assertEquals("https://example.com/media/a.jpg", overview.imageUrl)
        assertEquals(15, overview.workingMinutes)
        assertEquals(4.0, checkNotNull(overview.rating), 0.0)
    }

    @Test
    fun listWritesKeepValuesTheListDoesNotCarry() = runTest {
        database.store(ACCOUNT_A, TestRecipes.MushroomPasta)
        dao.setCookStats(ACCOUNT_A, 1, lastCookedEpochMillis = 7_000L, timesCooked = 3)
        val listed = RecipeOverview(id = 1, name = "Krämig svamppasta", rating = null, isNew = true, keywords = listOf(TestRecipes.Soup))

        dao.upsertOverviews(ACCOUNT_A, listOf(listed.toOverviewWrite(ACCOUNT_A)))

        val item = checkNotNull(dao.observeListItem(ACCOUNT_A, 1).first()).toModel()
        assertEquals(4.0, checkNotNull(item.rating), 0.0)
        assertEquals(3, item.timesCooked)
        assertEquals(7_000L, item.lastCooked?.toEpochMilliseconds())
        assertTrue(item.isNew)
        assertEquals(listOf("Soppa"), item.keywords.map { it.name })
        assertTrue(item.availableOffline)

        // The detail has no `new` flag: storing it again keeps is_new.
        database.store(ACCOUNT_A, TestRecipes.MushroomPasta)
        assertTrue(checkNotNull(dao.observeListItem(ACCOUNT_A, 1).first()).overview.isNew)
    }

    @Test
    fun detailsOlderThanTheirRecipeAreStale() = runTest {
        TestRecipes.All.take(3).forEach { database.store(ACCOUNT_A, it, fetchedAtEpochMillis = 100_000L) }
        fun edited(id: Long, name: String, at: Long) =
            RecipeOverview(id = id, name = name, updatedAt = Instant.fromEpochMilliseconds(at)).toOverviewWrite(ACCOUNT_A)

        // Recipe 1 was edited on the web after its detail was fetched, recipe 2 long before; 3 has no time.
        dao.upsertOverviews(ACCOUNT_A, listOf(edited(1, "Krämig svamppasta", 400_000L), edited(2, "Ärtsoppa", 10_000L)))
        assertEquals(listOf(1L), dao.getIdsWithStaleDetail(ACCOUNT_A, toleranceMillis = 60_000L, limit = 10))

        // A change shortly before the fetch may be after it on the server's clock: stale too, newest first.
        dao.upsertOverviews(ACCOUNT_A, listOf(edited(2, "Ärtsoppa", 50_000L)))
        assertEquals(listOf(1L, 2L), dao.getIdsWithStaleDetail(ACCOUNT_A, toleranceMillis = 60_000L, limit = 10))
        assertEquals(listOf(1L), dao.getIdsWithStaleDetail(ACCOUNT_A, toleranceMillis = 60_000L, limit = 1))

        // Fetched again: up to date.
        database.store(ACCOUNT_A, TestRecipes.MushroomPasta, fetchedAtEpochMillis = 500_000L)
        assertEquals(listOf(2L), dao.getIdsWithStaleDetail(ACCOUNT_A, toleranceMillis = 60_000L, limit = 10))
        assertEquals(emptyList<Long>(), dao.getIdsWithStaleDetail(ACCOUNT_B, toleranceMillis = 60_000L, limit = 10))
    }

    @Test
    fun listFiltersAndSorts() = runTest {
        TestRecipes.All.forEach { database.store(ACCOUNT_A, it) }
        dao.applyIndex(ACCOUNT_A, TestRecipes.All.map { RecipeIndexEntry(it.id, it.name) } + RecipeIndexEntry(7, "Ölbröd"))

        assertEquals(
            listOf("Kanelbullar", "Krämig svamppasta", "Köttbullar med potatismos", "Svampsoppa", "Äppelkaka", "Ärtsoppa", "Ölbröd"),
            names(RecipeQuery.All),
        )
        assertEquals(
            listOf("Äppelkaka", "Krämig svamppasta", "Svampsoppa", "Kanelbullar", "Köttbullar med potatismos", "Ärtsoppa", "Ölbröd"),
            names(RecipeQuery(sort = RecipeSort.RATING)),
        )
        assertEquals(
            listOf("Äppelkaka", "Krämig svamppasta", "Svampsoppa", "Köttbullar med potatismos", "Kanelbullar", "Ärtsoppa", "Ölbröd"),
            names(RecipeQuery(sort = RecipeSort.TOTAL_TIME)),
        )
        assertEquals(listOf("Krämig svamppasta", "Svampsoppa", "Äppelkaka"), names(RecipeQuery(maxTotalMinutes = 30)))
        assertEquals(listOf("Krämig svamppasta", "Äppelkaka"), names(RecipeQuery(minRating = 4.0)))
        assertEquals(listOf("Svampsoppa", "Ärtsoppa"), names(RecipeQuery(keywordIds = setOf(TestRecipes.Soup.id))))
        assertEquals(emptyList<String>(), names(RecipeQuery(keywordIds = setOf(TestRecipes.Soup.id, TestRecipes.Pasta.id))))
        assertEquals(6, names(RecipeQuery(onlyOffline = true)).size)
        assertEquals(listOf("Svampsoppa"), names(RecipeQuery(text = "svamp", keywordIds = setOf(TestRecipes.Soup.id))))
    }

    @Test
    fun cookStatsAreReplacedInOneGoAndCarryTheOwnRating() = runTest {
        TestRecipes.All.forEach { database.store(ACCOUNT_A, it) }
        dao.applyIndex(ACCOUNT_A, TestRecipes.All.map { RecipeIndexEntry(it.id, it.name) } + RecipeIndexEntry(7, "Ölbröd"))
        dao.setCookStats(ACCOUNT_A, 2, lastCookedEpochMillis = 5_000L, timesCooked = 4)

        // Only Ölbröd (no detail stored) was rated by the user; Ärtsoppa's log entries are gone.
        val bread7 = CookStatsRow(7, timesCooked = 2, lastCookedEpochMillis = 9_000L, rating = 5.0)
        dao.replaceCookStats(ACCOUNT_A, listOf(bread7), withRatings = true)

        val pea = dao.getOverviews(ACCOUNT_A, listOf(2)).single()
        assertNull(pea.timesCooked)
        assertNull(pea.lastCookedEpochMillis)
        assertNull("no own rating in the cook log", pea.rating)
        val bread = dao.getOverviews(ACCOUNT_A, listOf(7)).single()
        assertEquals(2, bread.timesCooked)
        assertEquals(5.0, checkNotNull(bread.rating), 0.0)
        // The offline "Rating 4+" filter and the rating sort find it without a stored detail.
        assertEquals(listOf("Ölbröd"), names(RecipeQuery(minRating = 4.0)))
        assertEquals("Ölbröd", names(RecipeQuery(sort = RecipeSort.RATING)).first())
        // A later name-sorted list (rating null) keeps it.
        dao.upsertOverviews(ACCOUNT_A, listOf(RecipeOverview(id = 7, name = "Ölbröd", rating = null).toOverviewWrite(ACCOUNT_A)))
        assertEquals(5.0, checkNotNull(dao.getOverviews(ACCOUNT_A, listOf(7)).single().rating), 0.0)

        // Without ratings (demo mode) the stored ratings stay.
        dao.replaceCookStats(ACCOUNT_A, emptyList(), withRatings = false)
        assertEquals(5.0, checkNotNull(dao.getOverviews(ACCOUNT_A, listOf(7)).single().rating), 0.0)
        assertNull(dao.getOverviews(ACCOUNT_A, listOf(7)).single().timesCooked)
    }

    @Test
    fun newestSortsByCreationTimeNotByTheStoredNewFlag() {
        fun item(id: Long, createdAt: Long?, isNew: Boolean) = RecipeListItem(
            overview = RecipeOverviewEntity(
                accountId = ACCOUNT_A,
                serverId = id,
                name = "R$id",
                createdAtEpochMillis = createdAt,
                isNew = isNew,
            ),
            pinned = false,
            availableOffline = false,
            lastViewedLocalEpochMillis = null,
            keywordList = null,
        )
        // 100 was new when it was first synced and kept the flag; 150 was created a month later.
        val items = listOf(item(100, 1_000L, isNew = true), item(150, 30_000L, isNew = false), item(160, null, isNew = false))

        val sorted = items.sortedWith(RecipeSorting.comparator(RecipeSort.NEWEST, SWEDISH)).map { it.overview.serverId }

        assertEquals(listOf(150L, 100L, 160L), sorted)
    }
}
