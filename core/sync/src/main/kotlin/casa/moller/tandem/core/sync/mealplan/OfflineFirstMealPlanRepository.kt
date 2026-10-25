package casa.moller.tandem.core.sync.mealplan

import casa.moller.tandem.core.data.DataProblem
import casa.moller.tandem.core.data.mealplan.MealPlanRepository
import casa.moller.tandem.core.data.permissions.SpacePermissionsRepository
import casa.moller.tandem.core.data.shopping.ShoppingRepository
import casa.moller.tandem.core.database.TandemDatabase
import casa.moller.tandem.core.database.TandemDatabaseFactory
import casa.moller.tandem.core.database.entity.MealPlanEntity
import casa.moller.tandem.core.database.entity.MealTypeEntity
import casa.moller.tandem.core.database.entity.OutboxEntities
import casa.moller.tandem.core.database.entity.OutboxEntity
import casa.moller.tandem.core.database.entity.OutboxOps
import casa.moller.tandem.core.database.entity.OutboxState
import casa.moller.tandem.core.database.mapper.toModel
import casa.moller.tandem.core.domain.mealplan.MealPlanDates
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.error.catchingUnexpected
import casa.moller.tandem.core.model.mealplan.MealPlanConflict
import casa.moller.tandem.core.model.mealplan.MealPlanDay
import casa.moller.tandem.core.model.mealplan.MealPlanDraft
import casa.moller.tandem.core.model.mealplan.MealPlanEntry
import casa.moller.tandem.core.model.mealplan.MealPlanUpdate
import casa.moller.tandem.core.model.mealplan.MealPlanWeek
import casa.moller.tandem.core.model.mealplan.MealType
import casa.moller.tandem.core.model.mealplan.MealTypeDraft
import casa.moller.tandem.core.model.mealplan.WeekShoppingIngredient
import casa.moller.tandem.core.model.mealplan.WeekShoppingMeal
import casa.moller.tandem.core.model.mealplan.WeekShoppingPreview
import casa.moller.tandem.core.model.mealplan.WeekShoppingSelection
import casa.moller.tandem.core.model.shopping.RecipeShoppingRequest
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.account.ActiveSources
import casa.moller.tandem.core.sync.platform.SyncClock
import casa.moller.tandem.core.sync.platform.SyncScheduler
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * [MealPlanRepository] on Room and the outbox (plan 3.5, 5.3, 5.7; feature #15, #16). See the
 * interface for the contract; how it is met:
 *
 * - **Offline first:** a new, changed, moved or removed meal is written to `meal_plan` and queued
 *   with `OutboxDao.enqueue` in ONE transaction, then the outbox worker is scheduled. The merge
 *   rules keep the user's last intent (a move of a meal that never reached the server becomes part
 *   of its CREATE; removing such a meal removes everything queued for it).
 * - **Temporary ids:** a meal or meal type created here gets a negative id (`IdRewriteDao.nextLocalId`);
 *   every function also accepts that id after the server answered. A meal with a new meal type
 *   refers to it through `refs` (`meal_type.id`) and waits for its CREATE (`depends_on`).
 * - **Dates** are days in the server's zone ([MealPlanDates]); a move keeps the meal's length and
 *   always sends both dates; the PATCH carries only the changed fields and the values they had, so
 *   the sender can compare them with the server first ([MealPlanOutboxHandler]).
 * - **Guests** get `DataProblem.READ_ONLY` before anything is stored (#42); **demo mode** writes the
 *   demo database and queues nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
internal class OfflineFirstMealPlanRepository @Inject constructor(
    private val sources: ActiveSources,
    private val databases: TandemDatabaseFactory,
    private val permissions: SpacePermissionsRepository,
    private val scheduler: SyncScheduler,
    private val clock: SyncClock,
    private val sync: MealPlanSync,
    private val shopping: Provider<ShoppingRepository>,
) : MealPlanRepository {
    /** Serializes local writes, so two taps never get the same temporary id. */
    private val writes = Mutex()

    // ------------------------------------------------------------------------------------ reads

    override fun observeWeek(start: LocalDate): Flow<MealPlanWeek> = sources.source
        .flatMapLatest { source ->
            when (source) {
                is ActiveSource.Stored -> week(source, start)
                ActiveSource.Loading -> emptyFlow<MealPlanWeek>()
                ActiveSource.None -> flowOf(weekOf(start, emptyList()))
            }
        }
        .catch { emit(weekOf(start, emptyList())) }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)

    private fun week(source: ActiveSource.Stored, start: LocalDate): Flow<MealPlanWeek> {
        val database = databases.get(source.kind)
        val end = start.plus(MealPlanWeek.DAYS - 1, DateTimeUnit.DAY)
        return combine(
            database.mealPlanDao().observeRange(source.accountId, start.toString(), end.toString()),
            database.outboxDao().observeAll(source.accountId),
        ) { rows, outbox ->
            val pending = PendingMeals.of(outbox)
            weekOf(start, rows.map { it.toModel(pending = pending.contains(it.entry)) })
        }
    }

    override fun observeMeal(id: Long): Flow<MealPlanEntry?> = sources.source
        .flatMapLatest { source ->
            if (source is ActiveSource.Stored) {
                val database = databases.get(source.kind)
                combine(
                    database.mealPlanDao().observeRow(source.accountId, id),
                    database.outboxDao().observeAll(source.accountId),
                ) { row, outbox ->
                    row?.toModel(pending = PendingMeals.of(outbox).contains(row.entry))
                }
            } else {
                flowOf(null)
            }
        }
        .catch { emit(null) }
        .distinctUntilChanged()

    override fun observeMealTypes(): Flow<List<MealType>> = sources.source
        .flatMapLatest { source ->
            if (source is ActiveSource.Stored) {
                databases.get(source.kind).referenceDao().observeMealTypes(source.accountId).map { rows -> rows.map { it.toModel() } }
            } else {
                flowOf(emptyList())
            }
        }
        .catch { emit(emptyList()) }
        .distinctUntilChanged()

    override fun observeConflicts(): Flow<List<MealPlanConflict>> = sources.source
        .flatMapLatest { source ->
            if (source is ActiveSource.Remote) {
                databases.get(source.kind).outboxDao().observeAll(source.accountId)
                    .map { rows -> rows.filter(::isConflict).flatMap(::conflictsOf) }
            } else {
                flowOf(emptyList())
            }
        }
        .catch { emit(emptyList()) }
        .distinctUntilChanged()

    override suspend fun refresh(): Outcome<Unit> = catchingUnexpected {
        when (val source = sources.current()) {
            is ActiveSource.Remote -> sync.refresh(source, background = true)
            ActiveSource.Demo -> Outcome.Success(Unit)
            ActiveSource.Loading, ActiveSource.None -> Outcome.Failure(DataProblem.NO_ACCOUNT.toError())
        }
    }

    // ------------------------------------------------------------------------------------ writes

    override suspend fun create(draft: MealPlanDraft): Outcome<Long> = write { context ->
        if (draft.recipeId == null && draft.title.isBlank()) return@write Outcome.Failure(TandemError.BadRequest())
        val type = context.mealType(draft.mealTypeId) ?: return@write Outcome.Failure(TandemError.BadRequest())
        val recipeName = draft.recipeId?.let { context.recipeName(it) }
        val to = draft.toDate?.takeIf { it >= draft.date } ?: draft.date
        val meal = MealPlanEntity(
            accountId = context.accountId,
            localId = context.database.idRewriteDao().nextLocalId(context.accountId, OutboxEntities.MEAL_PLAN),
            serverId = null,
            title = draft.title.trim(),
            recipeId = draft.recipeId,
            servings = draft.servings,
            note = draft.note.trim(),
            fromDate = draft.date.toString(),
            toDate = to.toString(),
            mealTypeId = type.id,
            recipeName = recipeName,
        )
        context.database.mealPlanDao().upsert(listOf(meal))
        context.queue(
            op = OutboxOps.CREATE,
            localId = meal.localId,
            serverId = null,
            payload = MealPlanPayloads.create(meal, type.name, recipeName, draft.addToShopping),
            mealTypeId = type.id,
        )
        Outcome.Success(meal.localId)
    }

    override suspend fun update(id: Long, update: MealPlanUpdate): Outcome<Unit> = write { context ->
        val meal = context.meal(id) ?: return@write Outcome.Failure(TandemError.NotFound)
        val from = LocalDate.parse(meal.fromDate)
        val to = LocalDate.parse(meal.toDate)
        val movedFrom = update.fromDate
        val movedTo = update.toDate
        val (newFrom, newTo) = when {
            movedFrom != null && movedTo != null -> movedFrom to maxOf(movedFrom, movedTo)
            movedFrom != null -> MealPlanDates.moved(from, to, movedFrom)
            movedTo != null -> from to maxOf(from, movedTo)
            else -> from to to
        }
        val type = update.mealTypeId?.let { context.mealType(it) ?: return@write Outcome.Failure(TandemError.BadRequest()) }
        val recipeId = update.recipeId ?: meal.recipeId
        val recipeName = if (recipeId != meal.recipeId) recipeId?.let { context.recipeName(it) } else meal.recipeName
        val changed = meal.copy(
            fromDate = newFrom.toString(),
            toDate = newTo.toString(),
            mealTypeId = type?.id ?: meal.mealTypeId,
            recipeId = recipeId,
            recipeName = recipeName,
            title = update.title?.trim() ?: meal.title,
            servings = update.servings ?: meal.servings,
            note = update.note?.trim() ?: meal.note,
        )
        val typeName = type?.name ?: context.mealType(meal.mealTypeId)?.name.orEmpty()
        val (payload, base) = MealPlanPayloads.patch(meal, changed, typeName, recipeName) ?: return@write Outcome.Success(Unit)
        context.database.mealPlanDao().upsert(listOf(changed))
        context.queue(
            op = OutboxOps.PATCH,
            localId = meal.localId.takeIf { meal.serverId == null },
            serverId = meal.serverId,
            payload = payload,
            baseSnapshot = base,
            mealTypeId = changed.mealTypeId.takeIf { it != meal.mealTypeId },
        )
        Outcome.Success(Unit)
    }

    override suspend fun delete(id: Long): Outcome<Unit> = write { context ->
        val meal = context.meal(id) ?: return@write Outcome.Success(Unit)
        context.database.mealPlanDao().delete(context.accountId, meal.localId)
        // The meal's last server day, so a later read can tell "deleted elsewhere" without a request.
        val day = MealPlanDates.serverDay(meal.toRaw) ?: LocalDate.parse(meal.toDate)
        context.queue(
            op = OutboxOps.DELETE,
            localId = meal.localId.takeIf { meal.serverId == null },
            serverId = meal.serverId,
            payload = buildJsonObject { put(MealPlanPayloads.TO_DATE, day.toString()) }.toString(),
        )
        Outcome.Success(Unit)
    }

    override suspend fun createMealType(draft: MealTypeDraft): Outcome<Long> = write { context ->
        val name = draft.name.trim()
        if (name.isEmpty()) return@write Outcome.Failure(TandemError.BadRequest())
        val reference = context.database.referenceDao()
        val existing = reference.getMealTypes(context.accountId)
        existing.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { return@write Outcome.Success(it.id) }
        val order = (existing.maxOfOrNull { it.typeOrder } ?: -1) + 1
        val id = context.database.idRewriteDao().nextLocalId(context.accountId, OutboxEntities.MEAL_TYPE)
        reference.upsertMealType(
            MealTypeEntity(
                accountId = context.accountId,
                id = id,
                name = name,
                typeOrder = order,
                time = draft.time?.toString(),
                color = draft.color,
            ),
        )
        context.queue(
            op = OutboxOps.CREATE,
            entity = OutboxEntities.MEAL_TYPE,
            localId = id,
            serverId = null,
            payload = MealPlanPayloads.createMealType(name, draft.time, draft.color, order),
        )
        Outcome.Success(id)
    }

    override suspend fun resolveConflict(conflict: MealPlanConflict, keepMine: Boolean): Outcome<Unit> = write { context ->
        val outbox = context.database.outboxDao()
        val serverId = context.database.idRewriteDao().current(context.accountId, OutboxEntities.MEAL_PLAN, conflict.mealPlanId)
        val row = outbox.getAllFor(context.accountId, OutboxEntities.MEAL_PLAN)
            .firstOrNull { row ->
                isConflict(row) && row.serverId == serverId && MealPlanPayloads.parse(row.payload)?.containsKey(conflict.field) == true
            }
            ?: return@write Outcome.Success(Unit)
        val mine = MealPlanPayloads.parse(row.payload) ?: JsonObject(emptyMap())
        val theirs = MealPlanPayloads.parse(row.baseSnapshot) ?: JsonObject(emptyMap())
        val fields = if (conflict.field in MealPlanPayloads.DATES) MealPlanPayloads.DATES.filter { it in mine } else listOf(conflict.field)
        if (keepMine) {
            // Sent again, compared with the value the server has now (so a third change is caught too).
            context.queue(
                op = OutboxOps.PATCH,
                localId = null,
                serverId = serverId,
                payload = JsonObject(mine.filterKeys { it in fields }).toString(),
                baseSnapshot = JsonObject(theirs.filterKeys { it in fields }).toString(),
            )
        } else {
            context.meal(serverId)?.let { meal ->
                context.database.mealPlanDao().upsert(listOf(applyTheirs(meal, theirs.filterKeys { it in fields })))
            }
        }
        val rest = JsonObject(mine.filterKeys { it !in fields })
        if (rest.isEmpty()) {
            outbox.deleteRows(listOf(row.id))
        } else {
            outbox.updateMerged(row.id, rest.toString(), row.batchKey, row.refs, row.dependsOn, context.now)
        }
        Outcome.Success(Unit)
    }

    // ---------------------------------------------------------------------- "Handla för veckan"

    override suspend fun weekShoppingPreview(start: LocalDate): Outcome<WeekShoppingPreview> = catchingUnexpected {
        val source = sources.current() as? ActiveSource.Stored
            ?: return@catchingUnexpected Outcome.Failure(DataProblem.NO_ACCOUNT.toError())
        val database = databases.get(source.kind)
        val accountId = source.accountId
        val week = observeWeek(start).first()
        val onList = database.mealPlanDao().getMealIdsOnShoppingList(accountId).toSet()
        val autoAdd = database.referenceDao().getUserPreference(accountId)?.mealPlanAutoAddShopping ?: false
        val foodsOnHand = database.referenceDao().getFoods(accountId).filter { it.onHand }.mapTo(HashSet()) { it.id }
        val meals = week.days.flatMap { it.meals }.distinctBy { it.id }.filter { it.recipe != null }.map { meal ->
            val already = meal.onShoppingList || meal.id in onList
            val detail = database.recipeDao().getDetail(accountId, requireNotNull(meal.recipe).id)
            val factor = detail?.detail?.servings?.takeIf { it > 0 }?.let { meal.servings / it } ?: 1.0
            val ingredients = detail?.ingredients.orEmpty()
                .filter { !it.isHeader && !it.foodName.isNullOrBlank() }
                .sortedWith(compareBy({ it.stepPosition }, { it.position }))
                .map { ingredient ->
                    val onHand = ingredient.foodOnHand || ingredient.foodId?.let { it in foodsOnHand } == true
                    WeekShoppingIngredient(
                        ingredientId = ingredient.ingredientId,
                        foodName = ingredient.foodName.orEmpty(),
                        amount = if (ingredient.noAmount) 0.0 else ingredient.amount * factor,
                        unitName = ingredient.unitName,
                        onHand = onHand,
                    )
                }
            WeekShoppingMeal(meal = meal, alreadyOnList = already, selected = !already, ingredients = ingredients)
        }
        Outcome.Success(WeekShoppingPreview(meals = meals, autoAddOnServer = autoAdd))
    }

    override suspend fun addWeekToShopping(selection: WeekShoppingSelection): Outcome<Unit> = catchingUnexpected {
        val source = sources.current() as? ActiveSource.Stored
            ?: return@catchingUnexpected Outcome.Failure(DataProblem.NO_ACCOUNT.toError())
        if (source is ActiveSource.Remote && readOnly()) return@catchingUnexpected Outcome.Failure(DataProblem.READ_ONLY.toError())
        val database = databases.get(source.kind)
        val onList = database.mealPlanDao().getMealIdsOnShoppingList(source.accountId).toSet()
        val requests = selection.ingredientIdsByMeal.filterValues { it.isNotEmpty() }.mapNotNull { (mealId, ingredientIds) ->
            val localId = database.idRewriteDao().current(source.accountId, OutboxEntities.MEAL_PLAN, mealId)
            val meal = database.mealPlanDao().get(source.accountId, localId)
            // Never twice (plan 6.2 step 2): a meal with entries on the list is skipped.
            val onTheList = meal != null && (meal.shopping || meal.localId in onList)
            val recipeId = meal?.recipeId?.takeUnless { onTheList }
            if (meal == null || recipeId == null) {
                null
            } else {
                RecipeShoppingRequest(recipeId, meal.servings, ingredientIds, mealPlanId = meal.localId)
            }
        }
        for (request in requests) {
            val added = shopping.get().addRecipe(request)
            if (added is Outcome.Failure) return@catchingUnexpected added
        }
        Outcome.Success(Unit)
    }

    // ------------------------------------------------------------------------------------ helpers

    private suspend fun readOnly(): Boolean = permissions.permissions.first().readOnly

    private suspend fun <T> write(block: suspend (WriteContext) -> Outcome<T>): Outcome<T> = catchingUnexpected {
        when (val source = sources.current()) {
            is ActiveSource.Stored -> {
                if (source is ActiveSource.Remote && readOnly()) return@catchingUnexpected Outcome.Failure(DataProblem.READ_ONLY.toError())
                val database = databases.get(source.kind)
                val result = writes.withLock {
                    var outcome: Outcome<T>? = null
                    database.mealPlanDao().inTransaction { outcome = block(WriteContext(source, database, clock.nowMillis())) }
                    requireNotNull(outcome)
                }
                if (source is ActiveSource.Remote && result is Outcome.Success) scheduler.scheduleOutbox()
                result
            }

            ActiveSource.Loading, ActiveSource.None -> Outcome.Failure(DataProblem.NO_ACCOUNT.toError())
        }
    }

    /** One local change: the source, its database and "now"; queues only for a real account. */
    private class WriteContext(val source: ActiveSource.Stored, val database: TandemDatabase, val now: Long) {
        val accountId: String = source.accountId

        /** The meal [id] (a temporary id is followed to its server id). */
        suspend fun meal(id: Long): MealPlanEntity? =
            database.mealPlanDao().get(accountId, database.idRewriteDao().current(accountId, OutboxEntities.MEAL_PLAN, id))

        suspend fun mealType(id: Long): MealTypeEntity? {
            val current = database.idRewriteDao().current(accountId, OutboxEntities.MEAL_TYPE, id)
            return database.referenceDao().getMealTypes(accountId).firstOrNull { it.id == current }
        }

        suspend fun recipeName(recipeId: Long): String? = database.recipeDao().getOverviews(accountId, listOf(recipeId)).firstOrNull()?.name

        /**
         * Queues a row (a real account only). A meal type that is still temporary is listed in
         * `refs` and the row waits for its CREATE.
         */
        @Suppress("LongParameterList") // The outbox row's own fields.
        suspend fun queue(
            op: String,
            localId: Long?,
            serverId: Long?,
            payload: String,
            entity: String = OutboxEntities.MEAL_PLAN,
            baseSnapshot: String? = null,
            mealTypeId: Long? = null,
        ) {
            if (source !is ActiveSource.Remote) return
            val outbox = database.outboxDao()
            val typeCreate = mealTypeId?.takeIf { it < 0 }?.let { typeId ->
                outbox.getAllFor(accountId, OutboxEntities.MEAL_TYPE).firstOrNull { it.op == OutboxOps.CREATE && it.localId == typeId }
            }
            outbox.enqueue(
                OutboxEntity(
                    accountId = accountId,
                    op = op,
                    entity = entity,
                    localId = localId,
                    serverId = serverId,
                    payload = payload,
                    baseSnapshot = baseSnapshot,
                    dependsOn = typeCreate?.id,
                    createdAtEpochMillis = now,
                    refs = mealTypeId?.let { MealPlanPayloads.refs(it) },
                ),
            )
        }
    }

    /** Which stored meals have unsent changes (also conflicts waiting for a decision). */
    private class PendingMeals(private val local: Set<Long>, private val server: Set<Long>) {
        fun contains(meal: MealPlanEntity): Boolean = meal.localId in local || (meal.serverId != null && meal.serverId in server)

        companion object {
            fun of(outbox: List<OutboxEntity>): PendingMeals {
                val rows = outbox.filter { it.entity == OutboxEntities.MEAL_PLAN }
                return PendingMeals(rows.mapNotNullTo(HashSet()) { it.localId }, rows.mapNotNullTo(HashSet()) { it.serverId })
            }
        }
    }

    private fun isConflict(row: OutboxEntity): Boolean =
        row.entity == OutboxEntities.MEAL_PLAN && row.state == OutboxState.FAILED && row.lastError == MealPlanPayloads.CONFLICT

    /** One conflict per field of a conflict row (the two dates are one), in [MealPlanPayloads.COMPARED] order. */
    private fun conflictsOf(row: OutboxEntity): List<MealPlanConflict> {
        val mine = MealPlanPayloads.parse(row.payload) ?: return emptyList()
        val theirs = MealPlanPayloads.parse(row.baseSnapshot) ?: JsonObject(emptyMap())
        val mealId = row.serverId ?: row.localId ?: return emptyList()
        return MealPlanPayloads.COMPARED
            .filter { it in mine && !(it == MealPlanPayloads.TO_DATE && MealPlanPayloads.FROM_DATE in mine) }
            .map { field ->
                val mineText = MealPlanPayloads.display(field, mine[field])
                MealPlanConflict(mealId, field, mineText, MealPlanPayloads.display(field, theirs[field]))
            }
    }

    /** [meal] with the server's values of [theirs] ("ta deras"). */
    private fun applyTheirs(meal: MealPlanEntity, theirs: Map<String, JsonElement>): MealPlanEntity {
        var result = meal
        for ((field, value) in theirs) {
            val text = (value as? JsonPrimitive)?.contentOrNull
            val objectId = ((value as? JsonObject)?.get("id") as? JsonPrimitive)?.longOrNull
            result = when (field) {
                MealPlanPayloads.FROM_DATE -> MealPlanDates.serverDay(text)?.let { result.copy(fromDate = "$it", fromRaw = text) } ?: result
                MealPlanPayloads.TO_DATE -> MealPlanDates.serverDay(text)?.let { result.copy(toDate = "$it", toRaw = text) } ?: result
                MealPlanPayloads.MEAL_TYPE -> objectId?.let { result.copy(mealTypeId = it) } ?: result
                MealPlanPayloads.RECIPE -> result.copy(recipeId = objectId, recipeName = nameOf(value))
                MealPlanPayloads.TITLE -> result.copy(title = text.orEmpty())
                MealPlanPayloads.NOTE -> result.copy(note = text.orEmpty())
                MealPlanPayloads.SERVINGS -> (value as? JsonPrimitive)?.doubleOrNull?.let { result.copy(servings = it) } ?: result
                else -> result
            }
        }
        return if (result.toDate < result.fromDate) result.copy(toDate = result.fromDate) else result
    }

    private fun nameOf(value: JsonElement): String? = ((value as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull

    private companion object {
        /** The 7 days from [start], each with the meals on it, by meal type order. */
        fun weekOf(start: LocalDate, meals: List<MealPlanEntry>): MealPlanWeek = MealPlanWeek(
            start = start,
            days = (0 until MealPlanWeek.DAYS).map { offset ->
                val day = start.plus(offset, DateTimeUnit.DAY)
                MealPlanDay(
                    date = day,
                    meals = meals.filter { MealPlanDates.covers(it.fromDate, it.toDate, day) }
                        .sortedWith(compareBy({ it.mealType.order }, { it.mealType.id }, { it.fromDate }, { it.id })),
                )
            },
        )
    }
}
