package casa.moller.tandem.core.sync.scan

import casa.moller.tandem.core.database.TandemDatabase
import casa.moller.tandem.core.database.TandemDatabaseFactory
import casa.moller.tandem.core.database.entity.FoodAliasEntity
import casa.moller.tandem.core.database.entity.FoodAliasKinds
import casa.moller.tandem.core.database.entity.OutboxEntities
import casa.moller.tandem.core.database.entity.OutboxOps
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.scan.FoodAlias
import casa.moller.tandem.core.model.scan.FoodAliasKind
import casa.moller.tandem.core.model.sync.SyncStop
import casa.moller.tandem.core.network.api.TandoorApi
import casa.moller.tandem.core.network.dto.AutomationDto
import casa.moller.tandem.core.network.paging.Paginator
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.account.ActiveSources
import casa.moller.tandem.core.sync.account.identity
import casa.moller.tandem.core.sync.permissions.isGuest
import casa.moller.tandem.core.sync.platform.SyncClock
import casa.moller.tandem.core.sync.remote.RemoteCaller
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Fas S (hemma-data lane): learned barcodes and receipt names as Tandoor `FOOD_ALIAS` automations
 * (plan §8.8, FACTS "Fas S d"): `param_1` = the code or receipt text (at most 128 characters,
 * matched without case), `param_2` = the food's name, `name` says what it is.
 */
internal object FoodAliases {
    const val NAME_BARCODE: String = "Tandem: streckkod"
    const val NAME_RECEIPT: String = "Tandem: kvitto"

    /** An alias learned with [FoodAliasKind.OTHER] (neither a barcode nor a receipt line). */
    const val NAME_OTHER: String = "Tandem"

    /** The lookup key: trimmed, lower case, at most [FoodAlias.MAX_LENGTH] characters (as stored in `param_1`). */
    fun key(text: String): String = clip(text).lowercase(Locale.ROOT)

    /** What is sent as `param_1`/`param_2`: trimmed and cut to [FoodAlias.MAX_LENGTH] (129 is a 400). */
    fun clip(text: String): String = text.trim().take(FoodAlias.MAX_LENGTH).trim()

    fun automationName(kind: FoodAliasKind): String = when (kind) {
        FoodAliasKind.BARCODE -> NAME_BARCODE
        FoodAliasKind.RECEIPT -> NAME_RECEIPT
        FoodAliasKind.OTHER -> NAME_OTHER
    }

    /** The stored kind of an automation `name`: only Tandem's own names are barcodes or receipt lines. */
    fun kindOf(automationName: String): String = when (automationName.trim()) {
        NAME_BARCODE -> FoodAliasKinds.BARCODE
        NAME_RECEIPT -> FoodAliasKinds.RECEIPT
        else -> FoodAliasKinds.OTHER
    }

    fun kindOf(row: FoodAliasEntity): FoodAliasKind = when (row.kind) {
        FoodAliasKinds.BARCODE -> FoodAliasKind.BARCODE
        FoodAliasKinds.RECEIPT -> FoodAliasKind.RECEIPT
        else -> FoodAliasKind.OTHER
    }

    fun storedKind(kind: FoodAliasKind): String = when (kind) {
        FoodAliasKind.BARCODE -> FoodAliasKinds.BARCODE
        FoodAliasKind.RECEIPT -> FoodAliasKinds.RECEIPT
        FoodAliasKind.OTHER -> FoodAliasKinds.OTHER
    }

    fun createPayload(alias: String, foodName: String, kind: FoodAliasKind): String = buildJsonObject {
        put("type", AutomationDto.TYPE_FOOD_ALIAS)
        put("name", automationName(kind))
        put("description", "")
        put("param_1", alias)
        put("param_2", foodName)
    }.toString()

    fun foodPayload(foodName: String): String = buildJsonObject { put("param_2", foodName) }.toString()

    fun FoodAliasEntity.toModel(pending: Boolean): FoodAlias =
        FoodAlias(id = localId, alias = alias, foodName = foodName, kind = kindOf(this), foodId = foodId, pending = pending)
}

/**
 * Reads every `FOOD_ALIAS` automation of the space (`automation?type=FOOD_ALIAS`, small and
 * space-wide) into `food_alias`: with the small tables, on pull to refresh and before learning. A
 * list that was not read completely replaces nothing; an alias with a queued change keeps its local
 * state. Guests get 403 on the list, so nothing is read for a guest. [lock] is held by the sender too.
 */
