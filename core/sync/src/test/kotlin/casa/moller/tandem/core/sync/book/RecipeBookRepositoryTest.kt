package casa.moller.tandem.core.sync.book

import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.data.DataProblem
import casa.moller.tandem.core.database.entity.OutboxEntities
import casa.moller.tandem.core.database.entity.OutboxOps
import casa.moller.tandem.core.database.entity.ServerAccountEntity
import casa.moller.tandem.core.model.book.RecipeBook
import casa.moller.tandem.core.model.book.RecipeBookKind
import casa.moller.tandem.core.model.book.WantToCook
import casa.moller.tandem.core.model.cooklog.CookLogDraft
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.sync.SyncReason
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.account.DEMO_ACCOUNT_ID
import casa.moller.tandem.core.sync.engine.DrainResult
import casa.moller.tandem.core.sync.testing.SyncTestGraph
import casa.moller.tandem.core.sync.testing.SyncTestGraph.Companion.ACCOUNT_ID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.ConscryptMode

/**
 * Books, favourites and "Vill laga" (feature #17, plan 3.7, 5.7) and the guest mode (#42) against a
 * stateful Tandoor answering in the recorded shapes of BOTH 2.6.13 and 2.7.1 ([FakeBookTandoor]):
 * owner vs shared member (never a write to someone else's book), favourites created offline with
 * the id rewrite, "Vill laga" through the whole keyword list and the smart book, queued changes
 * surviving a re-read, the guest's role from `user-space/all_personal` and demo mode.
 */
