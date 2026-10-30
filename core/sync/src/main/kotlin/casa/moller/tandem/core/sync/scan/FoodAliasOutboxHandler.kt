package casa.moller.tandem.core.sync.scan

import casa.moller.tandem.core.database.TandemDatabaseFactory
import casa.moller.tandem.core.database.entity.FoodAliasKinds
import casa.moller.tandem.core.database.entity.OutboxEntities
import casa.moller.tandem.core.database.entity.OutboxEntity
import casa.moller.tandem.core.database.entity.OutboxOps
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.network.api.TandoorApi
import casa.moller.tandem.core.network.dto.AutomationDto
import casa.moller.tandem.core.network.json.TandoorJson
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.engine.OutboxHandler
import casa.moller.tandem.core.sync.engine.OutboxResult
import casa.moller.tandem.core.sync.platform.SyncClock
import casa.moller.tandem.core.sync.remote.RemoteCaller
import casa.moller.tandem.core.sync.shopping.DroppedChange
import casa.moller.tandem.core.sync.shopping.DroppedChanges
import casa.moller.tandem.core.sync.shopping.claimedServerIds
import javax.inject.Inject
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * `food_alias` rows (`/api/automation/`, type FOOD_ALIAS; FACTS "Fas S d"), under
 * [FoodAliasReader.lock]:
 *
 * - **CREATE**: the space's aliases are checked first (a complete read at most a minute old): the
 *   same `param_1` (without case) that no other create of this device claimed is taken instead of a
 *   new one (re-pointed with a PATCH of `param_2` when it names another food), so two phones that
 *   learn the same code, or a retry after a lost answer, never make a duplicate. Otherwise `POST`.
 * - **PATCH** `{param_2}` / **DELETE**: a 404 means deleted elsewhere: the alias goes locally.
 *
 * A guest's 403 is a refused write (`RemoteCaller.write`), never a logout.
 */
internal class FoodAliasOutboxHandler @Inject constructor(
    private val databases: TandemDatabaseFactory,
    private val caller: RemoteCaller,
    private val reader: FoodAliasReader,
    private val dropped: DroppedChanges,
    private val clock: SyncClock,
) : OutboxHandler {
    override suspend fun send(remote: ActiveSource.Remote, row: OutboxEntity, payload: String): OutboxResult = reader.lock.withLock {
        val body = parse(payload) ?: return@withLock OutboxResult.Failed(TandemError.Unknown(UNSUPPORTED))
        when (row.op) {
            OutboxOps.CREATE -> create(remote, row, body)

            OutboxOps.PATCH -> update(remote, row) { id ->
                automations.patchAutomation(id, body).also { reader.noteServerAlias(remote.accountId, it) }
            }

            OutboxOps.DELETE -> update(remote, row) { id -> automations.deleteAutomation(id) }

            else -> OutboxResult.Failed(TandemError.Unknown(UNSUPPORTED))
        }
    }

    private suspend fun create(remote: ActiveSource.Remote, row: OutboxEntity, body: JsonObject): OutboxResult {
        val alias = (body["param_1"] as? JsonPrimitive)?.contentOrNull
            ?: return OutboxResult.Failed(TandemError.Unknown(UNSUPPORTED))
        val food = (body["param_2"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val known = when (val read = reader.serverAliasesLocked(remote)) {
            is Outcome.Failure -> return OutboxResult.Failed(read.error)
            is Outcome.Success -> read.value?.let { synchronized(it) { it.items.toList() } }.orEmpty()
        }
        val claimed = claimedServerIds(databases, remote, OutboxEntities.FOOD_ALIAS)
        val key = FoodAliases.key(alias)
        val existing = known.firstOrNull { other ->
            other.id != null && other.id !in claimed && !other.disabled && FoodAliases.key(other.param1.orEmpty()) == key
        }
        if (existing != null) {
            val id = existing.id ?: return OutboxResult.Failed(TandemError.Unknown(UNSUPPORTED))
            // Fas S review (fix-data): an alias made on the web (not one of Tandem's own names) wins,
            // as in `learn`: it is taken as it is, and this device's row follows the server.
            if (FoodAliases.kindOf(existing.name) == FoodAliasKinds.OTHER) {
                followServer(remote, row, existing)
                return OutboxResult.Created(id)
            }
            if (!existing.param2.equals(food, ignoreCase = true)) {
                val patch = buildJsonObject { put("param_2", food) }
                when (val sent = caller.write(remote) { automations.patchAutomation(id, patch) }) {
                    is Outcome.Success -> reader.noteServerAlias(remote.accountId, sent.value)

                    // Deleted meanwhile: a new alias instead.
                    is Outcome.Failure -> {
                        return if (sent.error == TandemError.NotFound) post(remote, body) else OutboxResult.Failed(sent.error)
                    }
                }
            }
            return OutboxResult.Created(id)
        }
        return post(remote, body)
    }

    /** The local row of [row]'s CREATE takes the server alias's food and kind (before `completeCreate` re-keys it). */
    private suspend fun followServer(remote: ActiveSource.Remote, row: OutboxEntity, server: AutomationDto) {
        val localId = row.localId ?: return
        val database = databases.get(remote.kind)
        val local = database.foodAliasDao().get(remote.accountId, localId) ?: return
        val food = server.param2?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val foodId = database.referenceDao().getFoods(remote.accountId).firstOrNull { it.name.equals(food, ignoreCase = true) }?.id
        database.foodAliasDao().upsert(
            listOf(local.copy(foodName = food, foodId = foodId, kind = FoodAliasKinds.OTHER, automationName = server.name)),
        )
    }

    private suspend fun post(remote: ActiveSource.Remote, body: JsonObject): OutboxResult =
        when (val sent = caller.write(remote) { automations.createAutomation(body) }) {
            is Outcome.Failure -> OutboxResult.Failed(sent.error)

            is Outcome.Success -> {
                val created: AutomationDto = sent.value
                reader.noteServerAlias(remote.accountId, created)
                created.id?.let { OutboxResult.Created(it) } ?: OutboxResult.Failed(TandemError.Decode("automation-without-id"))
            }
        }

    private suspend fun update(remote: ActiveSource.Remote, row: OutboxEntity, request: suspend TandoorApi.(Long) -> Any): OutboxResult {
        val serverId = row.serverId ?: return OutboxResult.Failed(TandemError.Unknown(WAITING_FOR_ID))
        return when (val sent = caller.write(remote) { this.request(serverId) }) {
            is Outcome.Success -> OutboxResult.Sent

            is Outcome.Failure -> {
                if (sent.error != TandemError.NotFound) return OutboxResult.Failed(sent.error)
                val database = databases.get(remote.kind)
                database.pantryDao().inTransaction {
                    database.outboxDao().deleteUnsentFor(remote.accountId, OutboxEntities.FOOD_ALIAS, serverId)
                    database.foodAliasDao().delete(remote.accountId, serverId)
                }
                reader.invalidate(remote.accountId)
                dropped.record(DroppedChange(row.entity, row.op, serverId, DroppedChanges.NOT_FOUND, clock.nowMillis()))
                OutboxResult.Dropped
            }
        }
    }

    private companion object {
        const val UNSUPPORTED = "tandem.sync.unsupported-outbox-op"
        const val WAITING_FOR_ID = "tandem.sync.waiting-for-server-id"

        fun parse(payload: String): JsonObject? = runCatching {
            TandoorJson.parseToJsonElement(payload)
        }.getOrNull() as? JsonObject
    }
}
