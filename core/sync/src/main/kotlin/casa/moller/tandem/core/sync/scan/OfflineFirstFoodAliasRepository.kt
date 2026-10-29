package casa.moller.tandem.core.sync.scan

import casa.moller.tandem.core.data.DataProblem
import casa.moller.tandem.core.data.permissions.SpacePermissionsRepository
import casa.moller.tandem.core.data.scan.FoodAliasRepository
import casa.moller.tandem.core.database.TandemDatabase
import casa.moller.tandem.core.database.TandemDatabaseFactory
import casa.moller.tandem.core.database.entity.FoodAliasEntity
import casa.moller.tandem.core.database.entity.OutboxEntities
import casa.moller.tandem.core.database.entity.OutboxEntity
import casa.moller.tandem.core.database.entity.OutboxOps
import casa.moller.tandem.core.database.entity.OutboxState
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.error.catchingUnexpected
import casa.moller.tandem.core.model.scan.FoodAlias
import casa.moller.tandem.core.model.scan.FoodAliasKind
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.account.ActiveSources
import casa.moller.tandem.core.sync.engine.DrainResult
import casa.moller.tandem.core.sync.engine.OutboxSender
import casa.moller.tandem.core.sync.platform.NetworkStatus
import casa.moller.tandem.core.sync.platform.SyncClock
import casa.moller.tandem.core.sync.platform.SyncScheduler
import casa.moller.tandem.core.sync.scan.FoodAliases.toModel
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [FoodAliasRepository] on Room (`food_alias`) and the outbox (plan §8.8, FACTS "Fas S d"): every
 * FOOD_ALIAS of the space is cached ([FoodAliasReader]); [find] matches without case on the device,
 * offline too. [learn] never makes a duplicate: the same text (without case) re-points the existing
 * alias (a PATCH of `param_2`) or does nothing when it already names that food, and the sender checks
 * the server once more before it creates one (another phone may have learned it meanwhile). A
 * FOOD_ALIAS made on the web is used for matching but not changed by the app: it wins and is
 * returned as it is. Guests cannot write automations (403): `DataProblem.READ_ONLY` before anything
 * is stored. Demo mode keeps its aliases in the demo database only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
