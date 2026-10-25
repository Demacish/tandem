package casa.moller.tandem.core.sync.shopping

import casa.moller.tandem.core.data.DataProblem
import casa.moller.tandem.core.data.permissions.SpacePermissionsRepository
import casa.moller.tandem.core.data.shopping.ShoppingRepository
import casa.moller.tandem.core.database.TandemDatabase
import casa.moller.tandem.core.database.TandemDatabaseFactory
import casa.moller.tandem.core.database.entity.OutboxEntities
import casa.moller.tandem.core.database.entity.OutboxEntity
import casa.moller.tandem.core.database.entity.OutboxOps
import casa.moller.tandem.core.database.entity.OutboxState
import casa.moller.tandem.core.database.entity.ShoppingEntryEntity
import casa.moller.tandem.core.database.entity.ShoppingListRecipeEntity
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.error.catchingUnexpected
import casa.moller.tandem.core.model.shopping.RecipeShoppingRequest
import casa.moller.tandem.core.model.shopping.ShoppingChange
import casa.moller.tandem.core.model.shopping.ShoppingEntry
import casa.moller.tandem.core.model.shopping.ShoppingEntryDraft
import casa.moller.tandem.core.model.shopping.ShoppingFilter
import casa.moller.tandem.core.model.shopping.ShoppingHistoryItem
import casa.moller.tandem.core.model.shopping.ShoppingList
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.account.ActiveSources
import casa.moller.tandem.core.sync.engine.DetailFetcher
import casa.moller.tandem.core.sync.platform.NetworkStatus
import casa.moller.tandem.core.sync.platform.SyncClock
import casa.moller.tandem.core.sync.platform.SyncScheduler
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [ShoppingRepository] on Room and the outbox (plan 3.5, 5.3, 5.7; features #9, #11-#14). See the
 * interface for the contract; how it is met:
 *
 * - **Offline first:** every change is written to `shopping_entry` / `shopping_list_recipe` and
 *   queued with `OutboxDao.enqueue` in ONE transaction (`ShoppingDao.inTransaction`), then the
 *   outbox worker is scheduled. The merge rules (`OutboxMerge`) keep the user's last intent: a
 *   change of an entry that never reached the server becomes part of its CREATE, check + uncheck
 *   cancel, a delete of such an entry removes everything queued for it.
 * - **Temporary ids:** an entry created here gets a negative id (`IdRewriteDao.nextLocalId`, never
 *   reused); every function also accepts that id after the entry reached the server.
 * - **Rows refer to rows:** a recipe put on the list is one list recipe (CREATE, `mealplan` in
 *   `refs` when the meal is new too) and one CREATE per ingredient that `depends_on` it with
 *   `list_recipe` in `refs` (FACTS "Fas 2 c": `recipe/{id}/shopping/` returns no ids).
 * - **Guests** get `DataProblem.READ_ONLY` before anything is stored (#42); **demo mode** writes
 *   the demo database and queues nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
internal class OfflineFirstShoppingRepository @Inject constructor(
    private val sources: ActiveSources,
    private val databases: TandemDatabaseFactory,
    private val permissions: SpacePermissionsRepository,
    private val scheduler: SyncScheduler,
    private val clock: SyncClock,
    private val sync: ShoppingSync,
    private val changes: ShoppingChangeBus,
    private val details: DetailFetcher,
    private val network: NetworkStatus,
) : ShoppingRepository {
    /** Serializes local writes, so two taps never get the same temporary id. */
    private val writes = Mutex()

    // ------------------------------------------------------------------------------------ reads

    override fun observeEntries(filter: ShoppingFilter): Flow<List<ShoppingEntry>> = stored { source ->
        val database = databases.get(source.kind)
        val accountId = source.accountId
        val shopping = database.shoppingDao()
        val stores = database.supermarketDao()
        val ordering = combine(stores.observeCategories(accountId), stores.observeAllRelations(accountId)) { categories, relations ->
            val positions = filter.supermarketId?.let { store ->
                relations.filter { it.supermarketId == store }
                    .sortedWith(compareBy({ it.relationOrder }, { it.id }))
                    .mapIndexed { index, relation -> relation.categoryId to index }
                    .toMap()
            }.orEmpty()
            ShoppingOrdering(positions, categories.associate { it.id to it.name })
        }
        combine(
            shopping.observeEntries(accountId),
            shopping.observeListRecipes(accountId),
            stores.observeFoods(accountId),
            shopping.observeOutbox(accountId),
            ordering,
        ) { entries, listRecipes, foods, outbox, order ->
            val onHand = foods.filter { it.onHand }.mapTo(HashSet()) { it.id }
            val recipes = listRecipes.associateBy { it.localId }
            val queued = outbox.filter { it.entity == OutboxEntities.SHOPPING_ENTRY }
            val pendingServer = queued.mapNotNullTo(HashSet()) { it.serverId }
            val pendingLocal = queued.mapNotNullTo(HashSet()) { it.localId }
            val models = entries.map { entry ->
                val pending = entry.localId in pendingLocal || (entry.serverId != null && entry.serverId in pendingServer)
                val foodOnHand = entry.foodId?.let { it in onHand } == true
                entry.toModel(onHand = foodOnHand, pending = pending, listRecipe = recipes[entry.listRecipeId])
            }
            order.sort(ShoppingOrdering.filter(models, filter))
        }
    }

    override fun observeLists(): Flow<List<ShoppingList>> = stored { source ->
        databases.get(source.kind).shoppingDao().observeShoppingLists(source.accountId).map { lists -> lists.map { it.toModel() } }
    }

    override fun observePendingCount(): Flow<Int> = sources.source
        .flatMapLatest { source ->
            if (source is ActiveSource.Remote) {
                databases.get(source.kind).shoppingDao().observePendingCount(source.accountId)
            } else {
                flowOf(0)
            }
        }
        .catch { emit(0) }
        .distinctUntilChanged()

    override fun observeChanges(): Flow<ShoppingChange> = sources.source.flatMapLatest { source ->
        if (source is ActiveSource.Remote) {
            changes.changes.filter { it.accountId == source.accountId }.map { it.change }
        } else {
            emptyFlow()
        }
    }

    override fun observeSuggestions(query: String, limit: Int): Flow<List<String>> = stored { source ->
        val database = databases.get(source.kind)
        val accountId = source.accountId
        val text = query.trim().lowercase()
        combine(
            database.supermarketDao().observeFoods(accountId),
            database.shoppingDao().observeEntries(accountId),
            database.shoppingDao().observeHistoryFoodNames(accountId),
        ) { foods, entries, history ->
            if (text.isEmpty()) return@combine emptyList()
            val names = (history + entries.map { it.foodName } + foods.flatMap { it.suggestionNames() })
                .map { it.trim() }
                .filter { it.isNotEmpty() && text in it.lowercase() }
                .distinctBy { it.lowercase() }
            // Names that start with the text first, then the rest; each group keeps its order (recent first).
            (names.filter { it.lowercase().startsWith(text) } + names.filterNot { it.lowercase().startsWith(text) }).take(limit)
        }
    }

    override fun observeHistory(): Flow<List<ShoppingHistoryItem>> = stored { source ->
        databases.get(source.kind).shoppingDao().observeHistory(source.accountId).map { rows -> rows.map { it.toModel() } }
    }

    override suspend fun shareText(filter: ShoppingFilter): Outcome<String> = catchingUnexpected {
        val source = sources.current() as? ActiveSource.Stored
            ?: return@catchingUnexpected Outcome.Failure(DataProblem.NO_ACCOUNT.toError())
        val entries = observeEntries(filter).first()
        val categories = databases.get(source.kind).supermarketDao().observeCategories(source.accountId).first()
        val ordering = ShoppingOrdering(aislePositions(source, filter.supermarketId), categories.associate { it.id to it.name })
        Outcome.Success(ordering.shareText(entries, Instant.fromEpochMilliseconds(clock.nowMillis())))
    }

    override suspend fun refresh(full: Boolean): Outcome<Unit> = catchingUnexpected {
        when (val source = sources.current()) {
            is ActiveSource.Remote -> sync.refresh(source, background = false, full = full)
            ActiveSource.Demo -> Outcome.Success(Unit)
            ActiveSource.Loading, ActiveSource.None -> Outcome.Failure(DataProblem.NO_ACCOUNT.toError())
        }
    }

    // ------------------------------------------------------------------------------------ writes

    override suspend fun add(draft: ShoppingEntryDraft): Outcome<Long> = when (val added = addAll(listOf(draft))) {
        is Outcome.Failure -> added
        is Outcome.Success -> added.value.firstOrNull()?.let { Outcome.Success(it) } ?: Outcome.Failure(TandemError.BadRequest())
    }

    override suspend fun addAll(drafts: List<ShoppingEntryDraft>): Outcome<List<Long>> = write { context ->
        val foods = context.database.referenceDao().getFoods(context.accountId).associateBy { it.name.lowercase() }
        drafts.filter { it.foodName.isNotBlank() }.map { draft ->
            val name = draft.foodName.trim()
            val food = draft.foodId?.let { id -> foods.values.firstOrNull { it.id == id } } ?: foods[name.lowercase()]
            context.createEntry(
                foodName = name,
                foodId = draft.foodId ?: food?.id,
                categoryId = food?.supermarketCategoryId,
                unitName = draft.unitName?.trim()?.takeIf { it.isNotEmpty() },
                unitId = draft.unitId,
                amount = draft.amount,
                listIds = draft.listIds,
            )
        }
    }

    override suspend fun addRecipe(request: RecipeShoppingRequest): Outcome<Unit> {
        val source = sources.current()
        if (source is ActiveSource.Remote && readOnly()) return Outcome.Failure(DataProblem.READ_ONLY.toError())
        val stored = source as? ActiveSource.Stored ?: return Outcome.Failure(DataProblem.NO_ACCOUNT.toError())
        val dao = databases.get(stored.kind).shoppingDao()
        if (dao.getRecipeServings(stored.accountId, request.recipeId) == null && source is ActiveSource.Remote && network.online.value) {
            // Not downloaded yet: read the recipe now (its ingredients are needed to scale them).
            details.fetch(source, request.recipeId, isBackground = false)
        }
        val servings = dao.getRecipeServings(stored.accountId, request.recipeId) ?: return Outcome.Failure(TandemError.NotFound)
        return write { context -> context.addRecipe(request, servings) }
    }

    override suspend fun setChecked(ids: Collection<Long>, checked: Boolean): Outcome<Unit> = write { context ->
        for (entry in context.entries(ids)) {
            if (entry.checked == checked) continue
            context.save(entry.copy(checked = checked, completedAt = if (checked) context.now else null, updatedAt = context.now))
            if (context.hasUnsentCreate(entry)) {
                // Not on the server yet: the check becomes part of its CREATE (sent with a bulk call
                // right after it, see ShoppingEntryOutboxHandler); no temporary id in a payload.
                context.queue(entry, OutboxOps.PATCH, ShoppingPayloads.checkSnapshot(checked))
            } else {
                context.queue(
                    entry,
                    OutboxOps.BULK_CHECK,
                    ShoppingPayloads.check(entry.serverId ?: entry.localId, checked),
                    baseSnapshot = ShoppingPayloads.checkSnapshot(!checked),
                    refs = ShoppingPayloads.checkRefs(entry.serverId ?: entry.localId),
                    batchKey = ShoppingPayloads.checkBatchKey(checked),
                )
            }
        }
    }

    override suspend fun setDelayed(ids: Collection<Long>, until: Instant?): Outcome<Unit> = write { context ->
        val untilMillis = until?.toEpochMilliseconds()
        for (entry in context.entries(ids)) {
            context.save(entry.copy(delayUntil = untilMillis, updatedAt = context.now))
            context.queue(entry, OutboxOps.PATCH, ShoppingPayloads.delay(untilMillis))
        }
    }

    override suspend fun update(id: Long, amount: Double?, unitName: String?, foodName: String?): Outcome<Unit> = write { context ->
        val entry = context.entries(listOf(id)).firstOrNull() ?: return@write
        val food = foodName?.trim()?.takeIf { it.isNotEmpty() }
        val known = food?.let { name ->
            context.database.referenceDao().getFoods(context.accountId).firstOrNull { it.name.equals(name, ignoreCase = true) }
        }
        val changed = entry.copy(
            amount = amount ?: entry.amount,
            unitName = if (unitName == null) entry.unitName else unitName.trim().takeIf { it.isNotEmpty() },
            unitId = if (unitName == null) entry.unitId else null,
            foodName = food ?: entry.foodName,
            foodId = if (food == null) entry.foodId else known?.id,
            categoryId = if (food == null) entry.categoryId else known?.supermarketCategoryId,
            updatedAt = context.now,
        )
        context.save(changed)
        context.queue(entry, OutboxOps.PATCH, ShoppingPayloads.edit(amount, unitName, food))
    }

    override suspend fun setLists(ids: Collection<Long>, listIds: Set<Long>): Outcome<Unit> = write { context ->
        for (entry in context.entries(ids)) {
            context.save(entry.copy(shoppingListIds = ShoppingIds.encode(listIds), updatedAt = context.now))
            context.queue(entry, OutboxOps.PATCH, ShoppingPayloads.lists(listIds))
        }
    }

    override suspend fun remove(ids: Collection<Long>): Outcome<Unit> = write { context ->
        context.entries(ids).forEach { context.removeEntry(it) }
    }

    override suspend fun removeListRecipe(listRecipeId: Long): Outcome<Unit> = write { context ->
        val key = context.current(OutboxEntities.SHOPPING_LIST_RECIPE, listRecipeId)
        val listRecipe = context.database.shoppingDao().getListRecipe(context.accountId, key)
        // The server deletes the recipe's entries with it: their own queued changes go now
        // ("rensning av outbox före tömning"), so nothing is sent for an entry that is gone.
        for (entry in context.database.shoppingDao().getEntriesOfListRecipe(context.accountId, key)) {
            val serverId = entry.serverId
            if (serverId != null) {
                context.database.outboxDao().deleteUnsentFor(context.accountId, OutboxEntities.SHOPPING_ENTRY, serverId)
                context.database.shoppingDao().deleteEntries(context.accountId, listOf(entry.localId))
            } else {
                context.removeEntry(entry)
            }
        }
        if (listRecipe != null) {
            context.database.shoppingDao().deleteListRecipes(context.accountId, listOf(listRecipe.localId))
            context.queueListRecipe(listRecipe, OutboxOps.DELETE, "{}")
        }
    }

    override suspend fun clearChecked(): Outcome<Unit> = write { context ->
        // Each DELETE replaces the entry's queued check (OutboxMerge): the outbox is clean before the list is.
        context.database.shoppingDao().getCheckedEntries(context.accountId).forEach { context.removeEntry(it) }
    }

    // ------------------------------------------------------------------------------------ helpers

    private suspend fun readOnly(): Boolean = permissions.permissions.first().readOnly

    /**
     * Runs a local change for the active source in one transaction ([WriteContext]); guests are
     * refused first. Schedules the outbox for a real account.
     */
    private suspend fun <T> write(block: suspend (WriteContext) -> T): Outcome<T> = catchingUnexpected {
        when (val source = sources.current()) {
            is ActiveSource.Stored -> {
                if (source is ActiveSource.Remote && readOnly()) return@catchingUnexpected Outcome.Failure(DataProblem.READ_ONLY.toError())
                val database = databases.get(source.kind)
                val result = writes.withLock {
                    var value: T? = null
                    database.shoppingDao().inTransaction { value = block(WriteContext(source, database, clock.nowMillis())) }
                    @Suppress("UNCHECKED_CAST") // Set by the block before the transaction returns.
                    value as T
                }
                if (source is ActiveSource.Remote) scheduler.scheduleOutbox()
                Outcome.Success(result)
            }

            ActiveSource.Loading, ActiveSource.None -> Outcome.Failure(DataProblem.NO_ACCOUNT.toError())
        }
    }

    private fun <T> stored(flow: (ActiveSource.Stored) -> Flow<List<T>>): Flow<List<T>> = sources.source
        .flatMapLatest { source -> if (source is ActiveSource.Stored) flow(source) else flowOf(emptyList()) }
        .catch { emit(emptyList()) }
        .distinctUntilChanged()

    private suspend fun aislePositions(source: ActiveSource.Stored, supermarketId: Long?): Map<Long, Int> {
        val store = supermarketId ?: return emptyMap()
        return databases.get(source.kind).supermarketDao().getRelations(source.accountId, store)
            .mapIndexed { index, relation -> relation.categoryId to index }
            .toMap()
    }

    /** One local change: the source, its database and "now"; queues only for a real account. */
    private inner class WriteContext(val source: ActiveSource.Stored, val database: TandemDatabase, val now: Long) {
        val accountId: String = source.accountId
        private val remote: Boolean = source is ActiveSource.Remote

        /** [id] as it is stored now (a temporary id resolved through `id_mapping`). */
        suspend fun current(entity: String, id: Long): Long = database.idRewriteDao().current(accountId, entity, id)

        suspend fun entries(ids: Collection<Long>): List<ShoppingEntryEntity> {
            val keys = ids.map { current(OutboxEntities.SHOPPING_ENTRY, it) }.distinct()
            return if (keys.isEmpty()) emptyList() else database.shoppingDao().getEntriesByLocalIds(accountId, keys)
        }

        suspend fun save(entry: ShoppingEntryEntity) = database.shoppingDao().upsertEntries(listOf(entry))

        @Suppress("LongParameterList") // The entry's fields.
        suspend fun createEntry(
            foodName: String,
            foodId: Long?,
            categoryId: Long?,
            unitName: String?,
            unitId: Long?,
            amount: Double,
            listIds: Set<Long>,
            listRecipe: ShoppingListRecipeEntity? = null,
            ingredientId: Long? = null,
            dependsOn: Long? = null,
        ): Long {
            val localId = database.idRewriteDao().nextLocalId(accountId, OutboxEntities.SHOPPING_ENTRY)
            val account = (source as? ActiveSource.Remote)?.account
            save(
                ShoppingEntryEntity(
                    accountId = accountId,
                    localId = localId,
                    serverId = null,
                    foodId = foodId,
                    foodName = foodName,
                    categoryId = categoryId,
                    unitId = unitId,
                    unitName = unitName,
                    amount = amount,
                    createdAt = now,
                    updatedAt = now,
                    createdBy = account?.userId,
                    listRecipeId = listRecipe?.localId,
                    ingredientId = ingredientId,
                    mealPlanId = listRecipe?.mealPlanId,
                    recipeId = listRecipe?.recipeId,
                    recipeName = listRecipe?.recipeName,
                    shoppingListIds = ShoppingIds.encode(listIds),
                ),
            )
            if (remote) {
                val listRecipeId = listRecipe?.let { it.serverId ?: it.localId }
                database.outboxDao().enqueue(
                    OutboxEntity(
                        accountId = accountId,
                        op = OutboxOps.CREATE,
                        entity = OutboxEntities.SHOPPING_ENTRY,
                        localId = localId,
                        payload = ShoppingPayloads.createEntry(
                            foodName = foodName,
                            foodId = foodId,
                            unitName = unitName,
                            unitId = unitId,
                            amount = amount,
                            listIds = listIds,
                            listRecipeId = listRecipeId,
                            ingredientId = ingredientId,
                        ),
                        refs = ShoppingPayloads.createEntryRefs(listRecipeId),
                        dependsOn = dependsOn,
                        createdAtEpochMillis = now,
                    ),
                )
            }
            return localId
        }

        /** "Lägg på listan" (#12): one list recipe and one entry per chosen ingredient, scaled to the servings. */
        suspend fun addRecipe(request: RecipeShoppingRequest, recipeServings: Int) {
            val dao = database.shoppingDao()
            val chosen = dao.getRecipeIngredients(accountId, request.recipeId)
                .filter { it.ingredientId in request.ingredientIds && !it.isHeader && !it.foodName.isNullOrBlank() }
            if (chosen.isEmpty()) return
            val factor = if (recipeServings > 0) request.servings / recipeServings else 1.0
            val mealPlanId = request.mealPlanId?.let { current(OutboxEntities.MEAL_PLAN, it) }
            val listRecipe = ShoppingListRecipeEntity(
                accountId = accountId,
                localId = database.idRewriteDao().nextLocalId(accountId, OutboxEntities.SHOPPING_LIST_RECIPE),
                serverId = null,
                recipeId = request.recipeId,
                recipeName = dao.getRecipeName(accountId, request.recipeId),
                mealPlanId = mealPlanId,
                servings = request.servings,
            )
            dao.upsertListRecipes(listOf(listRecipe))
            val createRow = if (remote) {
                database.outboxDao().enqueue(
                    OutboxEntity(
                        accountId = accountId,
                        op = OutboxOps.CREATE,
                        entity = OutboxEntities.SHOPPING_LIST_RECIPE,
                        localId = listRecipe.localId,
                        payload = ShoppingPayloads.createListRecipe(request.recipeId, request.servings, "", mealPlanId),
                        refs = ShoppingPayloads.createListRecipeRefs(mealPlanId),
                        dependsOn = mealPlanId?.takeIf { it < 0 }?.let { createRowOf(OutboxEntities.MEAL_PLAN, it)?.id },
                        createdAtEpochMillis = now,
                    ),
                )
            } else {
                null
            }
            for (ingredient in chosen) {
                createEntry(
                    foodName = ingredient.foodName.orEmpty(),
                    foodId = ingredient.foodId,
                    categoryId = ingredient.foodId?.let { database.supermarketDao().getFood(accountId, it)?.supermarketCategoryId },
                    unitName = ingredient.unitName,
                    unitId = ingredient.unitId,
                    amount = if (ingredient.noAmount) 0.0 else ingredient.amount * factor,
                    listIds = request.listIds,
                    listRecipe = listRecipe,
                    ingredientId = ingredient.ingredientId,
                    dependsOn = createRow,
                )
            }
        }

        /**
         * Queues [op] for [entry]: a server entry by `server_id`, one that never reached the server by
         * `local_id` (`depends_on` its CREATE while that is being sent; otherwise the change merges into it).
         */
        suspend fun queue(
            entry: ShoppingEntryEntity,
            op: String,
            payload: String,
            baseSnapshot: String? = null,
            refs: String? = null,
            batchKey: String? = null,
        ) {
            if (!remote) return
            val serverId = entry.serverId
            database.outboxDao().enqueue(
                OutboxEntity(
                    accountId = accountId,
                    op = op,
                    entity = OutboxEntities.SHOPPING_ENTRY,
                    localId = if (serverId == null) entry.localId else null,
                    serverId = serverId,
                    payload = payload,
                    baseSnapshot = baseSnapshot,
                    refs = refs,
                    batchKey = batchKey,
                    dependsOn = if (serverId == null) sendingCreateOf(OutboxEntities.SHOPPING_ENTRY, entry.localId) else null,
                    createdAtEpochMillis = now,
                ),
            )
        }

        suspend fun queueListRecipe(listRecipe: ShoppingListRecipeEntity, op: String, payload: String) {
            if (!remote) return
            val serverId = listRecipe.serverId
            database.outboxDao().enqueue(
                OutboxEntity(
                    accountId = accountId,
                    op = op,
                    entity = OutboxEntities.SHOPPING_LIST_RECIPE,
                    localId = if (serverId == null) listRecipe.localId else null,
                    serverId = serverId,
                    payload = payload,
                    dependsOn = if (serverId == null) sendingCreateOf(OutboxEntities.SHOPPING_LIST_RECIPE, listRecipe.localId) else null,
                    createdAtEpochMillis = now,
                ),
            )
        }

        /** Removes [entry] here and queues its DELETE (which cancels an unsent CREATE and all queued for it). */
        suspend fun removeEntry(entry: ShoppingEntryEntity) {
            database.shoppingDao().deleteEntries(accountId, listOf(entry.localId))
            queue(entry, OutboxOps.DELETE, "{}")
        }

        /** The CREATE row of temporary id [localId] (any state), if it is still in the outbox. */
        private suspend fun createRowOf(entity: String, localId: Long): OutboxEntity? =
            database.outboxDao().getAllFor(accountId, entity).firstOrNull { it.op == OutboxOps.CREATE && it.localId == localId }

        /** True while [entry] never reached the server and its CREATE waits unsent (a change merges into it). */
        suspend fun hasUnsentCreate(entry: ShoppingEntryEntity): Boolean {
            if (!remote || entry.serverId != null) return false
            return createRowOf(OutboxEntities.SHOPPING_ENTRY, entry.localId)?.state?.let { it in UNSENT } == true
        }

        /**
         * The CREATE a change of an offline object must wait for: only one being sent (or failed). An
         * unsent CREATE takes the change itself (`OutboxMerge`), and depending on it would block it.
         */
        private suspend fun sendingCreateOf(entity: String, localId: Long): Long? =
            createRowOf(entity, localId)?.takeIf { it.state !in UNSENT }?.id
    }

    private companion object {
        /** States of an outbox row that `OutboxDao.enqueue` merges with. */
        val UNSENT: Set<OutboxState> = setOf(OutboxState.PENDING, OutboxState.PAUSED)
    }
}
