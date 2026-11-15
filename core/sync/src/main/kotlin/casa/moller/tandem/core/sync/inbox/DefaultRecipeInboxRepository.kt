package casa.moller.tandem.core.sync.inbox

import casa.moller.tandem.core.data.DataProblem
import casa.moller.tandem.core.data.di.ApplicationScope
import casa.moller.tandem.core.data.inbox.InboxFailure
import casa.moller.tandem.core.data.inbox.RecipeInboxRepository
import casa.moller.tandem.core.data.inbox.inboxFailure
import casa.moller.tandem.core.database.DatabaseKind
import casa.moller.tandem.core.database.TandemDatabaseFactory
import casa.moller.tandem.core.database.dao.InboxDao
import casa.moller.tandem.core.database.entity.InboxEntryEntity
import casa.moller.tandem.core.datastore.RecipeInboxCategoryRecord
import casa.moller.tandem.core.datastore.RecipeInboxKeyStore
import casa.moller.tandem.core.datastore.RecipeInboxRecord
import casa.moller.tandem.core.datastore.RecipeInboxStore
import casa.moller.tandem.core.datastore.SecureStorageException
import casa.moller.tandem.core.domain.inbox.InboxText
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.error.isConnectivityProblem
import casa.moller.tandem.core.model.inbox.InboxAvailability
import casa.moller.tandem.core.model.inbox.InboxCategory
import casa.moller.tandem.core.model.inbox.InboxEntry
import casa.moller.tandem.core.model.inbox.InboxProblem
import casa.moller.tandem.core.model.inbox.InboxSettings
import casa.moller.tandem.core.model.inbox.InboxState
import casa.moller.tandem.core.network.miniflux.MinifluxClient
import casa.moller.tandem.core.network.miniflux.MinifluxEntryDto
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.account.ActiveSources
import casa.moller.tandem.core.sync.platform.SyncClock
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * [RecipeInboxRepository] (Fas 5, rss lane, plan §7 #35, docs/BYGGE.md "Inkorgen för recept"): the
 * settings in [RecipeInboxStore], the key in [RecipeInboxKeyStore] (used only inside `useKey`), the
 * posts in Room (`inbox_entry`), every Miniflux request through [MinifluxClient] and [MinifluxGuard]
 * (never `RemoteCaller`/`SyncGuard`: the inbox and Tandoor never pause each other). A "read" mark is
 * stored first and sent after (now when it can, else at the next refresh), like a tiny outbox.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
internal class DefaultRecipeInboxRepository @Inject constructor(
    private val sources: ActiveSources,
    private val databases: TandemDatabaseFactory,
    private val store: RecipeInboxStore,
    private val keys: RecipeInboxKeyStore,
    private val client: MinifluxClient,
    private val guard: MinifluxGuard,
    private val clock: SyncClock,
    private val demo: DemoInbox,
    @ApplicationScope private val scope: CoroutineScope,
) : RecipeInboxRepository {

    /** One refresh or send at a time (a pull to refresh and the background work never overlap). */
    private val mutex = Mutex()

    private val dao: InboxDao
        get() = databases.get(DatabaseKind.REAL).inboxDao()

    override val state: Flow<InboxState> = sources.source
        .flatMapLatest { source ->
            when (source) {
                ActiveSource.Loading -> flowOf(InboxState(InboxAvailability.LOADING))

                ActiveSource.None -> flowOf(InboxState(InboxAvailability.NO_ACCOUNT))

                ActiveSource.Demo -> flowOf(InboxState(InboxAvailability.DEMO))

                is ActiveSource.Remote -> combine(store.observe(source.accountId), keys.accountsWithKey) { record, withKey ->
                    InboxState(InboxAvailability.AVAILABLE, settingsOf(record, connected = source.accountId in withKey))
                }
            }
        }
        .catch { emit(InboxState(InboxAvailability.NO_ACCOUNT)) }
        .distinctUntilChanged()

    override val entries: Flow<List<InboxEntry>> = sources.source
        .flatMapLatest { source ->
            when (source) {
                ActiveSource.Demo -> demo.entries
                is ActiveSource.Remote -> dao.observeVisible(source.accountId).map { rows -> rows.map { it.toModel() } }
                else -> flowOf(emptyList())
            }
        }
        .catch { emit(emptyList()) }
        .distinctUntilChanged()

    override fun entry(id: Long): Flow<InboxEntry?> = sources.source
        .flatMapLatest { source ->
            when (source) {
                ActiveSource.Demo -> demo.entry(id)
                is ActiveSource.Remote -> dao.observe(source.accountId, id).map { row -> row?.takeIf { it.dismissedAt == null }?.toModel() }
                else -> flowOf(null)
            }
        }
        .catch { emit(null) }
        .distinctUntilChanged()

    override suspend fun connect(serverUrl: String, apiKey: String): Outcome<List<InboxCategory>> {
        val remote = remote() ?: return noAccount()
        val server = when (val normalized = MinifluxClient.normalize(serverUrl)) {
            is Outcome.Failure -> return inboxFailure(InboxProblem.INVALID_URL)
            is Outcome.Success -> normalized.value
        }
        val key = apiKey.trim()
        if (key.isEmpty()) return inboxFailure(InboxProblem.AUTH)
        val categories = mutex.withLock {
            // A key the user typed now: only what protects the server holds it back.
            guard.check(remote.accountId, key.hashCode(), background = false)?.let { return inboxFailure(it) }
            when (val me = call(remote.accountId, key) { client.me(server, key) }) {
                is Outcome.Failure -> return failureOf(me.error)
                is Outcome.Success -> Unit
            }
            when (val answer = call(remote.accountId, key) { client.categories(server, key) }) {
                is Outcome.Failure -> return failureOf(answer.error)
                is Outcome.Success -> answer.value.map { InboxCategory(it.id, it.title, it.totalUnread) }
            }
        }
        try {
            keys.store(remote.accountId, key)
        } catch (e: SecureStorageException) {
            return Outcome.Failure(DataProblem.SECURE_STORAGE.toError())
        }
        val serverText = server.toString()
        val sameServer = store.observe(remote.accountId).first().serverUrl == serverText
        store.update(remote.accountId) { old ->
            // Another server: its categories are other ones. The same server keeps the chosen that still exist.
            val kept = if (old.serverUrl == serverText) {
                old.categories.filter { chosen -> categories.any { it.id == chosen.id } }
            } else {
                emptyList()
            }
            old.copy(serverUrl = serverText, categories = kept, problem = null)
        }
        // Another server's posts are not this one's (the ids are per server).
        if (!sameServer) dao.deleteAccount(remote.accountId)
        guard.reset()
        return Outcome.Success(categories)
    }

    override suspend fun serverCategories(): Outcome<List<InboxCategory>> {
        val remote = remote() ?: return noAccount()
        val server = serverOf(store.observe(remote.accountId).first()) ?: return inboxFailure(InboxProblem.INVALID_URL)
        return keys.useKey(remote.accountId) { key ->
            mutex.withLock {
                guard.check(remote.accountId, key.hashCode(), background = false)?.let { return@withLock inboxFailure(it) }
                when (val answer = call(remote.accountId, key) { client.categories(server, key) }) {
                    is Outcome.Failure -> failureOf(answer.error)
                    is Outcome.Success -> Outcome.Success(answer.value.map { InboxCategory(it.id, it.title, it.totalUnread) })
                }
            }
        } ?: inboxFailure(InboxProblem.AUTH)
    }

    override suspend fun chooseCategories(categories: List<InboxCategory>): Outcome<Unit> {
        val remote = remote() ?: return noAccount()
        val chosen = categories.distinctBy { it.id }.map { RecipeInboxCategoryRecord(it.id, it.title) }
        store.update(remote.accountId) { it.copy(categories = chosen) }
        if (chosen.isEmpty()) {
            dao.deleteAccount(remote.accountId)
            return Outcome.Success(Unit)
        }
        // Read them now; a failure is kept as the inbox's problem and shown there.
        refresh(background = false)
        return Outcome.Success(Unit)
    }

    override suspend fun setMarkReadOnImport(enabled: Boolean) {
        val remote = remote() ?: return
        store.update(remote.accountId) { it.copy(markReadOnImport = enabled) }
    }

    override suspend fun setShowOnToday(enabled: Boolean) {
        val remote = remote() ?: return
        store.update(remote.accountId) { it.copy(showOnToday = enabled) }
    }

    override suspend fun disconnect() {
        val remote = remote() ?: return
        forget(remote.accountId)
        guard.reset()
    }

    /** Everything of [accountId]'s inbox on this phone (Koppla från, and when the account leaves). */
    suspend fun forget(accountId: String) {
        mutex.withLock {
            try {
                keys.remove(accountId)
            } catch (e: SecureStorageException) {
                // The key document cannot be written now; the account's record goes anyway, and
                // retainOnly removes the key when the account leaves the phone.
            }
            store.update(accountId) { RecipeInboxRecord() }
            dao.deleteAccount(accountId)
        }
    }

    override suspend fun refresh(background: Boolean): Outcome<Unit> {
        val remote = when (val source = sources.current()) {
            ActiveSource.Demo -> return Outcome.Success(Unit)
            is ActiveSource.Remote -> source
            else -> return noAccount()
        }
        val record = store.observe(remote.accountId).first()
        val server = serverOf(record)
        if (server == null || record.categories.isEmpty()) return Outcome.Success(Unit)
        val result = keys.useKey(remote.accountId) { key ->
            mutex.withLock { refreshLocked(remote.accountId, server, key, record.categories, background) }
        } ?: inboxFailure(InboxProblem.AUTH)
        val problem = (result as? Outcome.Failure)?.error?.let(InboxFailure::problemOf)
        store.update(remote.accountId) { old ->
            if (problem == null) old.copy(lastRefreshMillis = clock.nowMillis(), problem = null) else old.copy(problem = problem.name)
        }
        return result
    }

    private suspend fun refreshLocked(
        accountId: String,
        server: HttpUrl,
        key: String,
        categories: List<RecipeInboxCategoryRecord>,
        background: Boolean,
    ): Outcome<Unit> {
        guard.check(accountId, key.hashCode(), background)?.let { return inboxFailure(it) }
        sendReadsLocked(accountId, server, key)?.let { return failureOf(it) }
        val now = clock.nowMillis()
        val rows = mutableListOf<InboxEntryEntity>()
        for (category in categories) {
            when (val answer = call(accountId, key) { client.unreadEntries(server, key, category.id) }) {
                is Outcome.Success -> answer.value.entries.mapNotNullTo(rows) { it.toRow(accountId, category.id, now) }

                // A category deleted in Miniflux (FACTS: 400 "invalid category ID"): skipped, the others are read.
                is Outcome.Failure -> if (answer.error !is TandemError.BadRequest && answer.error != TandemError.NotFound) {
                    return failureOf(answer.error)
                }
            }
        }
        val fresh = rows.distinctBy { it.entryId }.sortedByDescending { it.publishedAt ?: 0 }.take(MAX_POSTS)
        dao.applyRefresh(accountId, fresh, keepImportedSinceMillis = now - KEEP_IMPORTED_MILLIS)
        return Outcome.Success(Unit)
    }

    /** Sends the stored "read" marks; the error that stopped it, null when all went (or none waited). */
    private suspend fun sendReadsLocked(accountId: String, server: HttpUrl, key: String): TandemError? {
        val pending = dao.getReadPending(accountId)
        if (pending.isEmpty()) return null
        return when (val sent = call(accountId, key) { client.markRead(server, key, pending) }) {
            is Outcome.Success -> {
                dao.readSent(accountId, pending)
                null
            }

            is Outcome.Failure -> sent.error
        }
    }

    override suspend fun dismiss(id: Long) {
        when (val source = sources.current()) {
            ActiveSource.Demo -> demo.dismiss(id)

            is ActiveSource.Remote -> {
                dao.dismiss(source.accountId, id, clock.nowMillis())
                sendReadsSoon(source.accountId)
            }

            else -> Unit
        }
    }

    override suspend fun markImported(id: Long) {
        when (val source = sources.current()) {
            ActiveSource.Demo -> demo.markImported(id, Instant.fromEpochMilliseconds(clock.nowMillis()))

            is ActiveSource.Remote -> {
                val markRead = store.observe(source.accountId).first().markReadOnImport
                dao.markImported(source.accountId, id, clock.nowMillis(), markRead)
                if (markRead) sendReadsSoon(source.accountId)
            }

            else -> Unit
        }
    }

    /**
     * Sends the waiting "read" marks in the app's scope (the screen that asked may be gone already);
     * offline, held back or failed, they wait for the next refresh.
     */
    private fun sendReadsSoon(accountId: String) {
        scope.launch {
            val server = serverOf(store.observe(accountId).first()) ?: return@launch
            keys.useKey(accountId) { key ->
                mutex.withLock {
                    if (guard.check(accountId, key.hashCode(), background = false) == null) sendReadsLocked(accountId, server, key)
                }
            }
        }
    }

    /** One Miniflux request, recorded by the guard. */
    private suspend fun <T> call(accountId: String, key: String, block: suspend () -> Outcome<T>): Outcome<T> {
        val result = block()
        guard.record(accountId, key.hashCode(), (result as? Outcome.Failure)?.error)
        return result
    }

    private suspend fun remote(): ActiveSource.Remote? = sources.current() as? ActiveSource.Remote

    private fun noAccount(): Outcome.Failure = Outcome.Failure(DataProblem.NO_ACCOUNT.toError())

    private fun failureOf(error: TandemError): Outcome.Failure = inboxFailure(problemOf(error))

    companion object {
        /** The most posts kept from one refresh (newest first). */
        const val MAX_POSTS: Int = 200

        /** Imported posts stay as "Importerad" this long. */
        const val KEEP_IMPORTED_MILLIS: Long = 30L * 24 * 60 * 60 * 1000

        /** What the user is told for a Miniflux failure. */
        fun problemOf(error: TandemError): InboxProblem = when {
            error.isConnectivityProblem -> InboxProblem.UNREACHABLE
            error is TandemError.Unauthorized -> InboxProblem.AUTH
            error is TandemError.TlsError -> InboxProblem.TLS
            error is TandemError.Throttled -> InboxProblem.THROTTLED
            error is TandemError.ServerError -> InboxProblem.SERVER
            error is TandemError.InvalidUrl -> InboxProblem.INVALID_URL
            error is TandemError.NotTandoor || error is TandemError.Decode || error == TandemError.NotFound -> InboxProblem.NOT_MINIFLUX
            error == TandemError.Blocked -> InboxProblem.PAUSED
            else -> InboxFailure.problemOf(error)
        }

        private fun serverOf(record: RecipeInboxRecord): HttpUrl? = record.serverUrl?.toHttpUrlOrNull()?.takeIf { it.isHttps }

        private fun settingsOf(record: RecipeInboxRecord, connected: Boolean) = InboxSettings(
            connected = connected && record.serverUrl != null,
            serverUrl = record.serverUrl,
            categories = record.categories.map { InboxCategory(it.id, it.title) },
            markReadOnImport = record.markReadOnImport,
            showOnToday = record.showOnToday,
            lastRefresh = record.lastRefreshMillis?.let(Instant::fromEpochMilliseconds),
            problem = InboxProblem.entries.firstOrNull { it.name == record.problem },
        )

        private fun InboxEntryEntity.toModel() = InboxEntry(
            id = entryId,
            title = title,
            url = url,
            feedTitle = feedTitle,
            categoryId = categoryId,
            publishedAt = publishedAt?.let(Instant::fromEpochMilliseconds),
            imageUrl = imageUrl,
            excerpt = excerpt,
            importedAt = importedAt?.let(Instant::fromEpochMilliseconds),
        )

        /** A post as a row; null for one without a web address (nothing to import). */
        internal fun MinifluxEntryDto.toRow(accountId: String, categoryId: Long, now: Long): InboxEntryEntity? {
            val link = url.trim().toHttpUrlOrNull() ?: return null
            val enclosures = this.enclosures.orEmpty().map { it.url to it.mimeType }
            return InboxEntryEntity(
                accountId = accountId,
                entryId = id,
                categoryId = feed?.category?.id ?: categoryId,
                title = InboxText.decode(title).trim().ifBlank { link.host },
                url = link.toString(),
                feedTitle = feed?.title?.trim()?.takeIf { it.isNotEmpty() } ?: link.host,
                publishedAt = publishedAt?.let(::epochMillisOf),
                imageUrl = InboxText.firstImage(content, enclosures),
                excerpt = InboxText.excerpt(content, InboxEntry.EXCERPT_LENGTH),
                fetchedAt = now,
            )
        }

        private fun epochMillisOf(text: String): Long? = try {
            OffsetDateTime.parse(text).toInstant().toEpochMilli()
        } catch (e: DateTimeParseException) {
            null
        }
    }
}
