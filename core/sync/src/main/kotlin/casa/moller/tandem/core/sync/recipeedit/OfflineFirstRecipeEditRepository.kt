package casa.moller.tandem.core.sync.recipeedit

import casa.moller.tandem.core.data.DataProblem
import casa.moller.tandem.core.data.permissions.SpacePermissionsRepository
import casa.moller.tandem.core.data.recipe.RecipeEditProblem
import casa.moller.tandem.core.data.recipe.RecipeEditRepository
import casa.moller.tandem.core.database.TandemDatabase
import casa.moller.tandem.core.database.TandemDatabaseFactory
import casa.moller.tandem.core.database.entity.OutboxEntities
import casa.moller.tandem.core.database.entity.OutboxEntity
import casa.moller.tandem.core.database.entity.OutboxOps
import casa.moller.tandem.core.database.entity.OutboxState
import casa.moller.tandem.core.database.mapper.toDetailWrite
import casa.moller.tandem.core.database.mapper.toModel
import casa.moller.tandem.core.database.mapper.toRecipe
import casa.moller.tandem.core.domain.drink.DrinkIndex
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.catchingUnexpected
import casa.moller.tandem.core.model.error.map
import casa.moller.tandem.core.model.recipe.Food
import casa.moller.tandem.core.model.recipe.Keyword
import casa.moller.tandem.core.model.recipe.MeasurementUnit
import casa.moller.tandem.core.model.recipe.Recipe
import casa.moller.tandem.core.model.recipe.RecipeDraft
import casa.moller.tandem.core.model.recipe.RecipeEditStatus
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.account.ActiveSources
import casa.moller.tandem.core.sync.drink.DrinkImageStore
import casa.moller.tandem.core.sync.platform.SyncClock
import casa.moller.tandem.core.sync.platform.SyncScheduler
import java.io.File
import java.text.Collator
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Instant
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
import kotlinx.serialization.json.JsonObject

