package casa.moller.tandem.core.sync.shopping

import casa.moller.tandem.core.data.DataProblem
import casa.moller.tandem.core.data.permissions.SpacePermissionsRepository
import casa.moller.tandem.core.data.shopping.SupermarketRepository
import casa.moller.tandem.core.database.TandemDatabase
import casa.moller.tandem.core.database.TandemDatabaseFactory
import casa.moller.tandem.core.database.entity.OutboxEntities
import casa.moller.tandem.core.database.entity.OutboxEntity
import casa.moller.tandem.core.database.entity.OutboxOps
import casa.moller.tandem.core.database.entity.SyncStateEntity
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.catchingUnexpected
import casa.moller.tandem.core.model.shopping.Supermarket
import casa.moller.tandem.core.model.shopping.SupermarketCategory
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.account.ActiveSources
import casa.moller.tandem.core.sync.platform.SyncClock
import casa.moller.tandem.core.sync.platform.SyncScheduler
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

/**
 * [SupermarketRepository] on Room (feature #10): stores, aisles and the aisle order come from the
 * small-table read ([ShoppingTables]); a food's aisle, "har hemma" and a new aisle order are
 * written here at once and queued (`food`, `supermarket_category_relation` outbox rows, sent by
 * [FoodOutboxHandler] and [AisleOrderOutboxHandler]). The chosen store is a choice of this device
 * and account, kept in `sync_state` (key [SELECTED_SUPERMARKET], the id in `hash`), so it needs no
 * schema change and goes with the account's other rows at logout. Guests may choose a store but
 * not change aisles (`DataProblem.READ_ONLY`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
internal class DefaultSupermarketRepository @Inject constructor(
    private val sources: ActiveSources,
    private val databases: TandemDatabaseFactory,
    private val permissions: SpacePermissionsRepository,
    private val scheduler: SyncScheduler,
    private val clock: SyncClock,
) : SupermarketRepository {
    override fun observeSupermarkets(): Flow<List<Supermarket>> = stored { source ->
        val dao = databases.get(source.kind).supermarketDao()
        combine(
            dao.observeSupermarkets(source.accountId),
            dao.observeAllRelations(source.accountId),
            dao.observeCategories(source.accountId),
        ) { stores, relations, categories ->
            val byId = categories.associateBy { it.id }
            stores.map { it.toModel(relations, byId) }
        }
    }

    override fun observeCategories(): Flow<List<SupermarketCategory>> = stored { source ->
        databases.get(source.kind).supermarketDao().observeCategories(source.accountId).map { rows -> rows.map { it.toModel() } }
    }

    override fun observeSelectedSupermarket(): Flow<Long?> = sources.source
        .flatMapLatest { source ->
            if (source is ActiveSource.Stored) {
                databases.get(source.kind).syncStateDao().observe(source.accountId)
                    .map { states -> states.firstOrNull { it.entity == SELECTED_SUPERMARKET }?.hash?.toLongOrNull() }
            } else {
                flowOf(null)
            }
        }
        .catch { emit(null) }
        .distinctUntilChanged()

    override suspend fun selectSupermarket(id: Long?): Outcome<Unit> = catchingUnexpected {
        val source = sources.current() as? ActiveSource.Stored
            ?: return@catchingUnexpected Outcome.Failure(DataProblem.NO_ACCOUNT.toError())
        val states = databases.get(source.kind).syncStateDao()
        if (id == null) {
            states.delete(source.accountId, SELECTED_SUPERMARKET)
        } else {
            states.upsert(SyncStateEntity(accountId = source.accountId, entity = SELECTED_SUPERMARKET, hash = id.toString()))
        }
        Outcome.Success(Unit)
    }

    override suspend fun setFoodCategory(foodId: Long, categoryId: Long?): Outcome<Unit> = write { source, database ->
        val name = categoryId?.let { database.supermarketDao().getCategory(source.accountId, it)?.name }
        database.supermarketDao().setFoodCategory(source.accountId, foodId, categoryId)
        database.shoppingDao().setCategoryOfFood(source.accountId, foodId, categoryId)
        queue(source, database, OutboxEntities.FOOD, foodId, ShoppingPayloads.foodCategory(categoryId, name))
    }

    override suspend fun setFoodOnHand(foodId: Long, onHand: Boolean): Outcome<Unit> = write { source, database ->
        database.supermarketDao().setFoodOnHand(source.accountId, foodId, onHand)
        queue(source, database, OutboxEntities.FOOD, foodId, ShoppingPayloads.foodOnHand(onHand))
    }

    override suspend fun reorderAisles(supermarketId: Long, relationIdsInOrder: List<Long>): Outcome<Unit> = write { source, database ->
        val dao = database.supermarketDao()
        val relations = dao.getRelations(source.accountId, supermarketId).associateBy { it.id }
        relationIdsInOrder.distinct().filter { it in relations }.forEachIndexed { index, relationId ->
            if (relations[relationId]?.relationOrder == index) return@forEachIndexed
            dao.setRelationOrder(source.accountId, relationId, index)
            queue(source, database, OutboxEntities.SUPERMARKET_CATEGORY_RELATION, relationId, ShoppingPayloads.relationOrder(index))
        }
    }

    private suspend fun write(block: suspend (ActiveSource.Stored, TandemDatabase) -> Unit): Outcome<Unit> = catchingUnexpected {
        when (val source = sources.current()) {
            is ActiveSource.Stored -> {
                if (source is ActiveSource.Remote && permissions.permissions.first().readOnly) {
                    return@catchingUnexpected Outcome.Failure(DataProblem.READ_ONLY.toError())
                }
                val database = databases.get(source.kind)
                database.shoppingDao().inTransaction { block(source, database) }
                if (source is ActiveSource.Remote) scheduler.scheduleOutbox()
                Outcome.Success(Unit)
            }

            ActiveSource.Loading, ActiveSource.None -> Outcome.Failure(DataProblem.NO_ACCOUNT.toError())
        }
    }

    /** A PATCH of a server object (foods and relations only exist on the server); merged with an unsent one. */
    private suspend fun queue(source: ActiveSource.Stored, database: TandemDatabase, entity: String, serverId: Long, payload: String) {
        if (source !is ActiveSource.Remote) return
        database.outboxDao().enqueue(
            OutboxEntity(
                accountId = source.accountId,
                op = OutboxOps.PATCH,
                entity = entity,
                serverId = serverId,
                payload = payload,
                createdAtEpochMillis = clock.nowMillis(),
            ),
        )
    }

    private fun <T> stored(flow: (ActiveSource.Stored) -> Flow<List<T>>): Flow<List<T>> = sources.source
        .flatMapLatest { source -> if (source is ActiveSource.Stored) flow(source) else flowOf(emptyList()) }
        .catch { emit(emptyList()) }
        .distinctUntilChanged()

    companion object {
        /** `sync_state.entity` of the store chosen on this device (`hash` = its id). */
        const val SELECTED_SUPERMARKET: String = "shopping_selected_supermarket"
    }
}
