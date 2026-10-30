package casa.moller.tandem.core.sync.pantry

import casa.moller.tandem.core.data.DataProblem
import casa.moller.tandem.core.data.pantry.PantryRepository
import casa.moller.tandem.core.data.permissions.SpacePermissionsRepository
import casa.moller.tandem.core.database.TandemDatabase
import casa.moller.tandem.core.database.TandemDatabaseFactory
import casa.moller.tandem.core.database.entity.InventoryEntryEntity
import casa.moller.tandem.core.database.entity.InventoryLocationEntity
import casa.moller.tandem.core.database.entity.OutboxEntities
import casa.moller.tandem.core.database.entity.OutboxEntity
import casa.moller.tandem.core.database.entity.OutboxOps
import casa.moller.tandem.core.database.entity.OutboxState
import casa.moller.tandem.core.database.entity.ShoppingEntryEntity
import casa.moller.tandem.core.database.entity.SyncStateEntity
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.error.catchingUnexpected
import casa.moller.tandem.core.model.pantry.InventoryEntry
import casa.moller.tandem.core.model.pantry.InventoryEntryDraft
import casa.moller.tandem.core.model.pantry.InventoryEntryUpdate
import casa.moller.tandem.core.model.pantry.InventoryLocation
import casa.moller.tandem.core.model.pantry.PantryFood
import casa.moller.tandem.core.model.pantry.PantryGroup
import casa.moller.tandem.core.model.pantry.PantryUnit
import casa.moller.tandem.core.model.pantry.UnpackChoice
import casa.moller.tandem.core.model.pantry.UnpackItem
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.account.ActiveSources
import casa.moller.tandem.core.sync.engine.DrainResult
import casa.moller.tandem.core.sync.engine.OutboxSender
import casa.moller.tandem.core.sync.permissions.isGuest
import casa.moller.tandem.core.sync.platform.NetworkStatus
import casa.moller.tandem.core.sync.platform.SyncClock
import casa.moller.tandem.core.sync.platform.SyncScheduler
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlinx.datetime.toKotlinLocalDate

