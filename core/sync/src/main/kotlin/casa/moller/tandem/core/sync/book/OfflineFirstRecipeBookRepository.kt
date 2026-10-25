package casa.moller.tandem.core.sync.book

import casa.moller.tandem.core.data.DataProblem
import casa.moller.tandem.core.data.book.RecipeBookRepository
import casa.moller.tandem.core.data.permissions.SpacePermissionsRepository
import casa.moller.tandem.core.database.TandemDatabase
import casa.moller.tandem.core.database.TandemDatabaseFactory
import casa.moller.tandem.core.database.entity.KeywordEntity
import casa.moller.tandem.core.database.entity.OutboxEntities
import casa.moller.tandem.core.database.entity.OutboxEntity
import casa.moller.tandem.core.database.entity.OutboxOps
import casa.moller.tandem.core.database.entity.OutboxState
import casa.moller.tandem.core.database.entity.RecipeBookEntity
import casa.moller.tandem.core.database.entity.RecipeBookEntryEntity
import casa.moller.tandem.core.database.entity.RecipeKeywordCrossRef
import casa.moller.tandem.core.database.mapper.toModel
import casa.moller.tandem.core.domain.book.RecipeBookRules
import casa.moller.tandem.core.domain.book.SmartFilter
import casa.moller.tandem.core.model.book.RecipeBook
import casa.moller.tandem.core.model.book.RecipeBookKind
import casa.moller.tandem.core.model.book.RecipeBookStatus
import casa.moller.tandem.core.model.book.WantToCook
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.error.catchingUnexpected
import casa.moller.tandem.core.model.recipe.RecipeOverview
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.account.ActiveSources
import casa.moller.tandem.core.sync.platform.NetworkStatus
import casa.moller.tandem.core.sync.platform.SyncClock
import casa.moller.tandem.core.sync.platform.SyncScheduler
import java.text.Collator
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [RecipeBookRepository] on Room and the outbox (plan 3.5, 3.7, 5.7; feature #17). How the
 * interface's contract is met:
 *
 * - **Books** are the user's own and shared books as last read ([BookSync]) plus books created
 *   here and not sent yet. Their kind comes from `RecipeBookRules` (favourites by name or Kitshn's
 *   tag, "Vill laga" by its filter). Only an OWN plain or favourites book can be changed (plan 3.7):
 *   everything else is refused with `DataProblem.NOT_OWNER` before anything is stored, so the
 *   server never sees a write it would answer with 403.
 * - **Favourites** are one book per person (plan 3.7 A): "Favorit" puts the recipe in the user's own
 *   favourites book, created on first use as "Favoriter (<name>)" and shared with the space's other
 *   users when it is sent (they see it read-only, and the Favourites view merges everyone's).
 * - **"Vill laga"** is the [WantToCook.KEYWORD] keyword on the recipe (plan 3.7 B): every member can
 *   set it (`RECIPE_KEYWORDS`, the whole keyword list is sent, see [RecipeKeywordsOutboxHandler]);
 *   the queued changes overlay the stored keywords until they are sent.
 * - **Smart books** whose filter only names keywords are evaluated on the device; others show what
 *   `GET /api/recipe/?filter=` gave in this process ([SmartBookResults]).
 * - **Guests** get `DataProblem.READ_ONLY` before anything is stored (#42); **demo mode** writes the
 *   demo database and queues nothing ("Vill laga" writes the keyword link itself).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
internal class OfflineFirstRecipeBookRepository @Inject constructor(
    private val sources: ActiveSources,
    private val databases: TandemDatabaseFactory,
    private val permissions: SpacePermissionsRepository,
    private val scheduler: SyncScheduler,
    private val clock: SyncClock,
    private val bookSync: BookSync,
    private val smart: SmartBookResults,
    private val names: BookNames,
    private val network: NetworkStatus,
) : RecipeBookRepository {
    /** Serializes local writes, so two taps never get the same temporary id. */
    private val writes = Mutex()

    // ------------------------------------------------------------------------------------ reads

    override fun observeBooks(): Flow<List<RecipeBook>> = snapshots { _, snapshot ->
        flowOf(snapshot.books.map { it.toModel(snapshot.userId, snapshot.countOf(it)) })
    }

    override fun observeBook(id: Long): Flow<RecipeBook?> = sources.source
        .flatMapLatest { source ->
            if (source !is ActiveSource.Stored) return@flatMapLatest flowOf<RecipeBook?>(null)
            flow { emit(currentBookId(source, id)) }.flatMapLatest { bookId ->
                snapshotOf(source).map { snapshot -> snapshot.book(bookId)?.let { it.toModel(snapshot.userId, snapshot.countOf(it)) } }
            }
        }
        .catch { emit(null) }
        .distinctUntilChanged()

    override fun observeRecipes(bookId: Long): Flow<List<RecipeOverview>> = sources.source
        .flatMapLatest { source ->
            if (source !is ActiveSource.Stored) return@flatMapLatest flowOf(emptyList<RecipeOverview>())
            flow { emit(currentBookId(source, bookId)) }.flatMapLatest { id ->
                snapshotOf(source).flatMapLatest { snapshot ->
                    val book = snapshot.book(id)
                    when {
                        book == null -> flowOf(emptyList<RecipeOverview>())

                        storedKind(book.kind) == RecipeBookKind.SMART && SmartFilter.parse(book.filterSearch)?.localOnly != true ->
                            smartRecipes(source, book)

                        else -> items(source, snapshot.recipeIdsOf(book))
                    }
                }
            }
        }
        .catch { emit(emptyList()) }
        .distinctUntilChanged()

    override fun observeFavorites(): Flow<List<RecipeOverview>> = snapshots { source, snapshot ->
        val favoriteBooks = snapshot.books.filter { storedKind(it.kind) == RecipeBookKind.FAVORITES }.map { it.localId }.toSet()
        items(source, snapshot.entries.filter { it.bookId in favoriteBooks }.map { it.recipeId })
    }

    override fun observeFavoriteIds(): Flow<Set<Long>> = sources.source
        .flatMapLatest { source ->
            if (source !is ActiveSource.Stored) flowOf(emptySet<Long>()) else snapshotOf(source).map { it.ownFavoriteIds() }
        }
        .catch { emit(emptySet()) }
        .distinctUntilChanged()

    override fun observeWantToCook(): Flow<List<RecipeOverview>> = snapshots { source, snapshot ->
        items(source, snapshot.wantToCook.toList())
    }

    override fun observeStatus(recipeId: Long): Flow<RecipeBookStatus> = sources.source
        .flatMapLatest { source ->
            if (source !is ActiveSource.Stored) return@flatMapLatest flowOf(RecipeBookStatus(recipeId))
            snapshotOf(source).map { snapshot ->
                val containing = snapshot.entries.filter { it.recipeId == recipeId }.map { it.bookId }.toSet()
                val books = snapshot.books.filter { it.localId in containing }
                val favorites = books.filter { storedKind(it.kind) == RecipeBookKind.FAVORITES }
                RecipeBookStatus(
                    recipeId = recipeId,
                    isFavorite = favorites.any { it.createdBy == snapshot.userId },
                    favoriteOf = favorites.filter { it.createdBy != snapshot.userId }.mapNotNull { it.createdByName }.distinct(),
                    wantToCook = recipeId in snapshot.wantToCook,
                    bookIds = books.map { it.localId }.toSet(),
                )
            }
        }
        .catch { emit(RecipeBookStatus(recipeId)) }
        .distinctUntilChanged()

    override suspend fun refresh(): Outcome<Unit> = catchingUnexpected {
        when (val source = sources.current()) {
            is ActiveSource.Remote -> {
                if (!network.online.value) return@catchingUnexpected Outcome.Failure(TandemError.NetworkUnavailable)
                bookSync.sync(source, background = false)
                Outcome.Success(Unit)
            }

            ActiveSource.Demo -> Outcome.Success(Unit)

            ActiveSource.Loading, ActiveSource.None -> Outcome.Failure(DataProblem.NO_ACCOUNT.toError())
        }
    }

    // ------------------------------------------------------------------------------------ writes

    override suspend fun createBook(name: String, description: String): Outcome<Long> {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return Outcome.Failure(TandemError.BadRequest(mapOf("name" to listOf("blank"))))
        return write { context -> Outcome.Success(context.createBook(trimmed, description.trim(), shareWithSpace = false).localId) }
    }

    override suspend fun addToBook(bookId: Long, recipeId: Long): Outcome<Unit> = write { context ->
        val book = context.book(bookId) ?: return@write Outcome.Failure(TandemError.NotFound)
        if (!context.canEdit(book)) return@write Outcome.Failure(DataProblem.NOT_OWNER.toError())
        context.addEntry(book, recipeId)
        Outcome.Success(Unit)
    }

    override suspend fun removeFromBook(bookId: Long, recipeId: Long): Outcome<Unit> = write { context ->
        val book = context.book(bookId) ?: return@write Outcome.Success(Unit)
        if (!context.canEdit(book)) return@write Outcome.Failure(DataProblem.NOT_OWNER.toError())
        context.removeEntries(book, recipeId)
        Outcome.Success(Unit)
    }

    override suspend fun setFavorite(recipeId: Long, favorite: Boolean): Outcome<Unit> = write { context ->
        val own = context.ownFavoriteBooks()
        if (favorite) {
            val book = own.firstOrNull() ?: context.createBook(context.favoritesName(), "", shareWithSpace = true)
            context.addEntry(book, recipeId)
        } else {
            own.forEach { context.removeEntries(it, recipeId) }
        }
        Outcome.Success(Unit)
    }

    override suspend fun setWantToCook(recipeId: Long, wantToCook: Boolean): Outcome<Unit> {
        // Already so (stored or queued): nothing to queue. A guest still gets READ_ONLY from write.
        if (!readOnly() && observeStatus(recipeId).first().wantToCook == wantToCook) return Outcome.Success(Unit)
        return setWantToCookNow(recipeId, wantToCook)
    }

    private suspend fun setWantToCookNow(recipeId: Long, wantToCook: Boolean): Outcome<Unit> = write { context ->
        context.setWantToCook(recipeId, wantToCook)
        Outcome.Success(Unit)
    }

    // ------------------------------------------------------------------------------------ helpers

    private suspend fun readOnly(): Boolean = permissions.permissions.first().readOnly

    /**
     * Runs a local change for the active source in one transaction ([WriteContext]); guests are
     * refused first. Schedules the outbox for a real account when something was queued.
     */
    private suspend fun <T> write(block: suspend (WriteContext) -> Outcome<T>): Outcome<T> = catchingUnexpected {
        when (val source = sources.current()) {
            is ActiveSource.Stored -> {
                if (source is ActiveSource.Remote && readOnly()) return@catchingUnexpected Outcome.Failure(DataProblem.READ_ONLY.toError())
                val database = databases.get(source.kind)
                val result = writes.withLock {
                    var value: Outcome<T>? = null
                    database.recipeBookDao().inTransaction { value = block(WriteContext(source, database, clock.nowMillis())) }
                    @Suppress("UNCHECKED_CAST") // Set by the block before the transaction returns.
                    value as Outcome<T>
                }
                if (source is ActiveSource.Remote && result is Outcome.Success) scheduler.scheduleOutbox()
                result
            }

            ActiveSource.Loading, ActiveSource.None -> Outcome.Failure(DataProblem.NO_ACCOUNT.toError())
        }
    }

    /** [flow] for the active source's [Snapshot]s; empty without a source. */
    private fun <T> snapshots(flow: (ActiveSource.Stored, Snapshot) -> Flow<List<T>>): Flow<List<T>> = sources.source
        .flatMapLatest { source ->
            if (source is ActiveSource.Stored) snapshotOf(source).flatMapLatest { flow(source, it) } else flowOf(emptyList())
        }
        .catch { emit(emptyList()) }
        .distinctUntilChanged()

    /** Books, entries, "Vill laga" and the keyword links of [source], updated on every change. */
    private fun snapshotOf(source: ActiveSource.Stored): Flow<Snapshot> {
        val dao = databases.get(source.kind).recipeBookDao()
        val accountId = source.accountId
        val outbox = if (source is ActiveSource.Remote) dao.observeOutbox(accountId) else flowOf(emptyList())
        return combine(
            dao.observeBooks(accountId),
            dao.observeAllEntries(accountId),
            dao.observeKeywordIdsNamed(accountId, WantToCook.KEYWORD),
            dao.observeRecipeKeywords(accountId),
            outbox,
        ) { books, entries, wantKeywordIds, links, queued ->
            val keywordsByRecipe = links.groupBy({ it.recipeId }, { it.keywordId }).mapValues { it.value.toSet() }
            val wantKeywords = wantKeywordIds.toSet()
            val want = keywordsByRecipe.filterValues { ids -> ids.any { it in wantKeywords } }.keys.toMutableSet()
            // Queued "Vill laga" changes win until they are sent (the last one per recipe).
            queued.filter { it.op == OutboxOps.RECIPE_KEYWORDS }.forEach { row ->
                val recipeId = row.serverId ?: return@forEach
                when (BookPayloads.wantToCookOf(row.payload)) {
                    true -> want += recipeId
                    false -> want -= recipeId
                    null -> Unit
                }
            }
            Snapshot(userId = source.userId(), books = books, entries = entries, wantToCook = want, keywordsByRecipe = keywordsByRecipe)
        }.distinctUntilChanged()
    }

    /** The stored recipes [ids], sorted by name in the UI's language (each once). */
    private fun items(source: ActiveSource.Stored, ids: List<Long>): Flow<List<RecipeOverview>> {
        val distinct = ids.distinct()
        if (distinct.isEmpty()) return flowOf(emptyList())
        val collator = Collator.getInstance(Locale.getDefault())
        return databases.get(source.kind).recipeBookDao().observeListItems(source.accountId, distinct)
            .map { rows -> rows.map { it.toModel() }.sortedWith { a, b -> collator.compare(a.name, b.name) } }
    }

    /** A smart book the device cannot evaluate: what the server gave in this process, read once when online. */
    private fun smartRecipes(source: ActiveSource.Stored, book: RecipeBookEntity): Flow<List<RecipeOverview>> =
        smart.observe(source.accountId, book.localId)
            .onStart {
                val filterId = book.filterId
                if (source is ActiveSource.Remote && filterId != null && network.online.value) {
                    val read = bookSync.fetchSmart(source, background = false, filterId = filterId)
                    if (read is Outcome.Success) smart.put(source.accountId, book.localId, read.value)
                }
            }
            .flatMapLatest { ids -> items(source, ids.orEmpty()) }

    private suspend fun currentBookId(source: ActiveSource.Stored, id: Long): Long =
        databases.get(source.kind).idRewriteDao().current(source.accountId, OutboxEntities.RECIPE_BOOK, id)

    /** The user id that owns "own" books: the account's, or the demo user's. */
    private fun ActiveSource.Stored.userId(): Long? = when (this) {
        is ActiveSource.Remote -> account.userId
        ActiveSource.Demo -> DemoBooks.OWN_USER_ID
    }

    /** Everything the reads need, from one consistent set of rows. */
    private data class Snapshot(
        val userId: Long?,
        val books: List<RecipeBookEntity>,
        val entries: List<RecipeBookEntryEntity>,
        val wantToCook: Set<Long>,
        val keywordsByRecipe: Map<Long, Set<Long>>,
    ) {
        fun book(id: Long): RecipeBookEntity? = books.firstOrNull { it.localId == id }

        fun ownFavoriteIds(): Set<Long> {
            val own = books.filter { storedKind(it.kind) == RecipeBookKind.FAVORITES && it.createdBy == userId }.map { it.localId }.toSet()
            return entries.filter { it.bookId in own }.map { it.recipeId }.toSet()
        }

        /** The recipes of [book] that the device can name (entries, or the smart filter evaluated here). */
        fun recipeIdsOf(book: RecipeBookEntity): List<Long> = when (storedKind(book.kind)) {
            RecipeBookKind.BOOK, RecipeBookKind.FAVORITES -> entries.filter { it.bookId == book.localId }.map { it.recipeId }

            RecipeBookKind.WANT_TO_COOK -> wantToCook.toList()

            RecipeBookKind.SMART -> SmartFilter.parse(book.filterSearch)
                ?.takeIf { it.localOnly }
                ?.let { filter -> keywordsByRecipe.filterValues { filter.matches(it) }.keys.toList() }
                .orEmpty()
        }

        fun countOf(book: RecipeBookEntity): Int = recipeIdsOf(book).distinct().size
    }

    /** One local change: the source, its database and "now"; queues only for a real account. */
    private inner class WriteContext(val source: ActiveSource.Stored, val database: TandemDatabase, val now: Long) {
        private val accountId: String = source.accountId
        private val remote: ActiveSource.Remote? = source as? ActiveSource.Remote
        private val dao = database.recipeBookDao()
        private val userId: Long? = source.userId()

        suspend fun book(id: Long): RecipeBookEntity? =
            dao.getBook(accountId, database.idRewriteDao().current(accountId, OutboxEntities.RECIPE_BOOK, id))

        /** Plan 3.7: only an own plain or favourites book. */
        fun canEdit(book: RecipeBookEntity): Boolean = book.createdBy == userId &&
            storedKind(book.kind).let { it == RecipeBookKind.BOOK || it == RecipeBookKind.FAVORITES }

        suspend fun ownFavoriteBooks(): List<RecipeBookEntity> =
            dao.getBooks(accountId).filter { storedKind(it.kind) == RecipeBookKind.FAVORITES && it.createdBy == userId }

        fun favoritesName(): String = RecipeBookRules.favoritesBookName(names.favoritesWord, remote?.account?.label.orEmpty())

        suspend fun createBook(name: String, description: String, shareWithSpace: Boolean): RecipeBookEntity {
            val localId = database.idRewriteDao().nextLocalId(accountId, OutboxEntities.RECIPE_BOOK)
            val kind = RecipeBookRules.kind(name, description, hasFilter = false, filterSearch = null, wantToCookKeywordIds = emptySet())
            val book = RecipeBookEntity(
                accountId = accountId,
                localId = localId,
                serverId = null,
                name = name,
                description = description,
                createdBy = userId,
                createdByName = remote?.account?.label,
                kind = kind.toStored(),
            )
            dao.upsertBooks(listOf(book))
            if (remote != null) {
                database.outboxDao().enqueue(
                    OutboxEntity(
                        accountId = accountId,
                        op = OutboxOps.CREATE,
                        entity = OutboxEntities.RECIPE_BOOK,
                        localId = localId,
                        payload = BookPayloads.createBook(name, description, shareWithSpace),
                        createdAtEpochMillis = now,
                    ),
                )
            }
            return book
        }

        /** Adds [recipeId] to [book] (nothing when it is there already). */
        suspend fun addEntry(book: RecipeBookEntity, recipeId: Long) {
            if (dao.findEntries(accountId, book.localId, recipeId).isNotEmpty()) return
            val localId = database.idRewriteDao().nextLocalId(accountId, OutboxEntities.RECIPE_BOOK_ENTRY)
            val entry = RecipeBookEntryEntity(accountId, localId, serverId = null, bookId = book.localId, recipeId = recipeId)
            dao.upsertEntries(listOf(entry))
            if (remote == null) return
            val bookRef = book.serverId ?: book.localId
            database.outboxDao().enqueue(
                OutboxEntity(
                    accountId = accountId,
                    op = OutboxOps.CREATE,
                    entity = OutboxEntities.RECIPE_BOOK_ENTRY,
                    localId = localId,
                    payload = BookPayloads.createEntry(bookRef, recipeId),
                    refs = BookPayloads.createEntryRefs(bookRef),
                    dependsOn = if (book.serverId == null) createRowOf(OutboxEntities.RECIPE_BOOK, book.localId)?.id else null,
                    createdAtEpochMillis = now,
                ),
            )
        }

        /** Removes [recipeId] from [book] here and queues the DELETEs (an unsent CREATE is cancelled instead). */
        suspend fun removeEntries(book: RecipeBookEntity, recipeId: Long) {
            for (entry in dao.findEntries(accountId, book.localId, recipeId)) {
                dao.deleteEntries(accountId, listOf(entry.localId))
                if (remote == null) continue
                val serverId = entry.serverId
                database.outboxDao().enqueue(
                    OutboxEntity(
                        accountId = accountId,
                        op = OutboxOps.DELETE,
                        entity = OutboxEntities.RECIPE_BOOK_ENTRY,
                        localId = if (serverId == null) entry.localId else null,
                        serverId = serverId,
                        payload = "{}",
                        dependsOn = if (serverId == null) sendingCreateOf(OutboxEntities.RECIPE_BOOK_ENTRY, entry.localId) else null,
                        createdAtEpochMillis = now,
                    ),
                )
            }
        }

        /** "Vill laga": queued for the server, or the keyword link itself in demo mode. */
        suspend fun setWantToCook(recipeId: Long, want: Boolean) {
            val keywordIds = dao.getKeywordIdsNamed(accountId, WantToCook.KEYWORD)
            if (remote == null) {
                if (want) {
                    val keywordId = keywordIds.firstOrNull() ?: (dao.getMaxKeywordId(accountId) + 1).also { id ->
                        dao.insertKeywordIfAbsent(KeywordEntity(accountId, id, WantToCook.KEYWORD))
                    }
                    dao.insertKeywordRef(RecipeKeywordCrossRef(accountId, recipeId, keywordId))
                } else if (keywordIds.isNotEmpty()) {
                    dao.deleteKeywordRefs(accountId, recipeId, keywordIds)
                }
                return
            }
            database.outboxDao().enqueue(
                OutboxEntity(
                    accountId = accountId,
                    op = OutboxOps.RECIPE_KEYWORDS,
                    entity = OutboxEntities.RECIPE,
                    serverId = recipeId,
                    payload = BookPayloads.wantToCook(want),
                    baseSnapshot = BookPayloads.wantToCook(!want),
                    createdAtEpochMillis = now,
                ),
            )
        }

        /** The CREATE row of temporary id [localId] (any state), if it is still in the outbox. */
        private suspend fun createRowOf(entity: String, localId: Long): OutboxEntity? =
            database.outboxDao().getAllFor(accountId, entity).firstOrNull { it.op == OutboxOps.CREATE && it.localId == localId }

        /** The CREATE a change of an offline object must wait for: only one being sent (an unsent one merges). */
        private suspend fun sendingCreateOf(entity: String, localId: Long): Long? =
            createRowOf(entity, localId)?.takeIf { it.state !in UNSENT }?.id
    }

    private companion object {
        /** States of an outbox row that `OutboxDao.enqueue` merges with. */
        val UNSENT: Set<OutboxState> = setOf(OutboxState.PENDING, OutboxState.PAUSED)
    }
}
