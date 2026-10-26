package casa.moller.tandem.core.sync.book

import casa.moller.tandem.core.database.TandemDatabase
import casa.moller.tandem.core.database.TandemDatabaseFactory
import casa.moller.tandem.core.database.entity.OutboxEntities
import casa.moller.tandem.core.database.entity.OutboxOps
import casa.moller.tandem.core.database.entity.RecipeBookKinds
import casa.moller.tandem.core.domain.book.SmartFilter
import casa.moller.tandem.core.model.book.WantToCook
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.network.api.RecipeListQuery
import casa.moller.tandem.core.network.api.TandoorApi
import casa.moller.tandem.core.network.api.list
import casa.moller.tandem.core.network.dto.PageDto
import casa.moller.tandem.core.network.paging.PagedResult
import casa.moller.tandem.core.network.paging.Paginator
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.account.ActiveSources
import casa.moller.tandem.core.sync.account.identity
import casa.moller.tandem.core.sync.permissions.SpaceRoleSync
import casa.moller.tandem.core.sync.remote.RemoteCaller
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The books' part of the small tables (plan 3.5 "Små tabeller": full re-read with the other small
 * tables, at start at most once an hour and every 6 hours; plan 3.7, feature #17):
 *
 * - every visible book (own and shared, `GET /api/recipe-book/`) and every entry of them
 *   (`GET /api/recipe-book-entry/`) replace the stored server rows; books and entries created on
 *   this device and not sent yet stay, and queued deletes are applied again, so nothing jumps
 *   back before it is sent. A list that was not read completely replaces nothing.
 * - the kind of each book (favourites, "Vill laga", smart, plain) comes from `RecipeBookRules`.
 * - a smart book whose filter the device cannot evaluate (anything but keyword ids) gets its
 *   recipes from `GET /api/recipe/?filter=<id>`, kept in memory for this process
 *   ([SmartBookResults]); offline after a restart it shows nothing and says why.
 * - [lock] is held from before the two reads until the rows are replaced, and by the book outbox
 *   handlers while they send (0.3.0 review, fix-data, like `ShoppingReader.lock`): an answer built
 *   before a CREATE that is answered meanwhile never deletes the new favourite or entry. Lock
 *   order: `OutboxSender`'s mutex, then [lock]; a book read never drains.
 */