/**
 * [PantryRepository] on Room and the outbox (plan §8.8, 3.5, 5.7; features #26, #28, #46). How the
 * contract is met:
 *
 * - **Household:** Tandoor lists the whole space's locations (FACTS "Fas S b"); only locations of
 *   the account's household (`server_account.household_id`) are shown, with their entries, plus
 *   locations created on this device. A real account whose household is unknown (not read yet, or
 *   a member in no household) sees only those (Fas S review, fix-data: never another household's
 *   fridge), and every change of a location or entry checks the same rule first. Demo mode: all.
 * - **Offline first:** every change is written to `inventory_location` / `inventory_entry` and
 *   queued with `OutboxDao.enqueue` in ONE transaction, then the outbox is scheduled. An object
 *   created offline gets a negative id (`IdRewriteDao.nextLocalId`); an entry in a location that is
 *   not on the server yet names it in `refs` and `depends_on` its CREATE. A change of an object
 *   whose CREATE waits unsent merges into it (`OutboxMerge`): created and marked "Slut" offline is
 *   ONE create with amount 0; created and deleted offline is nothing at all.
 * - **Default locations:** the first time the household's pantry is read empty ([refresh]),
 *   Kyl, Frys (`is_freezer`) and Skafferi are created in the device's language, once per household
 *   (`sync_state` [DEFAULTS_KEY]).
 * - **Packa upp:** today's shopping entries checked ON THIS DEVICE (`sync_state` [CHECKED_HERE_KEY],
 *   still checked with `completed_at` on the device's calendar day; Fas S review, fix-data: a
 *   partner's purchases are never offered on both phones) not yet unpacked or skipped today
 *   (`sync_state` [UNPACKED_KEY]). Both sets follow a list entry's temporary id to its server id.
 * - **Guests** get `DataProblem.READ_ONLY` before anything is stored (#42); **demo mode** writes
 *   the demo database and queues nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
internal class OfflineFirstPantryRepository @Inject constructor(
    private val sources: ActiveSources,
    private val databases: TandemDatabaseFactory,
    private val permissions: SpacePermissionsRepository,
    private val scheduler: SyncScheduler,
    private val clock: SyncClock,
    private val reader: PantryReader,
    private val outbox: OutboxSender,
    private val names: PantryNames,
    private val changes: PantryChanges,
    private val network: NetworkStatus,
) : PantryRepository {
    /** Serializes local writes, so two taps never get the same temporary id. */
    private val writes = Mutex()

    // ------------------------------------------------------------------------------------ reads

    override fun observeLocations(): Flow<List<InventoryLocation>> = stored(emptyList()) { source, database ->
        combine(visibleLocations(source, database), database.outboxDao().observeAll(source.accountId)) { locations, queue ->
            val pending = PendingObjects(queue, OutboxEntities.INVENTORY_LOCATION)
            locations.map { it.toModel(pending.has(it.localId, it.serverId)) }
        }
    }

    override fun observePantry(): Flow<List<PantryGroup>> = stored(emptyList()) { source, database ->
        combine(
            visibleLocations(source, database),
            database.pantryDao().observeEntries(source.accountId),
            database.outboxDao().observeAll(source.accountId),
        ) { locations, entries, queue ->
            val pendingLocations = PendingObjects(queue, OutboxEntities.INVENTORY_LOCATION)
            val pendingEntries = PendingObjects(queue, OutboxEntities.INVENTORY_ENTRY)
            val byLocation = entries.groupBy { it.locationId }
            locations.map { location ->
                PantryGroup(
                    location = location.toModel(pendingLocations.has(location.localId, location.serverId)),
                    entries = byLocation[location.localId].orEmpty().map { it.toModel(pendingEntries.has(it.localId, it.serverId)) },
                )
            }
        }
    }

    override fun observeEntry(id: Long): Flow<InventoryEntry?> = sources.source
        .flatMapLatest { source ->
            if (source !is ActiveSource.Stored) return@flatMapLatest flowOf<InventoryEntry?>(null)
            val database = databases.get(source.kind)
            val accountId = source.accountId
            val key = database.idRewriteDao().current(accountId, OutboxEntities.INVENTORY_ENTRY, id)
            combine(entryRow(database, accountId, key), database.outboxDao().observeAll(accountId)) { row, queue ->
                row?.toModel(PendingObjects(queue, OutboxEntities.INVENTORY_ENTRY).has(row.localId, row.serverId))
            }
        }
        .catch { emit(null) }
        .distinctUntilChanged()

    /** The row of [key]; a temporary key follows its server id once the CREATE was answered. */
    private fun entryRow(database: TandemDatabase, accountId: String, key: Long): Flow<InventoryEntryEntity?> =
        database.pantryDao().observeEntry(accountId, key).transformLatest { row ->
            val mapped = if (row == null && key < 0) {
                database.idRewriteDao().resolve(accountId, OutboxEntities.INVENTORY_ENTRY, key)
            } else {
                null
            }
            if (mapped != null) emitAll(database.pantryDao().observeEntry(accountId, mapped)) else emit(row)
        }

    override fun observeExpiring(today: LocalDate, days: Int): Flow<List<InventoryEntry>> = stored(emptyList()) { source, database ->
        val lastDay = today.plus(DatePeriod(days = days)).toString()
        combine(visibleLocations(source, database), database.pantryDao().observeExpiring(source.accountId, lastDay)) { locations, entries ->
            val visible = locations.mapTo(HashSet()) { it.localId }
            entries.filter { it.locationId in visible }.map { it.toModel(pending = false) }
        }
    }

    override fun observeAvailableFoodIds(): Flow<Set<Long>> = sources.source
        .flatMapLatest { source ->
            if (source !is ActiveSource.Stored) return@flatMapLatest flowOf(emptySet<Long>())
            val database = databases.get(source.kind)
            combine(
                visibleLocations(source, database),
                database.pantryDao().observeStockedEntries(source.accountId),
                database.supermarketDao().observeFoods(source.accountId),
            ) { locations, entries, foods ->
                val visible = locations.mapTo(HashSet()) { it.localId }
                entries.filter { it.locationId in visible }.mapNotNullTo(HashSet()) { it.foodId } +
                    foods.filter { it.onHand }.map { it.id }
            }
        }
        .catch { emit(emptySet()) }
        .distinctUntilChanged()

    // hemma-ui lane: Hemma's "Har hemma" list – foods flagged on hand (`food_onhand`, household)
    // without a stocked entry in one of the household's locations.
    override fun observeOnHandFoods(): Flow<List<PantryFood>> = stored(emptyList()) { source, database ->
        combine(
            visibleLocations(source, database),
            database.pantryDao().observeStockedEntries(source.accountId),
            database.supermarketDao().observeFoods(source.accountId),
        ) { locations, entries, foods ->
            val visible = locations.mapTo(HashSet()) { it.localId }
            val stocked = entries.filter { it.locationId in visible }.mapNotNullTo(HashSet()) { it.foodId }
            foods.filter { it.onHand && it.id !in stocked }
                .map { PantryFood(id = it.id, name = it.name, pluralName = it.pluralName) }
        }
    }

    override fun observeUnpackable(today: LocalDate): Flow<List<UnpackItem>> = stored(emptyList()) { source, database ->
        combine(
            database.shoppingDao().observeEntries(source.accountId),
            database.syncStateDao().observeOne(source.accountId, UNPACKED_KEY),
            database.syncStateDao().observeOne(source.accountId, CHECKED_HERE_KEY),
        ) { entries, done, here ->
            // An entry checked or unpacked by its temporary id has its server id since (the shopping
            // CREATE was answered meanwhile): the mapping is followed, so it is never offered again.
            val unpacked = withServerIds(database, source.accountId, UnpackedToday.decode(done?.hash, today))
            val checkedHere = withServerIds(database, source.accountId, UnpackedToday.decode(here?.hash, today))
            entries.filter {
                it.checked && completedOn(it) == today && it.localId in checkedHere && it.localId !in unpacked && it.foodName.isNotBlank()
            }
                .sortedBy { it.completedAt }
                .map { it.toUnpackItem() }
        }
    }

    /** [ids] plus the server id of every temporary one whose CREATE was answered. */
    private suspend fun withServerIds(database: TandemDatabase, accountId: String, ids: Set<Long>): Set<Long> {
        if (ids.none { it < 0 }) return ids
        val rewrite = database.idRewriteDao()
        return ids + ids.filter { it < 0 }.mapNotNull { rewrite.resolve(accountId, OutboxEntities.SHOPPING_ENTRY, it) }
    }

    // ------------------------------------------------------------------------------------ writes

    override suspend fun createLocation(name: String, isFreezer: Boolean): Outcome<InventoryLocation> = write { context ->
        val created = context.createLocation(name.trim(), isFreezer) ?: return@write householdUnknown()
        Outcome.Success(created.toModel(pending = context.remote))
    }

    override suspend fun renameLocation(id: Long, name: String, isFreezer: Boolean): Outcome<Unit> = write { context ->
        val location = context.location(id) ?: return@write Outcome.Failure(TandemError.NotFound)
        val trimmed = name.trim().ifEmpty { location.name }
        context.database.pantryDao().upsertLocations(listOf(location.copy(name = trimmed, isFreezer = isFreezer)))
        val payload = PantryPayloads.location(trimmed, isFreezer)
        context.queue(OutboxEntities.INVENTORY_LOCATION, location.localId, location.serverId, OutboxOps.PATCH, payload)
        Outcome.Success(Unit)
    }

    override suspend fun deleteLocation(id: Long): Outcome<Unit> = write { context ->
        val location = context.location(id) ?: return@write Outcome.Success(Unit)
        val dao = context.database.pantryDao()
        for (entry in dao.getEntriesOfLocation(context.accountId, location.localId)) {
            val serverId = entry.serverId
            if (serverId == null) {
                // Never sent: its DELETE cancels the queued CREATE.
                context.queue(OutboxEntities.INVENTORY_ENTRY, entry.localId, null, OutboxOps.DELETE, "{}")
            } else if (context.remote) {
                // The server deletes the location's entries with it (CASCADE): their queued changes go now.
                context.database.outboxDao().deleteUnsentFor(context.accountId, OutboxEntities.INVENTORY_ENTRY, serverId)
            }
        }
        dao.deleteEntriesOfLocation(context.accountId, location.localId)
        dao.deleteLocation(context.accountId, location.localId)
        context.queue(OutboxEntities.INVENTORY_LOCATION, location.localId, location.serverId, OutboxOps.DELETE, "{}")
        Outcome.Success(Unit)
    }

    override suspend fun reorderLocations(idsInOrder: List<Long>): Outcome<Unit> = write { context ->
        val keys = idsInOrder.map { context.current(OutboxEntities.INVENTORY_LOCATION, it) }.distinct()
        // The order lives on this device only (Tandoor has none): no outbox row.
        keys.forEachIndexed { index, key -> context.database.pantryDao().setLocationOrder(context.accountId, key, index + 1) }
        Outcome.Success(Unit)
    }

    override suspend fun addEntry(draft: InventoryEntryDraft): Outcome<InventoryEntry> = write { context ->
        val created = context.createEntry(draft) ?: return@write Outcome.Failure(TandemError.NotFound)
        Outcome.Success(created.toModel(pending = context.remote))
    }

    override suspend fun updateEntry(id: Long, update: InventoryEntryUpdate): Outcome<Unit> = write { context ->
        val entry = context.entry(id) ?: return@write Outcome.Failure(TandemError.NotFound)
        val locationId = update.locationId?.let { context.current(OutboxEntities.INVENTORY_LOCATION, it) }
        if (locationId != null && context.location(locationId) == null) return@write Outcome.Failure(TandemError.NotFound)
        val moved = locationId?.takeIf { it != entry.locationId }
        val amount = update.amount?.coerceAtLeast(0.0)
        val changed = entry.copy(
            amount = amount ?: entry.amount,
            locationId = moved ?: entry.locationId,
            expires = when {
                update.clearExpires -> null
                update.expires != null -> update.expires.toString()
                else -> entry.expires
            },
            note = update.note?.trim()?.ifEmpty { null } ?: if (update.note != null) null else entry.note,
            subLocation = update.subLocation?.trim()?.ifEmpty { null } ?: if (update.subLocation != null) null else entry.subLocation,
        )
        context.database.pantryDao().upsertEntries(listOf(changed))
        val payload = PantryPayloads.patchEntry(
            amount = amount,
            locationId = moved,
            expires = update.expires,
            clearExpires = update.clearExpires,
            note = update.note?.trim(),
            subLocation = update.subLocation?.trim(),
        )
        context.queueEntry(entry, OutboxOps.PATCH, payload, locationRef = moved)
        Outcome.Success(Unit)
    }

    override suspend fun markEmpty(id: Long): Outcome<Unit> = setAmount(id, relative = false) { 0.0 }

    /**
     * "-1" is queued as a CHANGE (Fas S review, fix-data): the sender applies it to the server's
     * amount at sending time, so two phones that each take one from 3 leave 1, and a partner's edit
     * meanwhile is kept. After an amount the user set that still waits, it stays a value.
     */
    override suspend fun decrement(id: Long): Outcome<Unit> = setAmount(id, relative = true) { amount -> (amount - 1.0).coerceAtLeast(0.0) }

    private suspend fun setAmount(id: Long, relative: Boolean, next: (Double) -> Double): Outcome<Unit> = write { context ->
        val entry = context.entry(id) ?: return@write Outcome.Failure(TandemError.NotFound)
        val amount = next(entry.amount)
        context.database.pantryDao().upsertEntries(listOf(entry.copy(amount = amount)))
        if (relative && !context.hasQueuedValue(entry)) {
            val payload = PantryPayloads.relativeAmount(amount)
            context.queueEntry(entry, OutboxOps.PATCH, payload, base = PantryPayloads.amountBase(entry.amount))
        } else {
            context.queueEntry(entry, OutboxOps.PATCH, PantryPayloads.amount(amount))
        }
        Outcome.Success(Unit)
    }

    override suspend fun deleteEntry(id: Long): Outcome<Unit> = write { context ->
        val entry = context.entry(id) ?: return@write Outcome.Success(Unit)
        context.database.pantryDao().deleteEntry(context.accountId, entry.localId)
        context.queueEntry(entry, OutboxOps.DELETE, "{}")
        Outcome.Success(Unit)
    }

    override suspend fun unpack(choices: List<UnpackChoice>): Outcome<Int> = write { context ->
        var created = 0
        for (choice in choices.filter { it.include }) {
            val item = choice.item
            val draft = InventoryEntryDraft(
                locationId = choice.locationId,
                foodName = item.food.name,
                foodId = item.food.id,
                amount = item.amount.takeIf { it > 0 } ?: 1.0,
                unitName = item.unit?.name,
                expires = choice.expires,
            )
            if (context.createEntry(draft) != null) created++
        }
        context.markDone(UNPACKED_KEY, choices.map { it.item.shoppingEntryId })
        Outcome.Success(created)
    }

    /**
     * Reads locations and entries again: the outbox first (so the read sees this device's changes),
     * then the full read, then the default locations if the household has none yet (first use).
     */
    override suspend fun refresh(): Outcome<Unit> = catchingUnexpected {
        when (val source = sources.current()) {
            is ActiveSource.Remote -> {
                if (!network.online.value) return@catchingUnexpected Outcome.Failure(TandemError.NetworkUnavailable)
                if (outbox.drain(source) == DrainResult.RETRY) scheduler.scheduleOutbox()
                val read = reader.sync(source, background = false)
                if (read is Outcome.Success) ensureDefaultLocations(source)
                read
            }

            ActiveSource.Demo -> Outcome.Success(Unit)

            ActiveSource.Loading, ActiveSource.None -> Outcome.Failure(DataProblem.NO_ACCOUNT.toError())
        }
    }

    /**
     * Kyl, Frys and Skafferi for a household whose pantry is empty after a complete read, once per
     * household on this device (deleting them all later does not bring them back). Not for guests
     * (they cannot write) or while the household is unknown.
     */
    suspend fun ensureDefaultLocations(remote: ActiveSource.Remote): Outcome<Unit> = catchingUnexpected {
        val database = databases.get(remote.kind)
        val accountId = remote.accountId
        if (isGuest(database, accountId) || permissions.permissions.first().readOnly) return@catchingUnexpected Outcome.Success(Unit)
        val household = database.accountDao().get(accountId)?.householdId ?: return@catchingUnexpected Outcome.Success(Unit)
        val marker = database.syncStateDao().get(accountId, DEFAULTS_KEY)
        if (marker?.hash == household.toString()) return@catchingUnexpected Outcome.Success(Unit)
        val own = database.pantryDao().getLocations(accountId).filter { it.householdId == household || it.localId < 0 }
        val created = if (own.isEmpty()) {
            write { context ->
                // Marked as defaults: their sender takes a same-named location another phone created first.
                context.createLocation(names.fridge, isFreezer = false, isDefault = true)
                context.createLocation(names.freezer, isFreezer = true, isDefault = true)
                context.createLocation(names.pantry, isFreezer = false, isDefault = true)
                Outcome.Success(Unit)
            }
        } else {
            Outcome.Success(Unit)
        }
        if (created is Outcome.Success) {
            database.syncStateDao().upsert(SyncStateEntity(accountId = accountId, entity = DEFAULTS_KEY, hash = household.toString()))
        }
        created
    }

    // ------------------------------------------------------------------------------------ helpers

    private fun householdUnknown(): Outcome<Nothing> = Outcome.Failure(TandemError.Unknown(HOUSEHOLD_UNKNOWN))

    /** The account's household id as it changes; null = unknown (not read yet, or in no household). */
    private fun household(source: ActiveSource.Stored, database: TandemDatabase): Flow<Long?> = if (source is ActiveSource.Remote) {
        database.accountDao().observe(source.accountId).map { it?.householdId }.distinctUntilChanged()
    } else {
        flowOf(null)
    }

    private fun visibleLocations(source: ActiveSource.Stored, database: TandemDatabase): Flow<List<InventoryLocationEntity>> =
        combine(household(source, database), database.pantryDao().observeLocations(source.accountId)) { household, locations ->
            locations.filter { isVisible(it, household, showAll = source !is ActiveSource.Remote) }
        }

    private fun <T> stored(empty: T, flow: (ActiveSource.Stored, TandemDatabase) -> Flow<T>): Flow<T> = sources.source
        .flatMapLatest { source -> if (source is ActiveSource.Stored) flow(source, databases.get(source.kind)) else flowOf(empty) }
        .catch { emit(empty) }
        .distinctUntilChanged()

    private suspend fun readOnly(): Boolean = permissions.permissions.first().readOnly

    /** Runs a local change for the active source in one transaction; guests are refused first. */
    private suspend fun <T> write(block: suspend (WriteContext) -> Outcome<T>): Outcome<T> = catchingUnexpected {
        when (val source = sources.current()) {
            is ActiveSource.Stored -> {
                if (source is ActiveSource.Remote && readOnly()) return@catchingUnexpected Outcome.Failure(DataProblem.READ_ONLY.toError())
                val database = databases.get(source.kind)
                var result: Outcome<T> = Outcome.Failure(TandemError.Unknown(HOUSEHOLD_UNKNOWN))
                writes.withLock {
                    database.pantryDao().inTransaction { result = block(WriteContext(source, database, clock.nowMillis())) }
                }
                if (result is Outcome.Success) {
                    if (source is ActiveSource.Remote) scheduler.scheduleOutbox()
                    changes.notify(source.accountId)
                }
                result
            }

            ActiveSource.Loading, ActiveSource.None -> Outcome.Failure(DataProblem.NO_ACCOUNT.toError())
        }
    }

    /** One local change: the source, its database and "now"; queues only for a real account. */
    private inner class WriteContext(val source: ActiveSource.Stored, val database: TandemDatabase, val now: Long) {
        val accountId: String = source.accountId
        val remote: Boolean = source is ActiveSource.Remote

        suspend fun current(entity: String, id: Long): Long = database.idRewriteDao().current(accountId, entity, id)

        private var household: Long? = null
        private var householdRead = false

        /** Fas S review (fix-data): whether this account may see and change [location] (its household's, or one of this device's). */
        private suspend fun visible(location: InventoryLocationEntity): Boolean {
            if (!remote) return true
            if (!householdRead) {
                household = database.accountDao().get(accountId)?.householdId
                householdRead = true
            }
            return isVisible(location, household, showAll = false)
        }

        /** A location the account may see; null otherwise (never another household's). */
        suspend fun location(id: Long): InventoryLocationEntity? =
            database.pantryDao().getLocation(accountId, current(OutboxEntities.INVENTORY_LOCATION, id))?.takeIf { visible(it) }

        /** An entry in a location the account may see; null otherwise. */
        suspend fun entry(id: Long): InventoryEntryEntity? {
            val entry = database.pantryDao().getEntry(accountId, current(OutboxEntities.INVENTORY_ENTRY, id)) ?: return null
            val location = database.pantryDao().getLocation(accountId, entry.locationId)
            return entry.takeIf { location != null && visible(location) || location == null && !remote }
        }

        /** Whether an amount the user SET ("Slut", the editor, Undo) still waits for [entry]: "-1" then stays a value. */
        suspend fun hasQueuedValue(entry: InventoryEntryEntity): Boolean {
            if (!remote) return false
            val rows = database.outboxDao().getUnsentFor(
                accountId,
                OutboxEntities.INVENTORY_ENTRY,
                entry.localId.takeIf { entry.serverId == null },
                entry.serverId,
            )
            return rows.any { row ->
                val body = PantryPayloads.parse(row.payload) ?: return@any false
                val hasAmount = PantryPayloads.number(body, PantryPayloads.FIELD_AMOUNT) != null
                hasAmount && (row.op == OutboxOps.CREATE || !PantryPayloads.isRelative(body))
            }
        }

        /** A new location of the account's household; null when the household is unknown (a real account only). */
        suspend fun createLocation(name: String, isFreezer: Boolean, isDefault: Boolean = false): InventoryLocationEntity? {
            val account = if (remote) database.accountDao().get(accountId) else null
            if (remote && (account?.householdId == null || account.householdName == null)) return null
            val dao = database.pantryDao()
            val order = (dao.getLocations(accountId).maxOfOrNull { it.locationOrder } ?: 0) + 1
            val row = InventoryLocationEntity(
                accountId = accountId,
                localId = database.idRewriteDao().nextLocalId(accountId, OutboxEntities.INVENTORY_LOCATION),
                serverId = null,
                name = name,
                isFreezer = isFreezer,
                householdId = account?.householdId,
                householdName = account?.householdName,
                locationOrder = order,
            )
            dao.upsertLocations(listOf(row))
            if (remote) {
                database.outboxDao().enqueue(
                    OutboxEntity(
                        accountId = accountId,
                        op = OutboxOps.CREATE,
                        entity = OutboxEntities.INVENTORY_LOCATION,
                        localId = row.localId,
                        payload = PantryPayloads.location(name, isFreezer, isDefault),
                        createdAtEpochMillis = now,
                    ),
                )
            }
            return row
        }

        /** A new entry from [draft]; null when its location is not stored. */
        suspend fun createEntry(draft: InventoryEntryDraft): InventoryEntryEntity? {
            val location = location(draft.locationId) ?: return null
            val foodName = draft.foodName.trim()
            if (foodName.isEmpty()) return null
            val foods = database.referenceDao().getFoods(accountId)
            val food = draft.foodId?.let { id -> foods.firstOrNull { it.id == id } }
                ?: foods.firstOrNull { it.name.equals(foodName, ignoreCase = true) }
            val unitName = draft.unitName?.trim()?.takeIf { it.isNotEmpty() } ?: names.defaultUnit
            val row = InventoryEntryEntity(
                accountId = accountId,
                localId = database.idRewriteDao().nextLocalId(accountId, OutboxEntities.INVENTORY_ENTRY),
                serverId = null,
                locationId = location.localId,
                subLocation = draft.subLocation?.trim()?.takeIf { it.isNotEmpty() },
                foodId = draft.foodId ?: food?.id,
                foodName = foodName,
                foodPluralName = food?.pluralName,
                unitName = unitName,
                amount = draft.amount.coerceAtLeast(0.0),
                expires = draft.expires?.toString(),
                note = draft.note?.trim()?.takeIf { it.isNotEmpty() },
                createdBy = (source as? ActiveSource.Remote)?.account?.userId,
                createdAtEpochMillis = now,
            )
            database.pantryDao().upsertEntries(listOf(row))
            if (remote) {
                val locationCreate = createRowOf(OutboxEntities.INVENTORY_LOCATION, location.localId)
                val waitForLocation = if (location.serverId == null) locationCreate?.id else null
                database.outboxDao().enqueue(
                    OutboxEntity(
                        accountId = accountId,
                        op = OutboxOps.CREATE,
                        entity = OutboxEntities.INVENTORY_ENTRY,
                        localId = row.localId,
                        payload = PantryPayloads.createEntry(
                            locationId = location.serverId ?: location.localId,
                            foodName = foodName,
                            foodId = row.foodId,
                            unitName = unitName,
                            amount = row.amount,
                            expires = draft.expires,
                            note = row.note,
                            subLocation = row.subLocation,
                        ),
                        refs = PantryPayloads.locationRefs(location.serverId ?: location.localId),
                        dependsOn = waitForLocation,
                        createdAtEpochMillis = now,
                    ),
                )
            }
            return row
        }

        /** Queues [op] for [entry]; a move to a location not yet on the server names it in `refs` and waits for its CREATE. */
        suspend fun queueEntry(entry: InventoryEntryEntity, op: String, payload: String, locationRef: Long? = null, base: String? = null) {
            val waitForLocation = locationRef?.takeIf { it < 0 }?.let { createRowOf(OutboxEntities.INVENTORY_LOCATION, it)?.id }
            queue(
                entity = OutboxEntities.INVENTORY_ENTRY,
                localId = entry.localId,
                serverId = entry.serverId,
                op = op,
                payload = payload,
                refs = PantryPayloads.locationRefs(locationRef),
                waitFor = waitForLocation,
                base = base,
            )
        }

        /**
         * Queues [op] for an object: by `server_id` when it has one, else by `local_id` (`depends_on`
         * its CREATE while that is being sent; an unsent one takes the change itself).
         */
        @Suppress("LongParameterList") // The outbox row's own columns.
        suspend fun queue(
            entity: String,
            localId: Long,
            serverId: Long?,
            op: String,
            payload: String,
            refs: String? = null,
            waitFor: Long? = null,
            base: String? = null,
        ) {
            if (!remote) return
            // Created offline and its CREATE is gone (cancelled): nothing can ever be sent for it.
            if (serverId == null && createRowOf(entity, localId) == null) return
            database.outboxDao().enqueue(
                OutboxEntity(
                    accountId = accountId,
                    op = op,
                    entity = entity,
                    localId = if (serverId == null) localId else null,
                    serverId = serverId,
                    payload = payload,
                    refs = refs,
                    baseSnapshot = base,
                    dependsOn = waitFor ?: if (serverId == null) sendingCreateOf(entity, localId) else null,
                    createdAtEpochMillis = now,
                ),
            )
        }

        /** Remembers [shoppingEntryIds] as unpacked (or skipped) today, by their server id where they have one. */
        suspend fun markDone(key: String, shoppingEntryIds: List<Long>) {
            val ids = shoppingEntryIds.map { current(OutboxEntities.SHOPPING_ENTRY, it) }
            UnpackedToday.add(database, accountId, key, clock.today(), ids)
        }

        private suspend fun createRowOf(entity: String, localId: Long): OutboxEntity? =
            database.outboxDao().getAllFor(accountId, entity).firstOrNull { it.op == OutboxOps.CREATE && it.localId == localId }

        /** The CREATE a change must wait for: one being sent, failed or attempted (an unsent, never tried one takes the change). */
        private suspend fun sendingCreateOf(entity: String, localId: Long): Long? =
            createRowOf(entity, localId)?.takeUnless { it.state in UNSENT && it.attempts == 0 }?.id
    }

    companion object {
        /** `sync_state`: the household (`hash`) whose default locations were created on this device. */
        const val DEFAULTS_KEY: String = "pantry_default_locations"

        /** `sync_state`: today's unpacked or skipped shopping entries (`hash` = `yyyy-MM-dd|id,id`). */
        const val UNPACKED_KEY: String = "pantry_unpacked"

        /** `sync_state` (Fas S review, fix-data): today's shopping entries checked on THIS device (same format). */
        const val CHECKED_HERE_KEY: String = "pantry_checked_here"

        /** A location cannot be created while the account's household is unknown (Tandoor requires one). */
        const val HOUSEHOLD_UNKNOWN: String = "tandem.pantry.household-unknown"

        private val UNSENT: Set<OutboxState> = setOf(OutboxState.PENDING, OutboxState.PAUSED)

        /**
         * A location of [household], or one created on this device or without a household; every
         * location only with [showAll] (demo mode). An unknown [household] shows no server location.
         */
        fun isVisible(location: InventoryLocationEntity, household: Long?, showAll: Boolean): Boolean =
            showAll || location.localId < 0 || location.householdId == null || (household != null && location.householdId == household)

        /** The device's calendar day an entry was checked on, null when it is not checked. */
        fun completedOn(entry: ShoppingEntryEntity): LocalDate? =
            entry.completedAt?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate().toKotlinLocalDate() }
    }
}

