package casa.moller.tandem.core.sync.drink

import casa.moller.tandem.core.data.di.ApplicationScope
import casa.moller.tandem.core.database.DatabaseKind
import casa.moller.tandem.core.database.TandemDatabaseFactory
import casa.moller.tandem.core.database.entity.DrinkDraftEntity
import casa.moller.tandem.core.domain.drink.DrinkIndex
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet

/**
 * Fas S komplettering (fix-ui #24): ONE [DrinkIndex] pipeline per account and database, shared by
 * every reader of the drink repository. Every meal card in Idag and Veckan shows a drink
 * suggestion (`observeRecipePairings`), and each of them used to build its own index from Room.
 *
 * Shared, a reader must still see every write made before it started (the repository's tests read
 * right after a change; Room tells the shared pipeline about a commit only a moment later). So each
 * built index carries the count of [invalidate] calls it was started at, a NEW reader invalidates
 * once and skips every index started before that, and the repository invalidates after each local
 * write (outbox rows, `drink_draft`, the demo database), so long-lived readers see their own change
 * at once too. A reader therefore never gets the replayed, older index. A new reader costs one
 * rebuild for all readers (cancelled and merged when several arrive together), not a pipeline of
 * its own; changes the sync makes (keywords, sent outbox rows) reach running readers through Room.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
internal class SharedDrinkIndex @Inject constructor(
    private val databases: TandemDatabaseFactory,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private data class Key(val kind: DatabaseKind, val accountId: String, val withOutbox: Boolean)

    private class Built(val writes: Long, val index: DrinkIndex, val drafts: List<DrinkDraftEntity>)

    private val writes = MutableStateFlow(0L)
    private val shared = ConcurrentHashMap<Key, SharedFlow<Built>>()

    /** How often an index pipeline was started from Room (for the tests: once per account while observed). */
    internal val pipelinesStarted = AtomicInteger()

    /** The index and the drafts of [accountId], read after this collection started (see the class comment). */
    fun observe(kind: DatabaseKind, accountId: String, withOutbox: Boolean): Flow<Pair<DrinkIndex, List<DrinkDraftEntity>>> = flow {
        val wanted = writes.updateAndGet { it + 1 }
        emitAll(sharedFor(Key(kind, accountId, withOutbox)).filter { it.writes >= wanted }.map { it.index to it.drafts })
    }

    /** A local write happened: the next index is read anew (Room has committed it). */
    fun invalidate() {
        writes.update { it + 1 }
    }

    private fun sharedFor(key: Key): SharedFlow<Built> = shared.getOrPut(key) {
        writes
            .flatMapLatest { count ->
                observeDrinkIndexAndDrafts(databases.get(key.kind), key.accountId, key.withOutbox)
                    .map { (index, drafts) -> Built(count, index, drafts) }
            }
            .onStart { pipelinesStarted.incrementAndGet() }
            .flowOn(Dispatchers.Default)
            .shareIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), replay = 1)
    }

    private companion object {
        /** A screen that turns (or a list that recycles a card) keeps the pipeline. */
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
