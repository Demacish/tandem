package casa.moller.tandem.core.sync.data

import casa.moller.tandem.core.data.sync.PendingChangesExporter
import casa.moller.tandem.core.database.DatabaseKind
import casa.moller.tandem.core.database.TandemDatabaseFactory
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.network.json.TandoorDates
import java.time.Instant
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * "Export pending changes" (feature #37) over the outbox of every account mirrored in the real
 * database, also while the session needs recovery (the accounts may be unreadable then, but the
 * outbox in Room is not). Only the queued operations: never a token or a server credential
 * (plan 5.5), and no server address either.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class RoomPendingChangesExporter @Inject constructor(private val databases: TandemDatabaseFactory) : PendingChangesExporter {

    override fun observePendingCount(): Flow<Int> {
        val database = databases.get(DatabaseKind.REAL)
        return database.accountDao().observeAll()
            .flatMapLatest { accounts ->
                if (accounts.isEmpty()) {
                    flowOf(0)
                } else {
                    combine(accounts.map { database.outboxDao().observeCount(it.id) }) { counts -> counts.sum() }
                }
            }
            .catch { emit(0) }
    }

    override suspend fun exportAsJson(): Outcome<String> = export(accountIds = null)

    /**
     * Phase 2 (konto lane): the pending changes of one account only, before it is removed
     * (`AccountDirectory.exportPendingChanges`). Same format; also when its mirror row is gone.
     */
    suspend fun exportAccount(accountId: String): Outcome<String> = export(accountIds = listOf(accountId))

    /** [accountIds] null = every mirrored account. */
    private suspend fun export(accountIds: List<String>?): Outcome<String> = try {
        val database = databases.get(DatabaseKind.REAL)
        val ids = accountIds ?: database.accountDao().observeAll().first().map { it.id }
        val changes = buildJsonArray {
            for (accountId in ids) {
                for (row in database.outboxDao().observeAll(accountId).first()) {
                    add(
                        buildJsonObject {
                            put("operation", row.op)
                            put("type", row.entity)
                            row.serverId?.let { put("id", it) }
                            put("payload", parse(row.payload))
                            put("createdAt", TandoorDates.format(Instant.ofEpochMilli(row.createdAtEpochMillis)))
                            put("attempts", row.attempts)
                            put("state", row.state.name)
                            row.lastError?.let { put("lastError", it) }
                        },
                    )
                }
            }
        }
        val document = buildJsonObject {
            put("format", "tandem-pending-changes")
            put("version", 1)
            put("changes", changes)
        }
        Outcome.Success(PRETTY.encodeToString(JsonElement.serializer(), document))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Outcome.Failure(TandemError.Unknown(EXPORT_FAILED))
    }

    private fun parse(payload: String): JsonElement = try {
        Json.parseToJsonElement(payload)
    } catch (e: IllegalArgumentException) {
        JsonObject(emptyMap())
    }

    private companion object {
        const val EXPORT_FAILED = "tandem.sync.export-failed"
        val PRETTY = Json { prettyPrint = true }
    }
}