/** Which objects of one entity have rows in the outbox (shown as "pending"). */
private class PendingObjects(queue: List<OutboxEntity>, entity: String) {
    private val rows = queue.filter { it.entity == entity }
    private val local = rows.mapNotNullTo(HashSet()) { it.localId }
    private val server = rows.mapNotNullTo(HashSet()) { it.serverId }

    fun has(localId: Long, serverId: Long?): Boolean = localId in local || (serverId != null && serverId in server)
}

/** `sync_state.hash` of today's unpacked shopping entries: `yyyy-MM-dd|1,2,3`; another day = none. */
internal object UnpackedToday {
    fun decode(hash: String?, today: LocalDate): Set<Long> {
        val text = hash ?: return emptySet()
        val day = text.substringBefore('|')
        if (day != today.toString()) return emptySet()
        return text.substringAfter('|', "").split(',').mapNotNullTo(HashSet()) { it.trim().toLongOrNull() }
    }

    fun encode(today: LocalDate, ids: Collection<Long>): String = today.toString() + "|" + ids.distinct().sorted().joinToString(",")

    /** Adds [ids] to today's set under [key] (another day's set is replaced). Inside the caller's transaction. */
    suspend fun add(database: TandemDatabase, accountId: String, key: String, today: LocalDate, ids: Collection<Long>) {
        if (ids.isEmpty()) return
        val states = database.syncStateDao()
        val all = decode(states.get(accountId, key)?.hash, today) + ids
        states.upsert(SyncStateEntity(accountId = accountId, entity = key, hash = encode(today, all)))
    }
}

private fun ShoppingEntryEntity.toUnpackItem(): UnpackItem = UnpackItem(
    shoppingEntryId = localId,
    food = PantryFood(id = foodId, name = foodName),
    amount = amount.takeIf { it > 0 } ?: 1.0,
    unit = unitName?.let { PantryUnit(id = unitId, name = it) },
)