/**
 * [RecipeEditRepository] on Room and the outbox (#22, plan §8.9; docs/BYGGE.md "Receptredigeraren"):
 *
 * - **A new recipe** is written to the recipe tables at once under a temporary (negative) id
 *   (`IdRewriteDao.nextLocalId`, entity `recipe_edit`), with the request body as its raw JSON, and
 *   queued as a `recipe_edit` CREATE whose payload is that body. It is in the list and opens like
 *   any recipe; `completeCreate` drops the temporary rows once the sender stored the answer.
 * - **An edit** replaces the stored copy at once (steps and ingredients keep their ids; the image,
 *   rating and cook statistics stay) and queues a PATCH with the whole recipe and `base_snapshot`
 *   `{"updated_at"}`. A sync does not overwrite a recipe with an unsent edit (`DetailFetcher`,
 *   `RecipeDao.upsertOverviews`); the sender stores the server's answer. An edit of a recipe not
 *   sent yet merges into its CREATE (`OutboxMerge`).
 * - **A delete** removes the recipe locally at once (with its unsent `recipe` rows) and queues a
 *   DELETE; for a recipe not sent yet the CREATE simply cancels. Fas 4 review (fix-data): FAILED rows
 *   of the recipe go first, or the DELETE would wait behind them for ever; a CREATE the server
 *   refused cancels the whole thing.
 * - **A photo** is copied and scaled by [DrinkImageStore] (the drinks' store, shared) and queued as
 *   `RECIPE_IMAGE`, after the CREATE for a new recipe. One unsent picture change per recipe: the
 *   newest replaces the older (`OutboxMerge`) and the older one's copy is deleted here.
 * - **Conflict:** an edit the sender held back ([RecipeEditPayloads.CONFLICT]) is a FAILED row whose
 *   payload [observeDraft] hands the editor; [discardEdit] drops it, with the picture change it came
 *   with. A new save supersedes it, and its picture change goes after the new PATCH.
 * - **A CREATE the server refused** (Fas 4 review, fix-data) is the only copy of the recipe: a new
 *   save writes its body into that row and puts it back in the queue with what failed with it.
 * - **Demo mode** writes the demo database directly (ids above the demo recipes) and queues nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
internal class OfflineFirstRecipeEditRepository @Inject constructor(
    private val sources: ActiveSources,
    private val databases: TandemDatabaseFactory,
    private val permissions: SpacePermissionsRepository,
    private val scheduler: SyncScheduler,
    private val clock: SyncClock,
    private val images: DrinkImageStore,
) : RecipeEditRepository {
    /** Serializes local writes, so two taps never get the same temporary id. */
    private val writes = Mutex()

    // ------------------------------------------------------------------------------------ reads

    override fun observeUnits(): Flow<List<MeasurementUnit>> = sources.source
        .flatMapLatest { source ->
            if (source !is ActiveSource.Stored) return@flatMapLatest flowOf(emptyList())
            databases.get(source.kind).referenceDao().observeUnits(source.accountId).map { rows ->
                val collator = collator()
                rows.map { MeasurementUnit(id = it.id, name = it.name, pluralName = it.pluralName, baseUnit = it.baseUnit) }
                    .sortedWith { a, b -> collator.compare(a.name, b.name) }
            }
        }
        .catch { emit(emptyList()) }
        .distinctUntilChanged()

    override fun observeDraft(recipeId: Long): Flow<RecipeDraft?> = sources.source
        .flatMapLatest { source ->
            if (source !is ActiveSource.Stored) return@flatMapLatest flowOf(null)
            val database = databases.get(source.kind)
            database.observeCurrentRecipeId(source.accountId, recipeId).flatMapLatest { id ->
                combine(
                    database.outboxDao().observeAll(source.accountId),
                    database.recipeDao().observeListItem(source.accountId, id),
                    database.recipeDao().observeDetail(source.accountId, id),
                    database.recipeDao().observeKeywords(source.accountId),
                ) { rows, item, detail, keywords ->
                    val kept = rows.lastOrNull { it.isEditOf(id) && it.isConflict }
                    kept?.let { RecipeEditPayloads.decode(it.payload, keywords.associate { k -> k.id to k.name }) }
                        ?: detail?.toRecipe(item?.overview, item?.toModel()?.keywords.orEmpty())?.let { RecipeDraft.from(it) }
                }
            }
        }
        .catch { emit(null) }
        .distinctUntilChanged()

    override fun observeEditStatus(recipeId: Long): Flow<RecipeEditStatus> = sources.source
        .flatMapLatest { source ->
            if (source !is ActiveSource.Remote) return@flatMapLatest flowOf(RecipeEditStatus.NONE)
            val database = databases.get(source.kind)
            database.observeCurrentRecipeId(source.accountId, recipeId).flatMapLatest { id ->
                database.outboxDao().observeAll(source.accountId).map { rows ->
                    val own = rows.filter { it.isEditOf(id) }
                    when {
                        own.any { it.op == OutboxOps.CREATE && it.state == OutboxState.FAILED } -> RecipeEditStatus.REFUSED_NEW
                        own.any { it.state != OutboxState.FAILED } || (id < 0 && own.isEmpty()) -> RecipeEditStatus.QUEUED
                        own.any { it.isConflict } -> RecipeEditStatus.CONFLICT
                        own.any { it.state == OutboxState.FAILED } -> RecipeEditStatus.FAILED
                        else -> RecipeEditStatus.NONE
                    }
                }
            }
        }
        .catch { emit(RecipeEditStatus.NONE) }
        .distinctUntilChanged()

    // ----------------------------------------------------------------------------------- writes

    override suspend fun createRecipe(draft: RecipeDraft): Outcome<Long> = save(draft, recipeId = null)

    override suspend fun updateRecipe(recipeId: Long, draft: RecipeDraft): Outcome<Unit> = save(draft, recipeId).map { }

    private suspend fun save(draft: RecipeDraft, recipeId: Long?): Outcome<Long> {
        val normalized = draft.normalized()
        if (normalized.name.isEmpty()) return Outcome.Failure(RecipeEditProblem.NAME_EMPTY.toError())
        if (readOnlyRemote()) return Outcome.Failure(DataProblem.READ_ONLY.toError())
        // The photo is copied (and scaled) before the transaction; a photo that cannot be read is a failure the form says.
        val accountId = (sources.current() as? ActiveSource.Stored)?.accountId
        val photo = normalized.imageUri?.let { uri -> accountId?.let { images.copy(it, uri) } }
        if (normalized.imageUri != null && accountId != null && photo == null) {
            return Outcome.Failure(RecipeEditProblem.PHOTO_UNREADABLE.toError())
        }
        val result = write { context ->
            if (recipeId == null) Outcome.Success(context.create(normalized, photo)) else context.update(recipeId, normalized, photo)
        }
        if (result is Outcome.Failure && photo != null) images.delete(photo.path)
        return result
    }

    override suspend fun deleteRecipe(recipeId: Long): Outcome<Unit> = write { context -> context.delete(recipeId) }

    override suspend fun discardEdit(recipeId: Long): Outcome<Unit> = write { context -> context.discard(recipeId) }

    // ------------------------------------------------------------------------------------ helpers

    private suspend fun readOnlyRemote(): Boolean = sources.current() is ActiveSource.Remote && permissions.permissions.first().readOnly

    private fun collator(): Collator = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.SECONDARY }

    private fun OutboxEntity.isEditOf(id: Long): Boolean =
        entity == OutboxEntities.RECIPE_EDIT && op != OutboxOps.RECIPE_IMAGE && (serverId == id || (serverId == null && localId == id))

    /** A picture change of recipe [id] (`RECIPE_IMAGE` has its own rules: it is no edit of the recipe's text). */
    private fun OutboxEntity.isImageOf(id: Long): Boolean =
        entity == OutboxEntities.RECIPE_EDIT && op == OutboxOps.RECIPE_IMAGE && (serverId == id || localId == id)

    /** An edit the sender held back because the recipe changed on the server meanwhile. */
    private val OutboxEntity.isConflict: Boolean
        get() = state == OutboxState.FAILED && lastError == RecipeEditPayloads.CONFLICT

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

    /** One local change: the source, its database and "now". */
    private inner class WriteContext(val source: ActiveSource.Stored, val database: TandemDatabase, val now: Long) {
        private val accountId: String = source.accountId
        private val remote: Boolean = source is ActiveSource.Remote
        private val recipes = database.recipeDao()
        private val outbox = database.outboxDao()

        suspend fun create(draft: RecipeDraft, photo: File?): Long {
            val id = if (remote) {
                database.idRewriteDao().nextLocalId(accountId, OutboxEntities.RECIPE_EDIT)
            } else {
                database.drinkDraftDao().getMaxRecipeId(accountId).coerceAtLeast(DEMO_CREATED_FIRST_ID - 1) + 1
            }
            val body = RecipeEditPayloads.encode(draft)
            store(id, draft, stored = null, photo = photo, rawJson = body.toString())
            if (!remote) return id
            val createId = outbox.enqueue(
                OutboxEntity(
                    accountId = accountId,
                    op = OutboxOps.CREATE,
                    entity = OutboxEntities.RECIPE_EDIT,
                    localId = id,
                    payload = body.toString(),
                    createdAtEpochMillis = now,
                ),
            )
            if (photo != null) queueImage(id, photo, dependsOn = createId)
            draft.imageUrl?.takeIf { photo == null }?.let { queueImageUrl(id, it, dependsOn = createId) }
            return id
        }

        suspend fun update(recipeId: Long, draft: RecipeDraft, photo: File?): Outcome<Long> {
            val id = database.idRewriteDao().current(accountId, OutboxEntities.RECIPE_EDIT, recipeId)
            val item = recipes.getListItems(accountId, listOf(id)).firstOrNull()
            val stored = recipes.getDetail(accountId, id)?.toRecipe(item?.overview, item?.toModel()?.keywords.orEmpty())
                ?: return Outcome.Failure(RecipeEditProblem.UNKNOWN_RECIPE.toError())
            val body = RecipeEditPayloads.encode(draft)
            val storedJson = recipes.getDetailRow(accountId, id)?.rawJson?.let { RecipeEditPayloads.parse(it) }
            store(id, draft, stored, photo, rawJson = RecipeEditPayloads.withStoredNutrition(body, storedJson).toString())
            if (!remote) return Outcome.Success(id)
            val rows = outbox.getAllFor(accountId, OutboxEntities.RECIPE_EDIT)
            // A FAILED PATCH is superseded by the new edit: a kept conflict (the editor started from the
            // server's version then), or one the server refused.
            val superseded = rows.filter { it.isEditOf(id) && it.op == OutboxOps.PATCH && it.state == OutboxState.FAILED }
            val base = baseOf(id, storedJson, stored, superseded)
            if (superseded.isNotEmpty()) outbox.deleteWithDependents(superseded.map { it.id })
            val refused = rows.firstOrNull { it.isEditOf(id) && it.op == OutboxOps.CREATE && it.state == OutboxState.FAILED }
            if (refused != null) {
                outbox.requeueRefusedCreate(refused.id, body.toString())
                queueImageChange(id, draft, photo, dependsOn = refused.id)
                return Outcome.Success(id)
            }
            // The picture change that came with a superseded edit goes after the new PATCH (a row waits
            // for older rows of its recipe, so it has to be queued anew): the editor knows nothing of it,
            // so it must not be lost, and it must not move `updated_at` before the PATCH is compared.
            val waitingImages = if (superseded.isEmpty()) {
                emptyList()
            } else {
                rows.filter { it.isImageOf(id) && it.state != OutboxState.IN_FLIGHT }
            }
            if (waitingImages.isNotEmpty()) outbox.deleteRows(waitingImages.map { it.id })
            val create = if (id < 0) outbox.getCreate(accountId, OutboxEntities.RECIPE_EDIT, id) else null
            val patchId = outbox.enqueue(
                OutboxEntity(
                    accountId = accountId,
                    op = OutboxOps.PATCH,
                    entity = OutboxEntities.RECIPE_EDIT,
                    localId = id.takeIf { it < 0 },
                    serverId = id.takeIf { it >= 0 },
                    payload = body.toString(),
                    baseSnapshot = base,
                    createdAtEpochMillis = now,
                ),
            )
            waitingImages.forEach { row ->
                outbox.enqueue(row.copy(id = 0, state = OutboxState.PENDING, lastError = null, attempts = 0, createdAtEpochMillis = now))
            }
            queueImageChange(id, draft, photo, dependsOn = create?.id ?: patchId.takeIf { id < 0 })
            return Outcome.Success(id)
        }

        /**
         * What the conflict check compares with: the `updated_at` and the steps of the copy the edit
         * started from. Fas 4 review (fix-data): both come from the stored JSON. The overview's
         * `updated_at` moves with a sync while the detail stays old, so a web change the draft never
         * saw would look like the copy it started from. An unsent edit's body stands there until the
         * answer comes (it has no `updated_at`): a merge keeps the older row's base then, the base of
         * a FAILED PATCH is taken over, and the overview's value is what is left.
         */
        private fun baseOf(id: Long, storedJson: JsonObject?, stored: Recipe, superseded: List<OutboxEntity>): String {
            if (id < 0) return RecipeEditPayloads.base(null)
            val updatedAt = RecipeEditPayloads.updatedAtOf(storedJson)
            if (updatedAt != null) return RecipeEditPayloads.base(updatedAt, RecipeEditPayloads.stepsFingerprint(storedJson))
            return superseded.lastOrNull()?.baseSnapshot ?: RecipeEditPayloads.base(stored.updatedAt)
        }

        /**
         * The picture change of a save, the newest intent of the recipe's: a new photo, an import's
         * picture on the web, or "Ta bort bilden" ([draft] without `keepImage`). On a recipe the
         * server has not got, removing simply cancels what is queued.
         */
        private suspend fun queueImageChange(id: Long, draft: RecipeDraft, photo: File?, dependsOn: Long?) {
            val url = draft.imageUrl
            when {
                photo != null -> {
                    dropReplacedImages(id, keep = photo.path)
                    queueImage(id, photo, dependsOn)
                }

                url != null -> {
                    dropReplacedImages(id, keep = null)
                    queueImageUrl(id, url, dependsOn)
                }

                draft.keepImage -> Unit

                id < 0 -> {
                    dropReplacedImages(id, keep = null)
                    val queued = outbox.getAllFor(accountId, OutboxEntities.RECIPE_EDIT)
                        .filter { it.isImageOf(id) && it.state != OutboxState.IN_FLIGHT }
                    if (queued.isNotEmpty()) outbox.deleteWithDependents(queued.map { it.id })
                }

                else -> {
                    dropReplacedImages(id, keep = null)
                    queueImageRemoval(id)
                }
            }
        }

        /**
         * A newer picture change replaces the older one (`OutboxMerge`): the older one's photo copy
         * goes, except [keep]. A FAILED row never merges and would hold the new one back, so it goes too.
         */
        private suspend fun dropReplacedImages(id: Long, keep: String?) {
            val rows = outbox.getAllFor(accountId, OutboxEntities.RECIPE_EDIT)
                .filter { it.isImageOf(id) && it.state != OutboxState.IN_FLIGHT }
            rows.mapNotNull { RecipeEditPayloads.imagePathOf(it.payload) }.filter { it != keep }.forEach { images.delete(it) }
            val failed = rows.filter { it.state == OutboxState.FAILED }.map { it.id }
            if (failed.isNotEmpty()) outbox.deleteRows(failed)
        }

        suspend fun delete(recipeId: Long): Outcome<Unit> {
            val id = database.idRewriteDao().current(accountId, OutboxEntities.RECIPE_EDIT, recipeId)
            val rows = outbox.getAllFor(accountId, OutboxEntities.RECIPE_EDIT)
            // Photo copies of unsent image rows of the recipe go with them.
            rows.filter { it.isImageOf(id) && it.state != OutboxState.IN_FLIGHT }
                .mapNotNull { RecipeEditPayloads.imagePathOf(it.payload) }
                .forEach { images.delete(it) }
            // A FAILED row of the recipe would hold its DELETE back for ever.
            val failed = rows.filter { (it.isEditOf(id) || it.isImageOf(id)) && it.state == OutboxState.FAILED }
            if (failed.isNotEmpty()) outbox.deleteWithDependents(failed.map { it.id })
            recipes.deleteRecipes(accountId, listOf(id))
            // A CREATE the server refused never made a recipe there: nothing to delete. (One that may
            // have reached it, a lost answer, is not FAILED: findLostCreate settled that.)
            if (!remote || failed.any { it.op == OutboxOps.CREATE }) return Outcome.Success(Unit)
            outbox.enqueue(
                OutboxEntity(
                    accountId = accountId,
                    op = OutboxOps.DELETE,
                    entity = OutboxEntities.RECIPE_EDIT,
                    localId = id.takeIf { it < 0 },
                    serverId = id.takeIf { it >= 0 },
                    createdAtEpochMillis = now,
                ),
            )
            // A recipe never sent: the DELETE cancelled its CREATE (OutboxMerge); one whose CREATE was
            // attempted waits for that CREATE and then deletes the copy a lost answer may have left.
            return Outcome.Success(Unit)
        }

        suspend fun discard(recipeId: Long): Outcome<Unit> {
            val id = database.idRewriteDao().current(accountId, OutboxEntities.RECIPE_EDIT, recipeId)
            val rows = outbox.getAllFor(accountId, OutboxEntities.RECIPE_EDIT)
            val failed = rows.filter { it.isEditOf(id) && it.state == OutboxState.FAILED }
            if (failed.isEmpty()) return Outcome.Success(Unit)
            // The picture change that came with the edit goes with it: it waited behind the edit and
            // would otherwise be sent now, a photo or a removal the user just dropped.
            val pictures = rows.filter { it.isImageOf(id) && it.state != OutboxState.IN_FLIGHT }
            pictures.mapNotNull { RecipeEditPayloads.imagePathOf(it.payload) }.forEach { images.delete(it) }
            outbox.deleteWithDependents((failed + pictures).map { it.id })
            return Outcome.Success(Unit)
        }

        /** Writes [draft] as recipe [id]'s stored copy (the list entry, detail, steps, ingredients, keyword links, FTS). */
        private suspend fun store(id: Long, draft: RecipeDraft, stored: Recipe?, photo: File?, rawJson: String) {
            val reference = database.referenceDao()
            val foods = reference.getFoods(accountId)
                .associateBy({ RecipeEditPayloads.key(it.name) }) { Food(it.id, it.name, it.pluralName, it.onHand) }
            val units = reference.getUnits(accountId)
                .associateBy({ RecipeEditPayloads.key(it.name) }) { MeasurementUnit(it.id, it.name, it.pluralName, it.baseUnit) }
            // A keyword the device has not got yet: a stable negative id from its name (as a pending
            // pairing's, `DrinkIndex`) until the sender's answer brings the real one.
            val known = database.drinkDraftDao().getKeywords(accountId)
            val keywords = draft.keywords.map { keyword ->
                val entity = keyword.id?.let { wanted -> known.firstOrNull { it.id == wanted } }
                    ?: known.firstOrNull { RecipeEditPayloads.key(it.name) == RecipeEditPayloads.key(keyword.name) }
                entity?.toModel() ?: Keyword(id = DrinkIndex.pendingKeywordId(keyword.name), name = keyword.name)
            }.distinctBy { it.id }
            val recipe = RecipeEditPayloads.toRecipe(
                id = id,
                draft = draft,
                stored = stored,
                keywords = keywords,
                foodsByName = foods,
                unitsByName = units,
                imageUrl = photo?.let { "file://${it.path}" } ?: draft.imageUrl,
                now = Instant.fromEpochMilliseconds(now),
            )
            recipes.replaceDetail(accountId, recipe.toDetailWrite(accountId, rawJson = rawJson, fetchedAtEpochMillis = now))
        }

        /** Fas 3 (import lane): the picture of an import, on the web, for the server to download after the recipe exists. */
        private suspend fun queueImageUrl(id: Long, url: String, dependsOn: Long?) {
            outbox.enqueue(
                OutboxEntity(
                    accountId = accountId,
                    op = OutboxOps.RECIPE_IMAGE,
                    entity = OutboxEntities.RECIPE_EDIT,
                    localId = id.takeIf { it < 0 },
                    serverId = id.takeIf { it >= 0 },
                    payload = RecipeEditPayloads.imageUrl(url),
                    dependsOn = dependsOn,
                    createdAtEpochMillis = now,
                ),
            )
        }

        private suspend fun queueImageRemoval(id: Long) {
            outbox.enqueue(
                OutboxEntity(
                    accountId = accountId,
                    op = OutboxOps.RECIPE_IMAGE,
                    entity = OutboxEntities.RECIPE_EDIT,
                    serverId = id,
                    payload = RecipeEditPayloads.imageRemoval(),
                    createdAtEpochMillis = now,
                ),
            )
        }

        private suspend fun queueImage(id: Long, photo: File, dependsOn: Long?) {
            outbox.enqueue(
                OutboxEntity(
                    accountId = accountId,
                    op = OutboxOps.RECIPE_IMAGE,
                    entity = OutboxEntities.RECIPE_EDIT,
                    localId = id.takeIf { it < 0 },
                    serverId = id.takeIf { it >= 0 },
                    payload = RecipeEditPayloads.image(photo.path),
                    dependsOn = dependsOn,
                    createdAtEpochMillis = now,
                ),
            )
        }
    }

    private companion object {
        /** Demo mode: recipes created in the app get ids from here (above the demo content and the demo drinks). */
        const val DEMO_CREATED_FIRST_ID = 2_000L
    }
}
