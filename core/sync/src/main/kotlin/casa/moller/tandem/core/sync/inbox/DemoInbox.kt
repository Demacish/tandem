package casa.moller.tandem.core.sync.inbox

import casa.moller.tandem.core.model.inbox.InboxEntry
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * Fas 5 (rss lane): demo mode's small built-in recipe inbox (plan §4.5 "demoläge"). Three posts in
 * the app's language (Swedish or English), never the network; "Markera som läst" and an import change
 * only this process's memory, so a restart shows them again. The addresses are on example.com: the
 * import in demo mode says that it needs a server, as every import does.
 */
@Singleton
internal class DemoInbox @Inject constructor() {
    private data class Marks(val dismissed: Set<Long> = emptySet(), val imported: Map<Long, Instant> = emptyMap())

    private val marks = MutableStateFlow(Marks())

    val entries: Flow<List<InboxEntry>> = marks.map { current ->
        posts(Locale.getDefault())
            .filter { it.id !in current.dismissed }
            .map { post -> post.copy(importedAt = current.imported[post.id]) }
    }

    fun entry(id: Long): Flow<InboxEntry?> = entries.map { list -> list.firstOrNull { it.id == id } }

    fun dismiss(id: Long) {
        marks.update { it.copy(dismissed = it.dismissed + id) }
    }

    fun markImported(id: Long, at: Instant) {
        marks.update { it.copy(imported = it.imported + (id to at)) }
    }

    private fun posts(locale: Locale): List<InboxEntry> {
        val swedish = locale.language == "sv"
        fun text(sv: String, en: String) = if (swedish) sv else en
        val feed = text("Exempelköket", "The Example Kitchen")
        return listOf(
            InboxEntry(
                id = 3,
                title = text("Pannkakor med blåbär", "Blueberry pancakes"),
                url = "https://example.com/recipes/blueberry-pancakes",
                feedTitle = feed,
                publishedAt = Instant.parse("2026-11-13T08:00:00Z"),
                excerpt = text(
                    "Tunna pannkakor med blåbär och grädde, klara på en halvtimme.",
                    "Thin pancakes with blueberries and cream, ready in half an hour.",
                ),
            ),
            InboxEntry(
                id = 2,
                title = text("Linssoppa med kokosmjölk", "Lentil soup with coconut milk"),
                url = "https://example.com/recipes/lentil-soup",
                feedTitle = feed,
                publishedAt = Instant.parse("2026-11-12T08:00:00Z"),
                excerpt = text(
                    "En värmande soppa på röda linser, ingefära och kokosmjölk.",
                    "A warming soup of red lentils, ginger and coconut milk.",
                ),
            ),
            InboxEntry(
                id = 1,
                title = text("Kladdkaka", "Sticky chocolate cake"),
                url = "https://example.com/recipes/sticky-chocolate-cake",
                feedTitle = text("Bakbloggen", "The Baking Blog"),
                publishedAt = Instant.parse("2026-11-11T08:00:00Z"),
                excerpt = text("Seg chokladkaka som alla tycker om.", "A gooey chocolate cake everyone likes."),
            ),
        )
    }
}