@Singleton
internal class FoodAliasReader @Inject constructor(
    private val databases: TandemDatabaseFactory,
    private val caller: RemoteCaller,
    private val sources: ActiveSources,
    private val clock: SyncClock,
) {
    val lock = Mutex()

    /** Per account: the server's aliases of the last complete read, for the sender's duplicate check. */
    private val lastRead = ConcurrentHashMap<String, ServerAliases>()

    /**
     * Fas S review (fix-data): per account, when a complete read was last STORED in `food_alias` (the
     * sender's duplicate check reads without storing, so it never counts as "known here").
     */
    private val lastStored = ConcurrentHashMap<String, Long>()

    class ServerAliases(val items: MutableList<AutomationDto>, val atMillis: Long)

    suspend fun sync(remote: ActiveSource.Remote, background: Boolean): Outcome<Unit> = lock.withLock {
        val database = databases.get(remote.kind)
        if (isGuest(database, remote.accountId)) return@withLock Outcome.Success(Unit)
        when (val listed = fetch(remote, background)) {
            is Outcome.Failure -> listed

            is Outcome.Success -> {
                if (sources.source.value.identity != remote.identity) return@withLock Outcome.Failure(SyncStop.SourceChanged)
                val aliases = listed.value ?: return@withLock Outcome.Success(Unit)
                store(database, remote.accountId, aliases)
                lastStored[remote.accountId] = clock.nowMillis()
                Outcome.Success(Unit)
            }
        }
    }

    /**
     * The server's aliases from a complete read not older than [MAX_AGE_MILLIS] (read now otherwise):
     * the sender checks for the same `param_1` before it creates one (no duplicates, also from
     * another phone, and a retry after a lost answer finds its own). Called under [lock].
     */
    suspend fun serverAliasesLocked(remote: ActiveSource.Remote): Outcome<ServerAliases?> {
        lastRead[remote.accountId]?.takeIf { clock.nowMillis() - it.atMillis in 0..MAX_AGE_MILLIS }?.let { return Outcome.Success(it) }
        return when (val listed = fetch(remote, background = true)) {
            is Outcome.Failure -> listed
            is Outcome.Success -> Outcome.Success(listed.value?.let { lastRead[remote.accountId] })
        }
    }

    /**
     * A full read for the scanner when the last STORED one is older than [SCANNER_MAX_AGE_MILLIS]
     * (Fas S review, fix-data: a code another phone learned a moment ago is known here before Open
     * Food Facts is asked, also right after a sync or a sent alias; the list is small and an unknown
     * code costs an Open Food Facts request anyway).
     */
    suspend fun syncIfStale(remote: ActiveSource.Remote): Outcome<Unit> {
        val last = lastStored[remote.accountId]
        if (last != null && clock.nowMillis() - last in 0..SCANNER_MAX_AGE_MILLIS) return Outcome.Success(Unit)
        return sync(remote, background = false)
    }

    /** An alias this device just created or changed, so the next check in the same drain knows it. */
    fun noteServerAlias(accountId: String, alias: AutomationDto) {
        val known = lastRead[accountId] ?: return
        synchronized(known) {
            known.items.removeAll { it.id == alias.id }
            known.items += alias
        }
    }

    fun invalidate(accountId: String) {
        lastRead.remove(accountId)
        lastStored.remove(accountId)
    }

    /** Every FOOD_ALIAS of the space, null when the list was not read completely. */
    private suspend fun fetch(remote: ActiveSource.Remote, background: Boolean): Outcome<List<AutomationDto>?> {
        val listed = caller.call(remote, background) {
            val api: TandoorApi = this
            Paginator.fetchAll(maxPages = MAX_PAGES) { page ->
                api.automations.automations(AutomationDto.TYPE_FOOD_ALIAS, page.takeIf { it > 1 })
            }
        }
        return when (listed) {
            is Outcome.Failure -> listed

            is Outcome.Success -> {
                if (!listed.value.complete) return Outcome.Success(null)
                val items = listed.value.items.filter { it.type == AutomationDto.TYPE_FOOD_ALIAS && it.id != null }
                lastRead[remote.accountId] = ServerAliases(items.toMutableList(), clock.nowMillis())
                Outcome.Success(items)
            }
        }
    }

    private suspend fun store(database: TandemDatabase, accountId: String, aliases: List<AutomationDto>) {
        val dao = database.foodAliasDao()
        val now = clock.nowMillis()
        val foods = database.referenceDao().getFoods(accountId).associateBy { it.name.lowercase(Locale.ROOT) }
        database.pantryDao().inTransaction {
            val old = dao.getAll(accountId).associateBy { it.localId }
            val queued = database.outboxDao().getAllFor(accountId, OutboxEntities.FOOD_ALIAS)
            val deleting = queued.filter { it.op == OutboxOps.DELETE }.mapNotNullTo(HashSet()) { it.serverId }
            val patching = queued.filter { it.op == OutboxOps.PATCH }.mapNotNullTo(HashSet()) { it.serverId }
            val rows = aliases.mapNotNull { it.toEntity(accountId, now, foods.mapValues { (_, food) -> food.id }) }
                .distinctBy { it.localId }
                .filterNot { it.localId in deleting }
                .map { row -> if (row.localId in patching) old[row.localId] ?: row else row }
            dao.replaceServerRows(accountId, rows)
        }
    }

    companion object {
        const val MAX_PAGES: Int = 50

        /** How long a read is reused by the sender's duplicate check. */
        const val MAX_AGE_MILLIS: Long = 60_000

        /** How long a stored read is reused by the scanner before an unknown code is looked up elsewhere. */
        const val SCANNER_MAX_AGE_MILLIS: Long = 5_000

        /** A server alias as a row (`local_id = server_id`); the food's id when a food of that name is stored. */
        fun AutomationDto.toEntity(accountId: String, now: Long, foodIds: Map<String, Long>): FoodAliasEntity? {
            val serverId = id ?: return null
            val alias = param1?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val food = param2?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return FoodAliasEntity(
                accountId = accountId,
                localId = serverId,
                serverId = serverId,
                alias = alias,
                aliasKey = FoodAliases.key(alias),
                foodName = food,
                foodId = foodIds[food.lowercase(Locale.ROOT)],
                kind = FoodAliases.kindOf(name),
                automationName = name,
                disabled = disabled,
                fetchedAtEpochMillis = now,
            )
        }
    }
}
