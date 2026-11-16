package casa.moller.tandem.core.sync.inbox

import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.data.inbox.InboxFailure
import casa.moller.tandem.core.database.DatabaseKind
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.inbox.InboxAvailability
import casa.moller.tandem.core.model.inbox.InboxCategory
import casa.moller.tandem.core.model.inbox.InboxProblem
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.testing.SyncTestGraph
import casa.moller.tandem.core.sync.testing.SyncTestGraph.Companion.ACCOUNT_ID
import casa.moller.tandem.core.sync.work.RecipeInboxWork
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.ConscryptMode

/**
 * Fas 5 (rss lane, plan §7 #35): the recipe inbox against an in-process Miniflux ([FakeMiniflux], the
 * answers of Miniflux 2.3.4). The key is stored only when the server takes it; the unread posts of the
 * chosen categories are cached and stay readable offline; "read" is sent now or at the next refresh;
 * an import is remembered as "Importerad" and marked read only when the setting is on; the inbox's own
 * guard stops a refused key and never touches the Tandoor sync's guard (nor the other way round);
 * disconnecting and the account's removal forget everything; demo mode never uses the network.
 */
@RunWith(AndroidJUnit4::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class RecipeInboxRepositoryTest {
    private val graph = SyncTestGraph()
    private val miniflux = FakeMiniflux().start()
    private val store = FakeRecipeInboxStore()
    private val keys = FakeRecipeInboxKeyStore()
    private val guard = MinifluxGuard(graph.clock)
    private val demo = DemoInbox()
    private val repository = DefaultRecipeInboxRepository(
        graph.sources,
        graph.databases,
        store,
        keys,
        miniflux.client(),
        guard,
        graph.clock,
        demo,
        graph.scope,
    )

    private val recipes = InboxCategory(2, "Recept")

    @After
    fun tearDown() {
        miniflux.close()
        graph.close()
    }

    private fun seed() {
        miniflux.post(11, "Pannkakor", day = 13)
        miniflux.post(12, "Linssoppa", day = 12)
        miniflux.post(13, "Kladdkaka", day = 11)
        miniflux.post(21, "Valresultat", day = 13, category = 3)
    }

    private suspend fun setUp() {
        seed()
        assertTrue(repository.connect(miniflux.baseUrl, FakeMiniflux.KEY) is Outcome.Success)
        assertEquals(Outcome.Success(Unit), repository.chooseCategories(listOf(recipes)))
    }

    private suspend fun titles() = repository.entries.first().map { it.title }

    private suspend fun eventually(condition: suspend () -> Boolean) {
        withTimeout(WAIT_MILLIS) { while (!condition()) delay(POLL_MILLIS) }
    }

    @Test
    fun theKeyIsStoredOnlyWhenTheServerTakesIt() = runBlocking {
        seed()
        val wrong = repository.connect(miniflux.baseUrl, "fel-nyckel")
        assertEquals(InboxProblem.AUTH, InboxFailure.problemOf((wrong as Outcome.Failure).error))
        assertEquals(emptyMap<String, String>(), keys.keys.value)
        assertFalse(repository.state.first().settings.connected)

        val cleartext = repository.connect(miniflux.baseUrl.replace("https://", "http://"), FakeMiniflux.KEY)
        assertEquals(InboxProblem.INVALID_URL, InboxFailure.problemOf((cleartext as Outcome.Failure).error))

        val pasted = miniflux.baseUrl.removeSuffix("/") + "/v1/me"
        val categories = (repository.connect(pasted, " ${FakeMiniflux.KEY} ") as Outcome.Success).value
        assertEquals(listOf("All", "Recept", "Nyheter"), categories.map { it.title })
        assertEquals(3, categories.first { it.id == 2L }.unread)
        assertEquals(mapOf(ACCOUNT_ID to FakeMiniflux.KEY), keys.keys.value)
        val state = repository.state.first()
        assertEquals(InboxAvailability.AVAILABLE, state.availability)
        assertTrue(state.settings.connected)
        assertFalse("no category chosen yet", state.settings.enabled)
        assertEquals(miniflux.baseUrl, state.settings.serverUrl)
        // Only Miniflux's own paths, never a Tandoor one, never with a password.
        assertTrue(miniflux.requests.all { it.contains("/v1/") })
    }

    @Test
    fun theChosenCategoriesAreReadNewestFirstAndCachedForOffline() = runBlocking {
        setUp()

        assertEquals(listOf("Pannkakor", "Linssoppa", "Kladdkaka"), titles())
        val first = repository.entries.first().first()
        assertEquals("https://recipes.example.com/recept/11", first.url)
        assertEquals("Exempelköket", first.feedTitle)
        assertEquals("https://recipes.example.com/bilder/11.jpg", first.imageUrl)
        assertEquals("Text om Pannkakor.", first.excerpt)
        assertTrue(repository.state.first().settings.enabled)
        assertNotNull(repository.state.first().settings.lastRefresh)

        // Offline: the refresh fails, the posts stay, the problem is kept for the screen.
        miniflux.down = true
        val offline = repository.refresh()
        assertEquals(InboxProblem.UNREACHABLE, InboxFailure.problemOf((offline as Outcome.Failure).error))
        assertEquals(listOf("Pannkakor", "Linssoppa", "Kladdkaka"), titles())
        assertEquals(InboxProblem.UNREACHABLE, repository.state.first().settings.problem)

        miniflux.down = false
        miniflux.post(14, "Äppelpaj", day = 14)
        assertEquals(Outcome.Success(Unit), repository.refresh())
        assertEquals(listOf("Äppelpaj", "Pannkakor", "Linssoppa", "Kladdkaka"), titles())
        assertNull(repository.state.first().settings.problem)
    }

    @Test
    fun markReadIsSentNowOrAtTheNextRefresh() = runBlocking {
        setUp()

        repository.dismiss(11)
        assertEquals(listOf("Linssoppa", "Kladdkaka"), titles())
        eventually { miniflux.markedRead.isNotEmpty() }
        assertEquals(listOf(listOf(11L)), miniflux.markedRead)

        // Offline the mark waits and goes first with the next refresh.
        miniflux.down = true
        repository.dismiss(12)
        assertEquals(listOf("Kladdkaka"), titles())
        eventually { miniflux.requests.count { it.startsWith("PUT") } >= 2 }
        miniflux.down = false
        assertEquals(Outcome.Success(Unit), repository.refresh())
        assertEquals(listOf(listOf(11L), listOf(12L)), miniflux.markedRead)
        assertEquals(listOf("Kladdkaka"), titles())
        assertTrue(miniflux.posts.getValue(12).read)
    }

    @Test
    fun anImportIsRememberedAndMarkedReadOnlyWhenTheSettingIsOn() = runBlocking {
        setUp()

        repository.markImported(11)
        eventually { miniflux.markedRead.isNotEmpty() }
        assertEquals(listOf(listOf(11L)), miniflux.markedRead)
        assertEquals(Outcome.Success(Unit), repository.refresh())
        // Read in Miniflux now, still in the inbox as "Importerad".
        val imported = repository.entries.first().first { it.id == 11L }
        assertTrue(imported.imported)
        assertEquals(listOf("Pannkakor", "Linssoppa", "Kladdkaka"), titles())

        repository.setMarkReadOnImport(false)
        repository.markImported(12)
        assertTrue(repository.entry(12).first()!!.imported)
        delay(SETTLE_MILLIS)
        assertEquals(listOf(listOf(11L)), miniflux.markedRead)
        assertFalse(miniflux.posts.getValue(12).read)
    }

    @Test
    fun aRefusedKeyStopsTheInboxButNeverTheTandoorSync() = runBlocking {
        setUp()
        miniflux.key = "a-new-key-on-the-server"

        val refused = repository.refresh(background = true)
        assertEquals(InboxProblem.AUTH, InboxFailure.problemOf((refused as Outcome.Failure).error))
        val sent = miniflux.requests.size
        repeat(3) { repository.refresh(background = true) }
        repository.refresh(background = false)
        assertEquals("a refused key sends nothing more", sent, miniflux.requests.size)
        assertEquals(InboxProblem.AUTH, repository.state.first().settings.problem)
        // The posts stay readable.
        assertEquals(3, repository.entries.first().size)
        // Tandoor's guard knows nothing of it.
        assertNull(graph.guard.check(ACCOUNT_ID, background = true))
        assertNull(graph.guard.check(ACCOUNT_ID, background = false))

        // A new key lifts it.
        assertTrue(repository.connect(miniflux.baseUrl, "a-new-key-on-the-server") is Outcome.Success)
        repository.chooseCategories(listOf(recipes))
        assertEquals(3, repository.entries.first().size)
    }

    // --- fix-09: what the inbox's guard learnt outlasts the process ---

    /** The same phone after a process restart: the stores and the posts stay, the guard starts empty. */
    private fun restarted() = DefaultRecipeInboxRepository(
        graph.sources,
        graph.databases,
        store,
        keys,
        miniflux.client(),
        MinifluxGuard(graph.clock),
        graph.clock,
        demo,
        graph.scope,
    )

    @Test
    fun aRefusedKeyStaysStoppedAfterAProcessRestart() = runBlocking {
        setUp()
        miniflux.key = "a-new-key-on-the-server"
        val refused = repository.refresh(background = true)
        assertEquals(InboxProblem.AUTH, InboxFailure.problemOf((refused as Outcome.Failure).error))
        assertTrue(store.records.value.getValue(ACCOUNT_ID).keyRefused)

        val later = restarted()
        val sent = miniflux.requests.size
        // A "read" mark waits, the 4-hourly work runs, the user opens the inbox and the category picker.
        later.dismiss(later.entries.first().first().id)
        delay(SETTLE_MILLIS)
        val background = later.refresh(background = true)
        assertEquals(InboxProblem.AUTH, InboxFailure.problemOf((background as Outcome.Failure).error))
        assertTrue(later.refresh(background = false) is Outcome.Failure)
        assertTrue(later.serverCategories() is Outcome.Failure)
        assertEquals("nothing is sent with the refused key", sent, miniflux.requests.size)
        assertEquals(InboxProblem.AUTH, later.state.first().settings.problem)

        // A new key lifts it, and the waiting mark goes with the next refresh.
        assertTrue(later.connect(miniflux.baseUrl, "a-new-key-on-the-server") is Outcome.Success)
        assertFalse(store.records.value.getValue(ACCOUNT_ID).keyRefused)
        assertEquals(Outcome.Success(Unit), later.refresh(background = true))
        assertEquals(2, later.entries.first().size)
    }

    @Test
    fun aRetryAfterOutlastsAProcessRestart() = runBlocking {
        setUp()
        miniflux.override = { MockResponse.Builder().code(429).addHeader("Retry-After", "600").body("{}").build() }
        val throttled = repository.refresh(background = true)
        assertEquals(InboxProblem.THROTTLED, InboxFailure.problemOf((throttled as Outcome.Failure).error))
        miniflux.override = null

        val later = restarted()
        val sent = miniflux.requests.size
        val held = later.refresh(background = false)
        assertEquals(InboxProblem.THROTTLED, InboxFailure.problemOf((held as Outcome.Failure).error))
        assertEquals("the server asked for ten minutes", sent, miniflux.requests.size)

        graph.clock.advance(11.minutes)
        assertEquals(Outcome.Success(Unit), later.refresh(background = true))
        assertNull(store.records.value.getValue(ACCOUNT_ID).holdUntilMillis)
    }

    @Test
    fun theTandoorGuardNeverStopsTheInbox() = runBlocking {
        setUp()
        // A firewall blocked Tandoor: every Tandoor request waits ...
        graph.guard.record(ACCOUNT_ID, graph.sources.tokenFingerprint(ACCOUNT_ID), TandemError.Blocked)
        assertNotNull(graph.guard.check(ACCOUNT_ID, background = false))
        // ... the inbox still reads Miniflux.
        miniflux.post(15, "Grönsakssoppa", day = 15)
        assertEquals(Outcome.Success(Unit), repository.refresh(background = true))
        assertEquals("Grönsakssoppa", titles().first())
    }

    @Test
    fun serverErrorsTripTheInboxBreakerForTheBackgroundOnly() = runBlocking {
        setUp()
        miniflux.override = { MockResponse.Builder().code(502).body("{}").build() }
        repeat(MinifluxGuard.BREAKER_MAX_ERRORS + 1) { repository.refresh(background = true) }
        miniflux.override = null
        val sent = miniflux.requests.size

        val paused = repository.refresh(background = true)
        assertEquals(InboxProblem.PAUSED, InboxFailure.problemOf((paused as Outcome.Failure).error))
        assertEquals(sent, miniflux.requests.size)
        // What the user starts still goes; after the pause the background does too.
        assertEquals(Outcome.Success(Unit), repository.refresh(background = false))
        graph.clock.advance(31.minutes)
        assertEquals(Outcome.Success(Unit), repository.refresh(background = true))
    }

    @Test
    fun aCategoryDeletedInMinifluxIsSkipped() = runBlocking {
        setUp()
        repository.chooseCategories(listOf(recipes, InboxCategory(99, "Borttagen")))
        assertEquals(listOf("Pannkakor", "Linssoppa", "Kladdkaka"), titles())
        assertNull(repository.state.first().settings.problem)
    }

    @Test
    fun disconnectingAndTheAccountsRemovalForgetEverything() = runBlocking {
        setUp()
        repository.disconnect()
        assertEquals(emptyMap<String, String>(), keys.keys.value)
        assertEquals(emptyMap<String, Any>(), store.records.value)
        assertEquals(emptyList<String>(), titles())
        assertFalse(repository.state.first().settings.connected)

        setUp()
        val schedule = RecipeInboxSchedule(
            graph.sources,
            store,
            keys,
            repository,
            RecordingInboxWork(),
            graph.accountStore,
            graph.health,
            graph.scope,
        )
        schedule.beforeRemoval(graph.account, "token-not-used")
        assertEquals(emptyMap<String, String>(), keys.keys.value)
        assertEquals(emptyMap<String, Any>(), store.records.value)
        assertEquals(emptyList<Any>(), graph.databases.get(DatabaseKind.REAL).inboxDao().getAll(ACCOUNT_ID))
    }

    @Test
    fun theBackgroundRefreshExistsOnlyWhileTheInboxIsOn() = runBlocking {
        val work = RecordingInboxWork()
        RecipeInboxSchedule(graph.sources, store, keys, repository, work, graph.accountStore, graph.health, graph.scope).start()
        delay(SETTLE_MILLIS)
        assertFalse("off by default", work.planned)

        setUp()
        eventually { work.planned }
        repository.chooseCategories(emptyList())
        eventually { !work.planned }
        repository.chooseCategories(listOf(recipes))
        eventually { work.planned }
        graph.sources.source.value = ActiveSource.Demo
        eventually { !work.planned }
    }

    @Test
    fun demoModeHasABuiltInInboxAndNoNetwork() = runBlocking {
        graph.sources.source.value = ActiveSource.Demo

        assertEquals(InboxAvailability.DEMO, repository.state.first().availability)
        assertTrue(repository.state.first().inUse)
        val posts = repository.entries.first()
        assertEquals(3, posts.size)
        assertTrue(posts.all { it.url.startsWith("https://example.com/") })
        repository.dismiss(posts.first().id)
        repository.markImported(posts[1].id)
        assertEquals(2, repository.entries.first().size)
        assertTrue(repository.entry(posts[1].id).first()!!.imported)
        assertEquals(Outcome.Success(Unit), repository.refresh())
        assertEquals(emptyList<String>(), miniflux.requests)
    }

    @Test
    fun withoutAnAccountThereIsNothing() = runBlocking {
        graph.sources.source.value = ActiveSource.None
        assertEquals(InboxAvailability.NO_ACCOUNT, repository.state.first().availability)
        assertEquals(emptyList<String>(), titles())
        assertTrue(repository.connect(miniflux.baseUrl, FakeMiniflux.KEY) is Outcome.Failure)
        assertEquals(emptyList<String>(), miniflux.requests)
    }

    private companion object {
        const val WAIT_MILLIS = 5_000L
        const val POLL_MILLIS = 20L
        const val SETTLE_MILLIS = 300L
    }
}

/** [RecipeInboxWork] that only remembers whether it is planned. */
internal class RecordingInboxWork : RecipeInboxWork {
    @Volatile
    var planned = false

    override fun ensure() {
        planned = true
    }

    override fun cancel() {
        planned = false
    }
}
