package casa.moller.tandem.core.sync.pantry

import casa.moller.tandem.core.data.DataProblem
import casa.moller.tandem.core.data.pantry.StaplesRepository
import casa.moller.tandem.core.data.permissions.SpacePermissionsRepository
import casa.moller.tandem.core.database.TandemDatabase
import casa.moller.tandem.core.database.TandemDatabaseFactory
import casa.moller.tandem.core.database.entity.OutboxEntities
import casa.moller.tandem.core.database.entity.OutboxEntity
import casa.moller.tandem.core.database.entity.OutboxOps
import casa.moller.tandem.core.database.entity.StapleEntity
import casa.moller.tandem.core.database.entity.SyncStateEntity
import casa.moller.tandem.core.domain.pantry.StapleCatalog
import casa.moller.tandem.core.domain.pantry.StapleFood
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.catchingUnexpected
import casa.moller.tandem.core.model.pantry.Staple
import casa.moller.tandem.core.model.sync.SyncStop
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.account.ActiveSources
import casa.moller.tandem.core.sync.account.identity
import casa.moller.tandem.core.sync.permissions.isGuest
import casa.moller.tandem.core.sync.platform.NetworkStatus
import casa.moller.tandem.core.sync.platform.SyncClock
import casa.moller.tandem.core.sync.platform.SyncScheduler
import casa.moller.tandem.core.sync.remote.RemoteCaller
import casa.moller.tandem.core.sync.remote.toEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * [StaplesRepository] (plan §8.8 "Basvaror"): the chosen staples live in `staple` on this device;
 * whether they are "har hemma" is Tandoor's `food_onhand` of their food (per household), set for
 * many at once with `BATCH_ON_HAND` rows (`PUT food/batch_update/`, FACTS "Fas S e").
 *
 * A staple's name finds the server's food with core:domain [StapleCatalog] (the same name, else
 * another name of the same staple: "Pepper" finds "svartpeppar"). A staple the space has no food
 * for is created by name (`POST /api/food/`) when online and then marked; offline it waits without
 * a food until the next sync ([resolvePending], the pantry's sync step) does both. Guests get
 * `DataProblem.READ_ONLY`; demo mode marks the demo database only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
internal class DefaultStaplesRepository @Inject constructor(
    private val sources: ActiveSources,
    private val databases: TandemDatabaseFactory,
    private val permissions: SpacePermissionsRepository,
    private val caller: RemoteCaller,
    private val scheduler: SyncScheduler,
    private val network: NetworkStatus,
    private val clock: SyncClock,
    private val changes: PantryChanges,
) : StaplesRepository {
    private val writes = Mutex()

    override fun observeStaples(): Flow<List<Staple>> = sources.source
        .flatMapLatest { source ->
            if (source !is ActiveSource.Stored) return@flatMapLatest flowOf(emptyList<Staple>())
            val database = databases.get(source.kind)
            val staples = database.stapleDao().observeStaples(source.accountId)
            combine(staples, database.supermarketDao().observeFoods(source.accountId)) { chosen, foods ->
                val byId = foods.associateBy { it.id }
                chosen.map { staple ->
                    val food = staple.foodId?.let { byId[it] }
                    Staple(name = staple.name, foodId = staple.foodId, onHand = food?.onHand == true)
                }
            }
        }
        .catch { emit(emptyList()) }
        .distinctUntilChanged()

    override fun observeSuggestionsShown(): Flow<Boolean> = sources.source
        .flatMapLatest { source ->
            if (source !is ActiveSource.Stored) return@flatMapLatest flowOf(false)
            databases.get(source.kind).syncStateDao().observeOne(source.accountId, SUGGESTIONS_KEY).map { it != null }
        }
        .catch { emit(false) }
        .distinctUntilChanged()

    override suspend fun markSuggestionsShown(): Outcome<Unit> = catchingUnexpected {
        val source = sources.current() as? ActiveSource.Stored
            ?: return@catchingUnexpected Outcome.Failure(DataProblem.NO_ACCOUNT.toError())
        // A device setting (no server write), so a guest may dismiss the list too.
        databases.get(source.kind).syncStateDao().upsert(
            SyncStateEntity(accountId = source.accountId, entity = SUGGESTIONS_KEY, lastFullRelistEpochMillis = clock.nowMillis()),
        )
        Outcome.Success(Unit)
    }

    override suspend fun addStaples(names: List<String>): Outcome<Unit> {
        val wanted = names.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { StapleCatalog.normalize(it) }
        if (wanted.isEmpty()) return Outcome.Success(Unit)
        val stored = write { source, database ->
            val foods = stapleFoods(database, source.accountId)
            val now = clock.nowMillis()
            val rows = wanted.map { name ->
                StapleEntity(source.accountId, StapleCatalog.normalize(name), name, StapleCatalog.matchFood(name, foods), now)
            }
            database.stapleDao().upsertStaples(rows)
            setOnHandLocked(source, database, rows.mapNotNull { it.foodId }, onHand = true)
        }
        if (stored is Outcome.Failure) return stored
        val source = sources.current()
        if (source is ActiveSource.Remote && network.online.value) resolvePending(source, background = false)
        return Outcome.Success(Unit)
    }

    override suspend fun removeStaple(name: String): Outcome<Unit> = write { source, database ->
        // Only the device's list: the food stays "har hemma" until it is unmarked.
        database.stapleDao().deleteStaple(source.accountId, StapleCatalog.normalize(name))
    }

    override suspend fun setOnHand(names: List<String>, onHand: Boolean): Outcome<Unit> = write { source, database ->
        val keys = names.mapTo(HashSet()) { StapleCatalog.normalize(it) }
        val foodIds = database.stapleDao().getStaples(source.accountId).filter { it.nameKey in keys }.mapNotNull { it.foodId }
        setOnHandLocked(source, database, foodIds, onHand)
    }

    /**
     * Staples without a food (added offline, or a name the space did not know): matched again
     * against the stored foods, else created by name on the server (`POST /api/food/`); then marked
     * "har hemma". Called after adding and by the pantry's sync step. Never for guests or demo mode.
     */
    suspend fun resolvePending(remote: ActiveSource.Remote, background: Boolean): Outcome<Unit> = catchingUnexpected {
        val database = databases.get(remote.kind)
        val accountId = remote.accountId
        if (isGuest(database, accountId) || permissions.permissions.first().readOnly) return@catchingUnexpected Outcome.Success(Unit)
        val pending = database.stapleDao().getStaples(accountId).filter { it.foodId == null }
        if (pending.isEmpty()) return@catchingUnexpected Outcome.Success(Unit)
        val resolved = mutableListOf<StapleEntity>()
        for (staple in pending) {
            val foodId = StapleCatalog.matchFood(staple.name, stapleFoods(database, accountId))
                ?: when (val created = createFood(remote, background, staple.name)) {
                    is Outcome.Failure -> return@catchingUnexpected created
                    is Outcome.Success -> created.value
                }
                ?: continue
            resolved += staple.copy(foodId = foodId)
        }
        // Fas S review (fix-data): stored for [remote] itself, never for whatever account is active
        // now: a switch while the food was being created must not put this account's staples and
        // food ids into another account's tables and outbox. Aborted on a switch; the food created
        // here is stored already, so the next run matches it instead of creating it again.
        if (sources.source.value.identity != remote.identity) return@catchingUnexpected Outcome.Failure(SyncStop.SourceChanged)
        writes.withLock {
            if (sources.source.value.identity != remote.identity) return@catchingUnexpected Outcome.Failure(SyncStop.SourceChanged)
            database.pantryDao().inTransaction {
                database.stapleDao().upsertStaples(resolved)
                setOnHandLocked(remote, database, resolved.mapNotNull { it.foodId }, onHand = true)
            }
        }
        if (sources.source.value.identity == remote.identity) scheduler.scheduleOutbox()
        changes.notify(accountId)
        Outcome.Success(Unit)
    }

    /** A food named [name] on the server, stored until the next full read of the foods; its id. */
    private suspend fun createFood(remote: ActiveSource.Remote, background: Boolean, name: String): Outcome<Long?> {
        val body = buildJsonObject { put("name", name) }
        val created = caller.call(remote, background, confirmForbidden = true) { pantryFoods.createFood(body) }
        if (created is Outcome.Failure) return created
        val row = (created as Outcome.Success).value.toEntity(remote.accountId) ?: return Outcome.Success(null)
        databases.get(remote.kind).referenceDao().upsertFoods(listOf(row))
        return Outcome.Success(row.id)
    }

    /** The foods staples are matched against (the account's food list). */
    private suspend fun stapleFoods(database: TandemDatabase, accountId: String): List<StapleFood> =
        database.referenceDao().getFoods(accountId).map { StapleFood(it.id, it.name, it.pluralName) }

    /**
     * "Har hemma" for [foodIds] here, and one `BATCH_ON_HAND` row per food that changes (merged per
     * food: back to where it started cancels, `OutboxMerge`). Inside the caller's transaction.
     */
    private suspend fun setOnHandLocked(source: ActiveSource.Stored, database: TandemDatabase, foodIds: List<Long>, onHand: Boolean) {
        val accountId = source.accountId
        val foods = database.referenceDao().getFoods(accountId).associateBy { it.id }
        val now = clock.nowMillis()
        for (foodId in foodIds.distinct()) {
            val before = foods[foodId]?.onHand ?: false
            database.supermarketDao().setFoodOnHand(accountId, foodId, onHand)
            if (source !is ActiveSource.Remote || before == onHand) continue
            database.outboxDao().enqueue(
                OutboxEntity(
                    accountId = accountId,
                    op = OutboxOps.BATCH_ON_HAND,
                    entity = OutboxEntities.FOOD,
                    serverId = foodId,
                    payload = PantryPayloads.onHand(onHand),
                    baseSnapshot = PantryPayloads.onHand(before),
                    batchKey = PantryPayloads.onHandBatchKey(onHand),
                    createdAtEpochMillis = now,
                ),
            )
        }
    }

    /** One local change in one transaction; guests are refused first. Schedules the outbox for a real account. */
    private suspend fun write(block: suspend (ActiveSource.Stored, TandemDatabase) -> Unit): Outcome<Unit> = catchingUnexpected {
        when (val source = sources.current()) {
            is ActiveSource.Stored -> {
                if (source is ActiveSource.Remote && permissions.permissions.first().readOnly) {
                    return@catchingUnexpected Outcome.Failure(DataProblem.READ_ONLY.toError())
                }
                val database = databases.get(source.kind)
                writes.withLock { database.pantryDao().inTransaction { block(source, database) } }
                if (source is ActiveSource.Remote) scheduler.scheduleOutbox()
                changes.notify(source.accountId)
                Outcome.Success(Unit)
            }

            ActiveSource.Loading, ActiveSource.None -> Outcome.Failure(DataProblem.NO_ACCOUNT.toError())
        }
    }

    companion object {
        /** `sync_state`: the first-time staple suggestions were shown or dismissed on this device. */
        const val SUGGESTIONS_KEY: String = "pantry_staples_suggested"
    }
}