internal class OfflineFirstFoodAliasRepository @Inject constructor(
    private val sources: ActiveSources,
    private val databases: TandemDatabaseFactory,
    private val permissions: SpacePermissionsRepository,
    private val reader: FoodAliasReader,
    private val outbox: OutboxSender,
    private val scheduler: SyncScheduler,
    private val network: NetworkStatus,
    private val clock: SyncClock,
) : FoodAliasRepository {
    private val writes = Mutex()

    override fun observeAliases(): Flow<List<FoodAlias>> = sources.source
        .flatMapLatest { source ->
            if (source !is ActiveSource.Stored) return@flatMapLatest flowOf(emptyList<FoodAlias>())
            val database = databases.get(source.kind)
            val aliases = database.foodAliasDao().observeAliases(source.accountId)
            combine(aliases, database.outboxDao().observeAll(source.accountId)) { rows, queue ->
                val queued = queue.filter { it.entity == OutboxEntities.FOOD_ALIAS }
                val local = queued.mapNotNullTo(HashSet()) { it.localId }
                val server = queued.mapNotNullTo(HashSet()) { it.serverId }
                rows.filterNot { it.disabled }.map { row ->
                    row.toModel(pending = row.localId in local || (row.serverId != null && row.serverId in server))
                }
            }
        }
        .catch { emit(emptyList()) }
        .distinctUntilChanged()

    override suspend fun find(text: String): FoodAlias? {
        val key = FoodAliases.key(text)
        if (key.isEmpty()) return null
        val source = sources.current() as? ActiveSource.Stored ?: return null
        return try {
            databases.get(source.kind).foodAliasDao().find(source.accountId, key).firstOrNull()?.toModel(pending = false)
        } catch (e: IllegalStateException) {
            // The database is not readable right now (recovery): no learned alias.
            null
        }
    }

    override suspend fun learn(text: String, foodName: String, kind: FoodAliasKind, foodId: Long?): Outcome<FoodAlias> = write { context ->
        val alias = FoodAliases.clip(text)
        val food = FoodAliases.clip(foodName)
        if (alias.isEmpty() || food.isEmpty()) return@write Outcome.Failure(TandemError.BadRequest())
        val dao = context.database.foodAliasDao()
        val existing = dao.find(context.accountId, FoodAliases.key(alias)).firstOrNull()
        when {
            existing == null -> Outcome.Success(context.create(alias, food, kind, foodId).toModel(pending = context.remote))

            // Already learned (by this or another phone), or a web alias that wins: nothing to send.
            existing.foodName.equals(food, ignoreCase = true) || FoodAliases.kindOf(existing) == FoodAliasKind.OTHER ->
                Outcome.Success(existing.toModel(pending = false))

            else -> {
                val changed = existing.copy(foodName = food, foodId = foodId ?: context.foodIdOf(food))
                dao.upsert(listOf(changed))
                context.queue(changed, OutboxOps.PATCH, FoodAliases.foodPayload(food))
                Outcome.Success(changed.toModel(pending = context.remote))
            }
        }
    }

    override suspend fun forget(id: Long): Outcome<Unit> = write { context ->
        val key = context.database.idRewriteDao().current(context.accountId, OutboxEntities.FOOD_ALIAS, id)
        val row = context.database.foodAliasDao().get(context.accountId, key) ?: return@write Outcome.Success(Unit)
        context.database.foodAliasDao().delete(context.accountId, row.localId)
        context.queue(row, OutboxOps.DELETE, "{}")
        Outcome.Success(Unit)
    }

    override suspend fun refresh(): Outcome<Unit> = catchingUnexpected {
        when (val source = sources.current()) {
            is ActiveSource.Remote -> {
                if (!network.online.value) return@catchingUnexpected Outcome.Failure(TandemError.NetworkUnavailable)
                if (outbox.drain(source) == DrainResult.RETRY) scheduler.scheduleOutbox()
                reader.sync(source, background = false)
            }

            ActiveSource.Demo -> Outcome.Success(Unit)

            ActiveSource.Loading, ActiveSource.None -> Outcome.Failure(DataProblem.NO_ACCOUNT.toError())
        }
    }

    private suspend fun <T> write(block: suspend (WriteContext) -> Outcome<T>): Outcome<T> = catchingUnexpected {
        when (val source = sources.current()) {
            is ActiveSource.Stored -> {
                if (source is ActiveSource.Remote && permissions.permissions.first().readOnly) {
                    return@catchingUnexpected Outcome.Failure(DataProblem.READ_ONLY.toError())
                }
                val database = databases.get(source.kind)
                var result: Outcome<T> = Outcome.Failure(TandemError.Unknown())
                writes.withLock {
                    database.pantryDao().inTransaction { result = block(WriteContext(source, database, clock.nowMillis())) }
                }
                if (result is Outcome.Success && source is ActiveSource.Remote) scheduler.scheduleOutbox()
                result
            }

            ActiveSource.Loading, ActiveSource.None -> Outcome.Failure(DataProblem.NO_ACCOUNT.toError())
        }
    }

    private inner class WriteContext(val source: ActiveSource.Stored, val database: TandemDatabase, val now: Long) {
        val accountId: String = source.accountId
        val remote: Boolean = source is ActiveSource.Remote

        suspend fun foodIdOf(name: String): Long? =
            database.referenceDao().getFoods(accountId).firstOrNull { it.name.lowercase(Locale.ROOT) == name.lowercase(Locale.ROOT) }?.id

        suspend fun create(alias: String, food: String, kind: FoodAliasKind, foodId: Long?): FoodAliasEntity {
            val row = FoodAliasEntity(
                accountId = accountId,
                localId = database.idRewriteDao().nextLocalId(accountId, OutboxEntities.FOOD_ALIAS),
                serverId = null,
                alias = alias,
                aliasKey = FoodAliases.key(alias),
                foodName = food,
                foodId = foodId ?: foodIdOf(food),
                kind = FoodAliases.storedKind(kind),
                automationName = FoodAliases.automationName(kind),
                fetchedAtEpochMillis = now,
            )
            database.foodAliasDao().upsert(listOf(row))
            if (remote) {
                database.outboxDao().enqueue(
                    OutboxEntity(
                        accountId = accountId,
                        op = OutboxOps.CREATE,
                        entity = OutboxEntities.FOOD_ALIAS,
                        localId = row.localId,
                        payload = FoodAliases.createPayload(alias, food, kind),
                        createdAtEpochMillis = now,
                    ),
                )
            }
            return row
        }

        /** Queues [op] for [row]: by server id, or by its temporary id (merged into an unsent CREATE). */
        suspend fun queue(row: FoodAliasEntity, op: String, payload: String) {
            if (!remote) return
            val serverId = row.serverId
            val create = database.outboxDao().getAllFor(accountId, OutboxEntities.FOOD_ALIAS)
                .firstOrNull { it.op == OutboxOps.CREATE && it.localId == row.localId }
            if (serverId == null && create == null) return
            database.outboxDao().enqueue(
                OutboxEntity(
                    accountId = accountId,
                    op = op,
                    entity = OutboxEntities.FOOD_ALIAS,
                    localId = if (serverId == null) row.localId else null,
                    serverId = serverId,
                    payload = payload,
                    dependsOn = if (serverId == null) create?.takeUnless { it.state in UNSENT && it.attempts == 0 }?.id else null,
                    createdAtEpochMillis = now,
                ),
            )
        }
    }

    private companion object {
        val UNSENT: Set<OutboxState> = setOf(OutboxState.PENDING, OutboxState.PAUSED)
    }
}
