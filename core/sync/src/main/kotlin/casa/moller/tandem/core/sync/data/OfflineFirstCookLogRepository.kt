package casa.moller.tandem.core.sync.data

import casa.moller.tandem.core.data.DataProblem
import casa.moller.tandem.core.data.cooklog.CookLogRepository
import casa.moller.tandem.core.database.TandemDatabaseFactory
import casa.moller.tandem.core.database.entity.CookLogEntity
import casa.moller.tandem.core.model.cooklog.CookLogDraft
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.catchingUnexpected
import casa.moller.tandem.core.network.dto.CookLogCreateRequestDto
import casa.moller.tandem.core.network.json.TandoorDates
import casa.moller.tandem.core.network.json.TandoorJson
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.account.ActiveSources
import casa.moller.tandem.core.sync.engine.cookStatsRules
import casa.moller.tandem.core.sync.platform.SyncClock
import casa.moller.tandem.core.sync.platform.SyncScheduler
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/**
 * [CookLogRepository] on Room and the outbox (plan 5.3, 5.7; feature #8 "Klart!"). See the
 * interface for the contract; the notes here are about how it is met.
 *
 * - **Real account:** `CookLogDao.logLocally` stores the entry, queues `COOK_LOG` with the body
 *   of `POST /api/cook-log/` and updates the recipe's "times cooked", "last cooked" and own rating
 *   in one transaction; then the outbox worker is scheduled (`OutboxSender` sends it, the next
 *   sync reconciles it with the server's list without counting it twice).
 * - **`created_at`:** the moment of [CookLogDraft.cookedAt], in whole seconds (what
 *   `TandoorDates.format` sends), so an entry sent days later keeps its date and a retry can find
 *   an entry that reached the server before its answer was lost.
 * - **Demo mode:** the entry goes into the demo database only and is never queued or sent;
 *   "Återställ demodata" empties that database, entries included.
 * - **Account:** rows carry the active account's id; logging out removes them with the account's
 *   other rows, and a new login to the same account and space keeps and sends them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
internal class OfflineFirstCookLogRepository @Inject constructor(
    private val sources: ActiveSources,
    private val databases: TandemDatabaseFactory,
    private val scheduler: SyncScheduler,
    private val clock: SyncClock,
) : CookLogRepository {

    override suspend fun logCooked(draft: CookLogDraft): Outcome<Unit> = catchingUnexpected {
        when (val source = sources.current()) {
            is ActiveSource.Stored -> {
                val remote = source as? ActiveSource.Remote
                val cookedAtMillis = draft.cookedAt.epochSeconds * MILLIS_PER_SECOND
                val entry = CookLogEntity(
                    accountId = source.accountId,
                    localId = 0,
                    serverId = null,
                    recipeId = draft.recipeId,
                    servings = draft.servings,
                    rating = draft.rating,
                    comment = draft.commentOrNull,
                    createdBy = remote?.account?.userId,
                    cookedAtEpochMillis = cookedAtMillis,
                )
                val payload = remote?.let {
                    val body = CookLogCreateRequestDto(
                        recipe = draft.recipeId,
                        servings = draft.servings,
                        rating = draft.rating,
                        comment = draft.commentOrNull,
                        createdAt = TandoorDates.format(java.time.Instant.ofEpochMilli(cookedAtMillis)),
                    )
                    TandoorJson.encodeToString(CookLogCreateRequestDto.serializer(), body)
                }
                databases.get(source.kind).cookLogDao().logLocally(entry, payload, clock.nowMillis(), source.cookStatsRules)
                if (remote != null) scheduler.scheduleOutbox()
                Outcome.Success(Unit)
            }

            ActiveSource.Loading, ActiveSource.None -> Outcome.Failure(DataProblem.NO_ACCOUNT.toError())
        }
    }

    override fun observePendingCount(): Flow<Int> = sources.source
        .flatMapLatest { source ->
            when (source) {
                is ActiveSource.Remote -> databases.get(source.kind).cookLogDao().observePendingCount(source.accountId)

                // Demo entries are never queued; without an account nothing waits.
                ActiveSource.Demo, ActiveSource.Loading, ActiveSource.None -> flowOf(0)
            }
        }
        .catch { emit(0) }
        .distinctUntilChanged()

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
    }
}
