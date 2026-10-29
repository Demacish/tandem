package casa.moller.tandem.core.sync.drink

import casa.moller.tandem.core.data.DataProblem
import casa.moller.tandem.core.data.drink.DrinkProblem
import casa.moller.tandem.core.data.drink.DrinkRepository
import casa.moller.tandem.core.data.permissions.SpacePermissionsRepository
import casa.moller.tandem.core.database.TandemDatabase
import casa.moller.tandem.core.database.TandemDatabaseFactory
import casa.moller.tandem.core.database.entity.DrinkDraftEntity
import casa.moller.tandem.core.database.entity.KeywordEntity
import casa.moller.tandem.core.database.entity.OutboxEntities
import casa.moller.tandem.core.database.entity.OutboxEntity
import casa.moller.tandem.core.database.entity.OutboxOps
import casa.moller.tandem.core.database.entity.RecipeKeywordCrossRef
import casa.moller.tandem.core.database.mapper.toModel
import casa.moller.tandem.core.database.relation.RecipeListItem
import casa.moller.tandem.core.domain.drink.DraftKeyword
import casa.moller.tandem.core.domain.drink.DraftKeywordRole
import casa.moller.tandem.core.domain.drink.DrinkIndex
import casa.moller.tandem.core.model.drink.Drink
import casa.moller.tandem.core.model.drink.DrinkDraft
import casa.moller.tandem.core.model.drink.DrinkKeywords
import casa.moller.tandem.core.model.drink.Pairing
import casa.moller.tandem.core.model.drink.RecipePairings
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.catchingUnexpected
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.account.ActiveSources
import casa.moller.tandem.core.sync.platform.SyncClock
import casa.moller.tandem.core.sync.platform.SyncScheduler
import java.io.File
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [DrinkRepository] on Room and the outbox (#43, B20, plan §8.8; docs/BYGGE.md "Drycker"):
 *
 * - **Reads** come from the cached keywords and recipe-keyword links ([DrinkIndex]) with the queued
 *   pairing changes and the drinks created offline on top, so everything works offline and a
 *   pairing shows at once.
 * - **Pairings** are `RECIPE_KEYWORDS` rows (`keyword:<id>` for a stored pairing keyword,
 *   `pairing:<name>` for a new one), sent by `RecipeKeywordsOutboxHandler`, which reads the recipe
 *   fresh first (a PATCH replaces the list). A drink created offline keeps its pairings in its
 *   `drink_draft` row until its CREATE is sent. Every member can pair; a guest gets
 *   `DataProblem.READ_ONLY` before anything is stored.
 * - **A new drink** is a `drink_draft` row plus a `drink` CREATE (and a `RECIPE_IMAGE` for its
 *   photo, scaled to 1600 px JPEG 85 % by [DrinkImageStore]); its id is negative until it is sent.
 * - **Demo mode** writes the demo database directly and queues nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
internal class OfflineFirstDrinkRepository @Inject constructor(
    private val sources: ActiveSources,
    private val databases: TandemDatabaseFactory,
    private val permissions: SpacePermissionsRepository,
    private val scheduler: SyncScheduler,
    private val clock: SyncClock,
    private val images: DrinkImageStore,
    private val drinkSync: DrinkSync,
) : DrinkRepository {
    /** Serializes local writes, so two taps never get the same temporary id. */
    private val writes = Mutex()

    // ------------------------------------------------------------------------------------ reads

    override fun observeDrinks(type: String?): Flow<List<Drink>> = snapshots { source, snapshot ->
        val ids = snapshot.index.drinkIds.filter { it >= 0 }
        listItems(source, ids).map { rows ->
            val collator = collator()
            val drinks = rows.map { it.toModel() }.map { overview ->
                Drink(
                    id = overview.id,
                    name = overview.name,
                    imageUrl = overview.imageUrl,
                    types = snapshot.index.typesOf(overview.id),
                    rating = overview.rating,
                    note = overview.description,
                )
            } + snapshot.drafts.map { it.toDrink(snapshot.index) }
            drinks.filter { type == null || it.types.any { name -> DrinkKeywords.key(name) == DrinkKeywords.key(type) } }
                .sortedWith { a, b -> collator.compare(a.name, b.name) }
        }
    }

    override fun observeDrinkTypes(): Flow<List<String>> = snapshots { _, snapshot ->
        val collator = collator()
        flowOf(snapshot.index.typeNames.sortedWith { a, b -> collator.compare(a, b) })
    }

    override fun observePairings(): Flow<List<Pairing>> = snapshots { _, snapshot ->
        val collator = collator()
        flowOf(snapshot.index.pairings.sortedWith { a, b -> collator.compare(a.name, b.name) })
    }

    override fun observeRecipePairings(recipeId: Long): Flow<RecipePairings> = sources.source
        .flatMapLatest { source ->
            if (source !is ActiveSource.Stored) return@flatMapLatest flowOf(RecipePairings(recipeId))
            flow { emit(currentId(source, recipeId)) }.flatMapLatest { id ->
                snapshotOf(source).flatMapLatest { snapshot -> pairingsOf(source, snapshot, recipeId, id) }
            }
        }
        .catch { emit(RecipePairings(recipeId)) }
        .distinctUntilChanged()

    private fun pairingsOf(source: ActiveSource.Stored, snapshot: Snapshot, asked: Long, id: Long): Flow<RecipePairings> {
        val index = snapshot.index
        val collator = collator()
        val pairings = index.pairingsOf(id).sortedWith { a, b -> collator.compare(a.name, b.name) }
        if (index.isDrink(id)) {
            return flowOf(RecipePairings(recipeId = asked, pairings = pairings, dishIds = index.dishesFor(id)))
        }
        val drinkIds = index.drinksFor(id)
        if (drinkIds.isEmpty()) return flowOf(RecipePairings(recipeId = asked, pairings = pairings))
        return listItems(source, drinkIds.filter { it >= 0 }).map { items ->
            val drinks = items.map { it.toModel() }.map { overview ->
                Drink(overview.id, overview.name, overview.imageUrl, index.typesOf(overview.id), overview.rating, overview.description)
            } + snapshot.drafts.filter { it.localId in drinkIds }.map { it.toDrink(index) }
            RecipePairings(recipeId = asked, pairings = pairings, drinks = drinks.sortedWith { a, b -> collator.compare(a.name, b.name) })
        }
    }

    override fun observeIsDrink(recipeId: Long): Flow<Boolean> = sources.source
        .flatMapLatest { source ->
            if (source !is ActiveSource.Stored) return@flatMapLatest flowOf(false)
            flow { emit(currentId(source, recipeId)) }.flatMapLatest { id -> snapshotOf(source).map { it.index.isDrink(id) } }
        }
        .catch { emit(false) }
        .distinctUntilChanged()

    // ----------------------------------------------------------------------------------- writes

    override suspend fun addPairing(recipeId: Long, keywordId: Long): Outcome<Unit> = write { context ->
        val pairing = context.index.pairing(keywordId) ?: return@write Outcome.Failure(DrinkProblem.UNKNOWN_RECIPE.toError())
        context.link(recipeId, pairing)
        Outcome.Success(Unit)
    }

    override suspend fun addNewPairing(recipeId: Long, name: String): Outcome<Pairing> = write { context ->
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return@write Outcome.Failure(DrinkProblem.NAME_EMPTY.toError())
        val existing = context.index.pairingNamed(trimmed)
        if (existing == null && context.index.nameTakenOutsidePairings(trimmed)) {
            return@write Outcome.Failure(DrinkProblem.NAME_TAKEN.toError())
        }
        val pairing = existing ?: context.newPairing(trimmed)
        context.link(recipeId, pairing)
        Outcome.Success(pairing)
    }

    override suspend fun pairWithDrink(recipeId: Long, drinkId: Long): Outcome<Pairing> = write { context ->
        val drink = context.currentId(drinkId)
        val name = context.recipeName(drink) ?: return@write Outcome.Failure(DrinkProblem.UNKNOWN_RECIPE.toError())
        val own = context.index.ownPairingOf(drink, name) ?: context.index.pairingNamed(name)
        if (own == null && context.index.nameTakenOutsidePairings(name)) return@write Outcome.Failure(DrinkProblem.NAME_TAKEN.toError())
        val pairing = own ?: context.newPairing(name)
        context.link(drink, pairing.copy(isTypeOnly = false))
        context.link(recipeId, pairing.copy(isTypeOnly = false))
        Outcome.Success(pairing.copy(isTypeOnly = false))
    }

    override suspend fun removePairing(recipeId: Long, keywordId: Long): Outcome<Unit> = write { context ->
        val pairing = context.index.pairing(keywordId) ?: return@write Outcome.Success(Unit)
        context.unlink(recipeId, pairing)
        Outcome.Success(Unit)
    }

    override suspend fun createDrink(draft: DrinkDraft): Outcome<Drink> {
        val name = draft.name.trim()
        if (name.isEmpty()) return Outcome.Failure(DrinkProblem.NAME_EMPTY.toError())
        // The photo is copied (and scaled) before the transaction; a guest is refused first.
        if (readOnlyRemote()) return Outcome.Failure(DataProblem.READ_ONLY.toError())
        val accountId = (sources.current() as? ActiveSource.Stored)?.accountId
        val photo = draft.imageUri?.let { uri -> accountId?.let { images.copy(it, uri) } }
        val result = write { context -> Outcome.Success(context.createDrink(draft.copy(name = name), photo)) }
        if (result is Outcome.Failure && photo != null) images.delete(photo.path)
        return result
    }

    override suspend fun refresh(): Outcome<Unit> = when (val source = sources.current()) {
        is ActiveSource.Remote -> drinkSync.refresh(source).also { scheduler.scheduleOutbox() }
        ActiveSource.Demo -> Outcome.Success(Unit)
        ActiveSource.Loading, ActiveSource.None -> Outcome.Failure(DataProblem.NO_ACCOUNT.toError())
    }

    // ------------------------------------------------------------------------------------ helpers

    private suspend fun readOnlyRemote(): Boolean = sources.current() is ActiveSource.Remote && permissions.permissions.first().readOnly

    private fun listItems(source: ActiveSource.Stored, ids: List<Long>): Flow<List<RecipeListItem>> =
        if (ids.isEmpty()) flowOf(emptyList()) else databases.get(source.kind).recipeBookDao().observeListItems(source.accountId, ids)

    private fun collator(): Collator = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.SECONDARY }

    private suspend fun currentId(source: ActiveSource.Stored, id: Long): Long =
        databases.get(source.kind).idRewriteDao().current(source.accountId, OutboxEntities.DRINK, id)

    /** The index and the drafts of [source], updated on every change. */
    private fun snapshotOf(source: ActiveSource.Stored): Flow<Snapshot> {
        val database = databases.get(source.kind)
        return combine(
            observeDrinkIndex(database, source.accountId, withOutbox = source is ActiveSource.Remote),
            database.drinkDraftDao().observeDrafts(source.accountId),
        ) { index, drafts -> Snapshot(index, drafts) }
    }

    private fun <T> snapshots(flow: (ActiveSource.Stored, Snapshot) -> Flow<List<T>>): Flow<List<T>> = sources.source
        .flatMapLatest { source ->
            if (source is ActiveSource.Stored) snapshotOf(source).flatMapLatest { flow(source, it) } else flowOf(emptyList())
        }
        .catch { emit(emptyList()) }
        .distinctUntilChanged()

    /**
     * Runs a local change for the active source in one transaction ([WriteContext]); guests are
     * refused first. Schedules the outbox for a real account when it succeeded.
     */
    private suspend fun <T> write(block: suspend (WriteContext) -> Outcome<T>): Outcome<T> = catchingUnexpected {
        when (val source = sources.current()) {
            is ActiveSource.Stored -> {
                if (source is ActiveSource.Remote && permissions.permissions.first().readOnly) {
                    return@catchingUnexpected Outcome.Failure(DataProblem.READ_ONLY.toError())
                }
                val database = databases.get(source.kind)
                val result = writes.withLock {
                    var value: Outcome<T>? = null
                    database.recipeBookDao().inTransaction {
                        val index = readDrinkIndex(database, source.accountId, withOutbox = source is ActiveSource.Remote)
                        value = block(WriteContext(source, database, index, clock.nowMillis()))
                    }
                    @Suppress("UNCHECKED_CAST") // Set by the block before the transaction returns.
                    value as Outcome<T>
                }
                if (source is ActiveSource.Remote && result is Outcome.Success) scheduler.scheduleOutbox()
                result
            }

            ActiveSource.Loading, ActiveSource.None -> Outcome.Failure(DataProblem.NO_ACCOUNT.toError())
        }
    }

    private data class Snapshot(val index: DrinkIndex, val drafts: List<DrinkDraftEntity>)

    /** One local change: the source, its database, the index as it is now and "now". */
    private inner class WriteContext(val source: ActiveSource.Stored, val database: TandemDatabase, val index: DrinkIndex, val now: Long) {
        private val accountId: String = source.accountId
        private val remote: Boolean = source is ActiveSource.Remote
        private val drinks = database.drinkDraftDao()

        suspend fun currentId(id: Long): Long = database.idRewriteDao().current(accountId, OutboxEntities.DRINK, id)

        /** The name of a stored recipe, or of a drink created offline. */
        suspend fun recipeName(id: Long): String? = if (id < 0) drinks.get(accountId, id)?.name else drinks.getRecipeName(accountId, id)

        /** A pairing keyword that does not exist yet: pending until a sender creates it (demo: created now). */
        suspend fun newPairing(name: String): Pairing {
            if (remote) return Pairing(DrinkIndex.pendingKeywordId(name), name, isTypeOnly = true)
            val root = index.pairsWithRootId?.takeIf { it >= 0 } ?: demoKeyword(DrinkKeywords.PAIRS_WITH, parentId = null)
            return Pairing(demoKeyword(name, parentId = root), name, isTypeOnly = true)
        }

        /** Adds [pairing] to recipe [recipeId] (nothing when it carries it already). */
        suspend fun link(recipeId: Long, pairing: Pairing) {
            val id = currentId(recipeId)
            if (index.pairingsOf(id).any { it.keywordId == pairing.keywordId }) return
            when {
                id < 0 -> editDraft(id) { keywords -> keywords + DraftKeyword(pairing.name, DraftKeywordRole.PAIRING) }
                !remote -> drinks.insertKeywordRefs(listOf(RecipeKeywordCrossRef(accountId, id, pairing.keywordId)))
                else -> enqueueToggle(id, pairing, add = true)
            }
        }

        /** Removes [pairing] from recipe [recipeId] (nothing when it does not carry it). */
        suspend fun unlink(recipeId: Long, pairing: Pairing) {
            val id = currentId(recipeId)
            if (index.pairingsOf(id).none { it.keywordId == pairing.keywordId }) return
            val key = DrinkKeywords.key(pairing.name)
            when {
                id < 0 -> editDraft(id) { list ->
                    list.filterNot { it.role == DraftKeywordRole.PAIRING && DrinkKeywords.key(it.name) == key }
                }

                !remote -> drinks.deleteKeywordRef(accountId, id, pairing.keywordId)

                else -> enqueueToggle(id, pairing, add = false)
            }
        }

        suspend fun createDrink(draft: DrinkDraft, photo: File?): Drink {
            val keywords = buildList {
                add(DraftKeyword(DrinkKeywords.DRINK, DraftKeywordRole.DRINK))
                draft.types.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { DrinkKeywords.key(it) }
                    .forEach { add(DraftKeyword(it, DraftKeywordRole.TYPE)) }
                val pairings = draft.pairingKeywordIds.mapNotNull { index.pairing(it)?.name } + draft.newPairingNames
                pairings.map { it.trim() }.filter { it.isNotEmpty() }.forEach { add(DraftKeyword(it, DraftKeywordRole.PAIRING)) }
            }.distinctBy { it.role to DrinkKeywords.key(it.name) }
            if (!remote) return DemoDrinks.create(database, accountId, draft, keywords, photo, now)
            val localId = database.idRewriteDao().nextLocalId(accountId, OutboxEntities.DRINK)
            drinks.upsert(
                DrinkDraftEntity(
                    accountId = accountId,
                    localId = localId,
                    name = draft.name,
                    description = draft.description.trim(),
                    servings = draft.servings.coerceAtLeast(1),
                    keywords = DrinkPayloads.encodeDraftKeywords(keywords),
                    imagePath = photo?.path,
                    createdAtEpochMillis = now,
                ),
            )
            val createId = database.outboxDao().enqueue(
                OutboxEntity(
                    accountId = accountId,
                    op = OutboxOps.CREATE,
                    entity = OutboxEntities.DRINK,
                    localId = localId,
                    payload = DrinkPayloads.createDrink(localId),
                    createdAtEpochMillis = now,
                ),
            )
            if (photo != null) {
                database.outboxDao().enqueue(
                    OutboxEntity(
                        accountId = accountId,
                        op = OutboxOps.RECIPE_IMAGE,
                        entity = OutboxEntities.DRINK,
                        localId = localId,
                        payload = DrinkPayloads.image(photo.path),
                        dependsOn = createId,
                        createdAtEpochMillis = now,
                    ),
                )
            }
            return Drink(
                id = localId,
                name = draft.name,
                imageUrl = photo?.let { "file://${it.path}" },
                types = keywords.filter { it.role == DraftKeywordRole.TYPE }.map { it.name },
                note = draft.description.trim().ifEmpty { null },
                pending = true,
            )
        }

        private suspend fun editDraft(localId: Long, change: (List<DraftKeyword>) -> List<DraftKeyword>) {
            val draft = drinks.get(accountId, localId) ?: return
            val keywords = change(DrinkPayloads.decodeDraftKeywords(draft.keywords))
                .distinctBy { it.role to DrinkKeywords.key(it.name) }
            drinks.upsert(draft.copy(keywords = DrinkPayloads.encodeDraftKeywords(keywords)))
        }

        /** A pending pairing goes by name (the sender creates it), a stored one by its id (a rename never breaks it). */
        private suspend fun enqueueToggle(recipeId: Long, pairing: Pairing, add: Boolean) {
            if (index.isPending(pairing.keywordId)) {
                enqueue(recipeId, DrinkPayloads.pairing(pairing.name, add), DrinkPayloads.pairing(pairing.name, !add))
            } else {
                enqueue(recipeId, DrinkPayloads.keyword(pairing.keywordId, add), DrinkPayloads.keyword(pairing.keywordId, !add))
            }
        }

        private suspend fun enqueue(recipeId: Long, payload: String, base: String) {
            database.outboxDao().enqueue(
                OutboxEntity(
                    accountId = accountId,
                    op = OutboxOps.RECIPE_KEYWORDS,
                    entity = OutboxEntities.RECIPE,
                    serverId = recipeId,
                    payload = payload,
                    baseSnapshot = base,
                    createdAtEpochMillis = now,
                ),
            )
        }

        /** Demo mode: a keyword written directly (the next free id). */
        private suspend fun demoKeyword(name: String, parentId: Long?): Long {
            val id = drinks.getMaxKeywordId(accountId) + 1
            drinks.upsertKeywords(listOf(KeywordEntity(accountId, id, name, parentId = parentId)))
            return id
        }
    }
}

/** A drink created offline as the lists show it (its photo from the app-private copy). */
private fun DrinkDraftEntity.toDrink(index: DrinkIndex): Drink = Drink(
    id = localId,
    name = name,
    imageUrl = imagePath?.let { "file://$it" },
    types = index.typesOf(localId),
    note = description.ifBlank { null },
    pending = true,
)
