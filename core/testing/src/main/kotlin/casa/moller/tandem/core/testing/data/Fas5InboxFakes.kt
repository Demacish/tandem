package casa.moller.tandem.core.testing.data

import casa.moller.tandem.core.data.inbox.RecipeInboxRepository
import casa.moller.tandem.core.data.inbox.inboxFailure
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.inbox.InboxAvailability
import casa.moller.tandem.core.model.inbox.InboxCategory
import casa.moller.tandem.core.model.inbox.InboxEntry
import casa.moller.tandem.core.model.inbox.InboxProblem
import casa.moller.tandem.core.model.inbox.InboxSettings
import casa.moller.tandem.core.model.inbox.InboxState
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

// Fas 5 (rss lane, plan §7 #35): the recipe inbox in memory, for the screens' tests and screenshots.

/** Sample posts and settings (example.com addresses, nothing real). */
object SampleInbox {
    val recipes = InboxCategory(2, "Recept", unread = 3)
    val news = InboxCategory(3, "Nyheter", unread = 12)
    val desserts = InboxCategory(4, "Efterrätter", unread = 1)

    /** Friday 13 November 2026, 08:00 UTC. */
    val refreshed: Instant = Instant.parse("2026-11-13T08:00:00Z")

    val posts: List<InboxEntry> = listOf(
        InboxEntry(
            id = 11,
            title = "Pannkakor med blåbär och vispad grädde",
            url = "https://recipes.example.com/recept/pannkakor",
            feedTitle = "Exempelköket",
            categoryId = 2,
            publishedAt = Instant.parse("2026-11-13T07:30:00Z"),
            excerpt = "Tunna pannkakor med blåbär och grädde, klara på en halvtimme. Gräddas i smör i en varm panna.",
        ),
        InboxEntry(
            id = 12,
            title = "Linssoppa",
            url = "https://recipes.example.com/recept/linssoppa",
            feedTitle = "Exempelköket",
            categoryId = 2,
            publishedAt = Instant.parse("2026-11-12T08:00:00Z"),
            excerpt = "En värmande soppa på röda linser.",
        ),
        InboxEntry(
            id = 13,
            title = "Kladdkaka",
            url = "https://baking.example.com/kladdkaka",
            feedTitle = "Bakbloggen",
            categoryId = 4,
            publishedAt = Instant.parse("2026-11-10T08:00:00Z"),
            excerpt = "Seg chokladkaka.",
            importedAt = Instant.parse("2026-11-12T19:00:00Z"),
        ),
    )

    val settings = InboxSettings(
        connected = true,
        serverUrl = "https://rss.example.com/",
        categories = listOf(InboxCategory(2, "Recept"), InboxCategory(4, "Efterrätter")),
        lastRefresh = refreshed,
    )

    val inUse = InboxState(InboxAvailability.AVAILABLE, settings)

    val notSetUp = InboxState(InboxAvailability.AVAILABLE, InboxSettings())

    val demo = InboxState(InboxAvailability.DEMO)
}

/**
 * [RecipeInboxRepository] in memory: [connect] takes [acceptedKey], [refreshFailure] makes refreshes
 * fail, and every call is listed in [calls].
 */
class FakeRecipeInboxRepository(initial: InboxState = SampleInbox.inUse, posts: List<InboxEntry> = SampleInbox.posts) :
    RecipeInboxRepository {
    val mutableState = MutableStateFlow(initial)
    val posts = MutableStateFlow(posts)
    val calls = mutableListOf<String>()
    var serverCategories: List<InboxCategory> = listOf(SampleInbox.recipes, SampleInbox.news, SampleInbox.desserts)
    var acceptedKey: String = "miniflux-test-key"
    var refreshFailure: InboxProblem? = null
    var categoriesFailure: InboxProblem? = null

    override val state: Flow<InboxState> = mutableState

    override val entries: Flow<List<InboxEntry>> = this.posts

    override fun entry(id: Long): Flow<InboxEntry?> = posts.map { list -> list.firstOrNull { it.id == id } }

    override suspend fun connect(serverUrl: String, apiKey: String): Outcome<List<InboxCategory>> {
        calls += "connect $serverUrl"
        if (!serverUrl.startsWith("https://")) return inboxFailure(InboxProblem.INVALID_URL)
        if (apiKey != acceptedKey) return inboxFailure(InboxProblem.AUTH)
        settings { it.copy(connected = true, serverUrl = serverUrl, problem = null) }
        return Outcome.Success(serverCategories)
    }

    override suspend fun serverCategories(): Outcome<List<InboxCategory>> {
        calls += "categories"
        categoriesFailure?.let { return inboxFailure(it) }
        return Outcome.Success(serverCategories)
    }

    override suspend fun chooseCategories(categories: List<InboxCategory>): Outcome<Unit> {
        calls += "choose ${categories.map { it.id }}"
        settings { it.copy(categories = categories.map { c -> InboxCategory(c.id, c.title) }) }
        return Outcome.Success(Unit)
    }

    override suspend fun setMarkReadOnImport(enabled: Boolean) {
        calls += "markRead $enabled"
        settings { it.copy(markReadOnImport = enabled) }
    }

    override suspend fun setShowOnToday(enabled: Boolean) {
        calls += "today $enabled"
        settings { it.copy(showOnToday = enabled) }
    }

    override suspend fun disconnect() {
        calls += "disconnect"
        settings { InboxSettings() }
        posts.value = emptyList()
    }

    override suspend fun refresh(background: Boolean): Outcome<Unit> {
        calls += "refresh"
        refreshFailure?.let { problem ->
            settings { it.copy(problem = problem) }
            return inboxFailure(problem)
        }
        settings { it.copy(problem = null) }
        return Outcome.Success(Unit)
    }

    override suspend fun dismiss(id: Long) {
        calls += "dismiss $id"
        posts.update { list -> list.filterNot { it.id == id } }
    }

    override suspend fun markImported(id: Long) {
        calls += "imported $id"
        posts.update { list -> list.map { if (it.id == id) it.copy(importedAt = SampleInbox.refreshed) else it } }
    }

    private fun settings(transform: (InboxSettings) -> InboxSettings) {
        mutableState.update { it.copy(settings = transform(it.settings)) }
    }
}