@RunWith(AndroidJUnit4::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class RecipeBookRepositoryTest {
    private class Env(val graph: SyncTestGraph, val tandoor: FakeBookTandoor, val version: String) {
        val repo = graph.books

        suspend fun drainAll() {
            repeat(MAX_DRAINS) {
                val result = graph.outbox.drain(graph.remote)
                if (result == DrainResult.DONE && outbox().isEmpty()) return
            }
            throw AssertionError("[$version] the outbox did not drain: ${outbox()}")
        }

        suspend fun outbox() = graph.real.outboxDao().observeAll(ACCOUNT_ID).first()

        suspend fun books(): List<RecipeBook> = repo.observeBooks().first()

        suspend fun book(name: String): RecipeBook = books().first { it.name == name }

        fun offline() {
            graph.network.online.value = false
        }

        fun online() {
            graph.network.online.value = true
        }
    }

    private fun forEachVersion(
        userId: Long = FakeBookTandoor.OWN_USER,
        seed: FakeBookTandoor.() -> Unit = {},
        block: suspend Env.() -> Unit,
    ) {
        for (version in VERSIONS) {
            val graph = SyncTestGraph(serverVersion = version, userId = userId)
            val tandoor = FakeBookTandoor(version).also {
                it.caller = userId
                it.seed()
                it.install(graph.server)
            }
            try {
                runBlocking {
                    mirror(graph)
                    assertTrue(graph.engine.run(SyncReason.FIRST_SYNC).error == null)
                    Env(graph, tandoor, version).block()
                }
            } finally {
                graph.close()
            }
        }
    }

    private fun ok(outcome: Outcome<*>) = assertTrue("$outcome", outcome is Outcome.Success)

    private fun problem(outcome: Outcome<*>): DataProblem? =
        DataProblem.entries.firstOrNull { (outcome as? Outcome.Failure)?.error == it.toError() }

    private suspend fun mirror(graph: SyncTestGraph) {
        val account = graph.account
        graph.real.accountDao().upsert(ServerAccountEntity(account.id, account.baseUrl, account.userId, account.spaceId))
    }

    // ------------------------------------------------------------------ plan 3.7: owner vs shared

    @Test
    fun aBookSharedByAnotherMemberIsReadOnlyAndNeverWritten() = forEachVersion(
        userId = FakeBookTandoor.PARTNER,
        seed = {
            val theirs = addBook("Favoriter (Kim)", owner = FakeBookTandoor.OWN_USER, shared = listOf(FakeBookTandoor.PARTNER))
            addEntry(theirs, recipe = PASTA)
            addBook("Vardag", owner = FakeBookTandoor.PARTNER)
        },
    ) {
        val theirs = book("Favoriter (Kim)")
        val own = book("Vardag")
        assertEquals(RecipeBookKind.FAVORITES, theirs.kind)
        assertFalse(theirs.isOwn)
        assertFalse(theirs.canEdit)
        assertEquals(1, theirs.recipeCount)
        assertTrue(own.isOwn && own.canEdit)

        assertEquals(DataProblem.NOT_OWNER, problem(repo.addToBook(theirs.id, PANCAKES)))
        assertEquals(DataProblem.NOT_OWNER, problem(repo.removeFromBook(theirs.id, PASTA)))
        assertTrue("nothing is queued for someone else's book", outbox().isEmpty())

        ok(repo.addToBook(own.id, PANCAKES))
        ok(repo.addToBook(own.id, PANCAKES)) // twice: nothing new
        assertEquals(1, outbox().size)
        assertEquals(listOf("Pannkakor"), repo.observeRecipes(own.id).first().map { it.name })
        drainAll()
        val posts = tandoor.callsTo("POST", "/api/recipe-book-entry/")
        assertEquals(1, posts.size)
        assertEquals(own.id, Json.parseToJsonElement(posts.single().second).jsonObject.long("book"))
        val toTheirs = tandoor.calls.filter { (call, body) -> "/${theirs.id}/" in call || "\"book\":${theirs.id}" in body }
        assertTrue("[$version] no write to the shared book: $toTheirs", toTheirs.isEmpty())

        // The partner's favourites count in the merged view and in the recipe's status.
        assertEquals(listOf("Krämig svamppasta"), repo.observeFavorites().first().map { it.name })
        assertEquals(listOf("Kim"), repo.observeStatus(PASTA).first().favoriteOf)
        assertFalse(repo.observeStatus(PASTA).first().isFavorite)
    }

    // ------------------------------------------------------------------ favourites per person, offline

    @Test
    fun theOwnFavouritesBookIsCreatedOfflineAndSharedWithTheSpace() = forEachVersion {
        offline()
        ok(repo.setFavorite(PASTA, true))
        ok(repo.setFavorite(PANCAKES, true))
        val book = books().single { it.kind == RecipeBookKind.FAVORITES }
        assertTrue("a temporary id until it is sent", book.id < 0)
        assertTrue(book.isOwn)
        assertEquals("Favoriter (cook)", book.name)
        assertEquals(setOf(PASTA, PANCAKES), repo.observeFavoriteIds().first())
        assertTrue(repo.observeStatus(PASTA).first().isFavorite)
        ok(repo.setFavorite(PANCAKES, false)) // cancels its unsent CREATE
        assertEquals(listOf(OutboxOps.CREATE, OutboxOps.CREATE), outbox().map { it.op })
        assertEquals(listOf(OutboxEntities.RECIPE_BOOK, OutboxEntities.RECIPE_BOOK_ENTRY), outbox().map { it.entity })
        assertEquals("the entry waits for its book", outbox()[0].id, outbox()[1].dependsOn)

        online()
        drainAll()
        val created = tandoor.books.values.single { it.name == "Favoriter (cook)" }
        val others = listOf(FakeBookTandoor.PARTNER, FakeBookTandoor.GUEST)
        assertEquals("shared read-only with the space's other users", others, created.shared)
        val bookBody = Json.parseToJsonElement(tandoor.callsTo("POST", "/api/recipe-book/").single().second).jsonObject
        assertFalse("the app's own key is never sent", BookPayloads.SHARE_WITH_SPACE in bookBody)
        assertEquals(created.id, tandoor.entries.values.single().book)
        assertEquals(PASTA, tandoor.entries.values.single().recipe)
        // The temporary id was rewritten everywhere: the book has its server id, the entry points at it.
        val stored = books().single { it.kind == RecipeBookKind.FAVORITES }
        assertEquals(created.id, stored.id)
        assertEquals(setOf(PASTA), repo.observeFavoriteIds().first())
        assertEquals(created.id, graph.real.idRewriteDao().resolve(ACCOUNT_ID, OutboxEntities.RECIPE_BOOK, book.id))
    }

    @Test
    fun anEntryThatIsOnTheServerAlreadyBecomesThatEntry() = forEachVersion(seed = { addBook("Vardag", owner = FakeBookTandoor.OWN_USER) }) {
        val vardag = book("Vardag")
        ok(repo.addToBook(vardag.id, PASTA))
        // Another device of the same user added it meanwhile: Tandoor answers 400 "unique set".
        val existing = tandoor.addEntry(vardag.id, PASTA)
        drainAll()
        assertEquals(1, tandoor.entries.size)
        val entries = graph.real.recipeBookDao().observeEntries(ACCOUNT_ID, vardag.id).first()
        assertEquals(listOf(existing), entries.map { it.localId })

        ok(repo.removeFromBook(vardag.id, PASTA))
        drainAll()
        assertTrue(tandoor.entries.isEmpty())
        assertEquals(1, tandoor.callsTo("DELETE", "/api/recipe-book-entry/$existing/").size)
    }

    @Test
    fun queuedChangesSurviveAReRead() = forEachVersion(
        seed = {
            val vardag = addBook("Vardag", owner = FakeBookTandoor.OWN_USER)
            addEntry(vardag, PASTA)
        },
    ) {
        val vardag = book("Vardag")
        offline()
        ok(repo.removeFromBook(vardag.id, PASTA))
        ok(repo.addToBook(vardag.id, PANCAKES))
        online()
        graph.bookSync.sync(graph.remote, background = false)
        assertEquals("the queued delete and add stay", listOf("Pannkakor"), repo.observeRecipes(vardag.id).first().map { it.name })
        drainAll()
        graph.bookSync.sync(graph.remote, background = false)
        assertEquals(listOf("Pannkakor"), repo.observeRecipes(vardag.id).first().map { it.name })
    }

    // ------------------------------------------------------------------ "Vill laga" (plan 3.7 B)

    @Test
    fun wantToCookSendsTheWholeKeywordListAndCreatesTheSmartBook() = forEachVersion {
        offline()
        ok(repo.setWantToCook(PASTA, true))
        assertEquals(listOf("Krämig svamppasta"), repo.observeWantToCook().first().map { it.name })
        assertTrue(repo.observeStatus(PASTA).first().wantToCook)
        ok(repo.setWantToCook(PANCAKES, true))
        ok(repo.setWantToCook(PANCAKES, false)) // back to where it was: both cancel
        assertEquals(1, outbox().size)
        assertEquals(OutboxOps.RECIPE_KEYWORDS, outbox().single().op)

        online()
        drainAll()
        val patch = Json.parseToJsonElement(tandoor.callsTo("PATCH", "/api/recipe/$PASTA/").single().second).jsonObject
        val sent = (patch["keywords"] as JsonArray).map { (it.jsonObject["name"] as JsonPrimitive).content }
        assertEquals("the recipe's keywords stay, the new one is added", listOf("Pasta", "Vegetariskt", WantToCook.KEYWORD), sent)
        assertTrue(tandoor.callsTo("PATCH", "/api/recipe/$PANCAKES/").isEmpty())
        assertEquals(listOf("Pasta", "Vegetariskt", WantToCook.KEYWORD), tandoor.keywordsOf(PASTA))
        // The stored detail has the keyword now; the list does not depend on the outbox any more.
        assertEquals(listOf("Krämig svamppasta"), repo.observeWantToCook().first().map { it.name })

        // The shared smart book for the web: a saved filter on the keyword id, shared with the space.
        val filter = Json.parseToJsonElement(tandoor.callsTo("POST", "/api/custom-filter/").single().second).jsonObject
        val keywordId = graph.real.recipeBookDao().getKeywordIdsNamed(ACCOUNT_ID, WantToCook.KEYWORD).single()
        assertEquals(WantToCook.filterSearch(keywordId), (filter["search"] as JsonPrimitive).content)
        val smart = tandoor.books.values.single { it.filter != null }
        assertEquals("Vill laga", smart.name)
        assertEquals(RecipeBookKind.WANT_TO_COOK, book("Vill laga").kind)
        assertEquals(1, book("Vill laga").recipeCount)

        // Removing it sends the list without the keyword; a second "Vill laga" creates no second book.
        ok(repo.setWantToCook(PASTA, false))
        drainAll()
        assertEquals(listOf("Pasta", "Vegetariskt"), tandoor.keywordsOf(PASTA))
        assertTrue(repo.observeWantToCook().first().isEmpty())
        ok(repo.setWantToCook(PANCAKES, true))
        drainAll()
        assertEquals(1, tandoor.callsTo("POST", "/api/recipe-book/").size)
    }

    // ------------------------------------------------------------------ guest mode (#42)

    @Test
    fun aGuestIsReadOnlyEverywhereAndQueuesNothing() = forEachVersion(seed = { group = "guest" }) {
        // The role comes from user-space/all_personal, read with the first sync.
        assertTrue(graph.spacePermissions.permissions.first().readOnly)
        graph.permissions.setGuest(true)
        assertEquals(DataProblem.READ_ONLY, problem(repo.setFavorite(PASTA, true)))
        assertEquals(DataProblem.READ_ONLY, problem(repo.setWantToCook(PASTA, true)))
        assertEquals(DataProblem.READ_ONLY, problem(repo.createBook("Mina")))
        val draft = CookLogDraft(PASTA, servings = 2, rating = 5, comment = null, cookedAt = Instant.fromEpochMilliseconds(0))
        val cooked = graph.cookLogs.logCooked(draft)
        assertEquals(DataProblem.READ_ONLY, problem(cooked))
        graph.recipes.markViewed(PASTA)
        assertTrue("a guest queues nothing: ${outbox()}", outbox().isEmpty())

        tandoor.group = "user"
        graph.roleSync.sync(graph.remote, background = false)
        assertFalse(graph.spacePermissions.permissions.first().readOnly)
    }

    // ------------------------------------------------------------------ demo mode

    @Test
    fun demoModeHasFavouritesSharedBooksAndWantToCookWithoutTheNetwork(): Unit = runBlocking {
        val graph = SyncTestGraph()
        try {
            graph.sources.source.value = ActiveSource.Demo
            assertTrue(graph.demo.ensureSeeded() is Outcome.Success)
            val repo = graph.books
            val books = repo.observeBooks().first()
            val own = books.single { it.kind == RecipeBookKind.FAVORITES && it.isOwn }
            val theirs = books.single { it.kind == RecipeBookKind.FAVORITES && !it.isOwn }
            assertEquals("Alex", theirs.ownerName)
            assertFalse(theirs.canEdit)
            assertNotNull(books.singleOrNull { it.kind == RecipeBookKind.WANT_TO_COOK })
            assertTrue(repo.observeWantToCook().first().isNotEmpty())
            assertTrue(repo.observeFavorites().first().size > own.recipeCount)

            val recipe = repo.observeWantToCook().first().first().id
            ok(repo.setWantToCook(recipe, false))
            assertFalse(repo.observeWantToCook().first().any { it.id == recipe })
            ok(repo.setWantToCook(recipe, true))
            assertTrue(repo.observeWantToCook().first().any { it.id == recipe })
            val notFavorite = graph.demoContent.recipeIds.first { it !in repo.observeFavoriteIds().first() }
            ok(repo.setFavorite(notFavorite, true))
            assertTrue(notFavorite in repo.observeFavoriteIds().first())
            assertEquals(DataProblem.NOT_OWNER, problem(repo.addToBook(theirs.id, notFavorite)))
            // Seeded once: what the user changed stays on the next start.
            assertTrue(graph.demo.ensureSeeded() is Outcome.Success)
            assertTrue(notFavorite in repo.observeFavoriteIds().first())
            assertEquals(0, graph.demoDatabase.outboxDao().observeCount(DEMO_ACCOUNT_ID).first())
            assertTrue("demo mode sends nothing: ${graph.server.requests}", graph.server.requests.isEmpty())
        } finally {
            graph.close()
        }
    }

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

    /** 0.3.0 review (fix-data): an entry (a heart) sent while the books are read stays. */
    @Test
    fun anEntrySentWhileTheBooksAreReadStays() = forEachVersion(seed = { addBook("Vardag", owner = FakeBookTandoor.OWN_USER) }) {
        val vardag = book("Vardag")
        ok(repo.addToBook(vardag.id, PASTA))
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        graph.server.respond("GET", "/api/recipe-book-entry/") { request ->
            // The answer is built now, before the entry below reaches the server.
            val answer = checkNotNull(tandoor.answer(request))
            entered.countDown()
            gate.await(GATE_SECONDS, TimeUnit.SECONDS)
            answer
        }
        coroutineScope {
            val read = async(Dispatchers.Default) { graph.bookSync.sync(graph.remote, background = true) }
            assertTrue(entered.await(GATE_SECONDS, TimeUnit.SECONDS))
            val drain = async(Dispatchers.Default) { graph.outbox.drain(graph.remote) }
            delay(SETTLE_MILLIS)
            gate.countDown()
            read.await()
            drain.await()
        }
        assertEquals(1, tandoor.callsTo("POST", "/api/recipe-book-entry/").size)
        assertEquals("[$version] the entry is still in the book", 1, repo.observeRecipes(vardag.id).first().size)
    }

    private companion object {
        const val GATE_SECONDS = 10L
        const val SETTLE_MILLIS = 500L

        val VERSIONS = listOf("2.6.13", "2.7.1")
        const val MAX_DRAINS = 10
        const val PASTA = 2L
        const val PANCAKES = 3L
    }
}