@Singleton
internal class BookSync @Inject constructor(
    private val databases: TandemDatabaseFactory,
    private val caller: RemoteCaller,
    private val sources: ActiveSources,
    private val smart: SmartBookResults,
) {
    /** Held by the books' read and by the book outbox handlers while they send. */
    val lock = Mutex()

    suspend fun sync(remote: ActiveSource.Remote, background: Boolean) {
        val database = databases.get(remote.kind)
        val accountId = remote.accountId
        val dao = database.recipeBookDao()
        lock.withLock {
            val books = fetchAll(remote, background) { page -> taxonomy.recipeBooks(page) }
            val entries = fetchAll(remote, background) { page -> taxonomy.recipeBookEntries(book = null, page = page) }
            if (!sameSource(remote)) return
            val bookList = (books as? Outcome.Success)?.value?.takeIf { it.complete }?.items
            val entryList = (entries as? Outcome.Success)?.value?.takeIf { it.complete }?.items
            if (bookList != null && entryList != null) {
                val wantIds = dao.getKeywordIdsNamed(accountId, WantToCook.KEYWORD).toSet()
                dao.inTransaction {
                    dao.replaceServerRows(
                        accountId,
                        books = bookList.mapNotNull { it.toEntity(accountId, wantIds) },
                        entries = entryList.mapNotNull { it.toEntity(accountId) },
                    )
                    reapplyQueued(database, accountId)
                }
            }
        }
        val serverSmart = dao.getBooks(accountId).filter { book ->
            book.kind == RecipeBookKinds.SMART && book.filterId != null && SmartFilter.parse(book.filterSearch)?.localOnly != true
        }
        for (book in serverSmart) {
            val recipes = fetchSmart(remote, background, book.filterId ?: continue)
            if (recipes is Outcome.Success && sameSource(remote)) smart.put(accountId, book.localId, recipes.value)
        }
    }

    /** The recipe ids of a smart book's filter, read now (also when the user opens the book). */
    suspend fun fetchSmart(remote: ActiveSource.Remote, background: Boolean, filterId: Long): Outcome<List<Long>> {
        val result = caller.call(remote, background) {
            val api = this
            Paginator.fetchAll(maxPages = MAX_SMART_PAGES) { page ->
                api.recipes.list(RecipeListQuery(customFilter = filterId), page = page.takeIf { it > 1 })
            }
        }
        return when (result) {
            is Outcome.Failure -> result
            is Outcome.Success -> Outcome.Success(result.value.items.mapNotNull { it.id })
        }
    }

    /** Applies queued deletes of books and entries to the stored rows again (after a re-read). */
    suspend fun reapplyQueued(database: TandemDatabase, accountId: String) {
        val dao = database.recipeBookDao()
        for (row in dao.getOutbox(accountId).filter { it.op == OutboxOps.DELETE }) {
            val id = row.serverId ?: row.localId ?: continue
            when (row.entity) {
                OutboxEntities.RECIPE_BOOK_ENTRY -> dao.deleteEntries(accountId, listOf(id))
                OutboxEntities.RECIPE_BOOK -> dao.deleteBookWithEntries(accountId, id)
            }
        }
    }

    private suspend fun <T> fetchAll(
        remote: ActiveSource.Remote,
        background: Boolean,
        fetch: suspend TandoorApi.(page: Int?) -> PageDto<T>,
    ): Outcome<PagedResult<T>> = caller.call(remote, background) {
        val api = this
        Paginator.fetchAll { page -> api.fetch(page.takeIf { it > 1 }) }
    }

    private fun sameSource(remote: ActiveSource.Remote): Boolean = sources.source.value.identity == remote.identity

    private companion object {
        /** At most 5 × 100 recipes per smart book (plan 4.1: few requests). */
        const val MAX_SMART_PAGES: Int = 5
    }
}

/** The recipes of smart books the device cannot evaluate, as last read from the server (this process only). */
@Singleton
internal class SmartBookResults @Inject constructor() {
    private val results = MutableStateFlow<Map<Pair<String, Long>, List<Long>>>(emptyMap())

    fun put(accountId: String, bookId: Long, recipeIds: List<Long>) {
        results.update { it + ((accountId to bookId) to recipeIds) }
    }

    /** The recipe ids of book [bookId], null while never read in this process. */
    fun observe(accountId: String, bookId: Long): Flow<List<Long>?> = results.map { it[accountId to bookId] }
}

/**
 * The books lane's steps of a sync run (`SyncEngine`): [role] before the shopping list, [run] (the
 * books, [BookSync]) after it, both with the small tables.
 */
internal class BookSyncStep @Inject constructor(
    private val roles: SpaceRoleSync,
    private val books: BookSync,
    private val databases: TandemDatabaseFactory,
) {
    /**
     * The user's role in the space (guest mode #42, [SpaceRoleSync]) with the small tables, and in
     * every run while it is not known yet (0.3.0 review, fix-data): it is read BEFORE the shopping
     * list and the outbox, so nothing a guest queued before the role was known is ever sent.
     */
    suspend fun role(remote: ActiveSource.Remote, background: Boolean, smallTables: Boolean) {
        val unknown = databases.get(remote.kind).accountDao().observeGroupLevel(remote.accountId).first() == null
        if (smallTables || unknown) roles.sync(remote, background)
    }

    suspend fun run(remote: ActiveSource.Remote, background: Boolean, smallTables: Boolean) {
        if (!smallTables) return
        books.sync(remote, background)
    }
}
