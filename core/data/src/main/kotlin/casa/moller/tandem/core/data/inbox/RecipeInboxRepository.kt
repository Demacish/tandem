package casa.moller.tandem.core.data.inbox

import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.inbox.InboxCategory
import casa.moller.tandem.core.model.inbox.InboxEntry
import casa.moller.tandem.core.model.inbox.InboxProblem
import casa.moller.tandem.core.model.inbox.InboxState
import kotlinx.coroutines.flow.Flow

/**
 * Fas 5 (rss lane, plan §7 #35, §8.4): "Inkorg för recept", posts from the user's own Miniflux
 * server (any Miniflux, nothing server-specific in the app, plan §4.5). Off until the user sets it
 * up under Mer; implemented in core:sync (`DefaultRecipeInboxRepository`). Main-safe; flows never
 * throw.
 *
 * - **Per account.** The server address, the API key (encrypted, never logged, never backed up)
 *   and the chosen categories belong to the active Tandoor account and go with it (logout, removal).
 * - **Offline first.** The posts are cached in Room: [entries] shows the last ones read offline;
 *   [refresh] reads the unread posts of the chosen categories (when the screen opens, on pull to
 *   refresh, and a few times a day in the background while the inbox is on).
 * - **Its own guard.** Miniflux requests have their own timeouts, rate limit and circuit breaker
 *   (a refused key stops them until a new key is entered); a Miniflux failure never touches the
 *   Tandoor sync's guard, and the other way round.
 * - **Two taps.** A post opens its preview; "Importera" opens the import review (#18) with its
 *   address. [markImported] remembers it as "Importerad" and, when the setting is on, marks it read
 *   in Miniflux.
 * - **Demo mode** has a small built-in inbox and never uses the network; guests may read the inbox
 *   (importing is what they cannot do, the import refuses it).
 */
interface RecipeInboxRepository {
    /** The settings and where the inbox can be used. */
    val state: Flow<InboxState>

    /** The posts to show, newest first: unread ones and the ones imported here (not dismissed). */
    val entries: Flow<List<InboxEntry>>

    /** One post (null when it is gone). */
    fun entry(id: Long): Flow<InboxEntry?>

    /**
     * Checks [serverUrl] and [apiKey] against the server (`/v1/me`, then the categories) and stores
     * them for the active account only when the server took the key. Returns the server's categories
     * with their unread counts. Failures are [InboxFailure]s.
     */
    suspend fun connect(serverUrl: String, apiKey: String): Outcome<List<InboxCategory>>

    /** The server's categories with their unread counts (with the stored key). */
    suspend fun serverCategories(): Outcome<List<InboxCategory>>

    /** Chooses the categories to read; at least one turns the inbox on (and reads them), none turns it off. */
    suspend fun chooseCategories(categories: List<InboxCategory>): Outcome<Unit>

    suspend fun setMarkReadOnImport(enabled: Boolean)

    suspend fun setShowOnToday(enabled: Boolean)

    /** "Koppla från": forgets the key, the address, the categories and the cached posts of the active account. */
    suspend fun disconnect()

    /**
     * Sends the waiting "read" marks and reads the unread posts of the chosen categories.
     *
     * @param background true for the periodic work (the guard's breaker holds it back).
     */
    suspend fun refresh(background: Boolean = false): Outcome<Unit>

    /** "Markera som läst": hides the post here and marks it read in Miniflux (now, or at the next refresh when offline). */
    suspend fun dismiss(id: Long)

    /** The post was imported (accepted in the review): "Importerad", and read in Miniflux when `InboxSettings.markReadOnImport`. */
    suspend fun markImported(id: Long)
}

/** A Miniflux failure as the inbox reports it: [problem] says what the user can do. */
fun inboxFailure(problem: InboxProblem): Outcome.Failure = Outcome.Failure(InboxFailure.errorOf(problem))

/** Recognises the inbox's failures among [TandemError]s. */
object InboxFailure {
    private const val PREFIX = "tandem.inbox."

    fun errorOf(problem: InboxProblem): TandemError = TandemError.Unknown(PREFIX + problem.name)

    /** The [InboxProblem] [error] stands for, [InboxProblem.OTHER] for anything else. */
    fun problemOf(error: TandemError): InboxProblem {
        val detail = (error as? TandemError.Unknown)?.detail
        return InboxProblem.entries.firstOrNull { detail == PREFIX + it.name } ?: InboxProblem.OTHER
    }
}
