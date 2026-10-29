package casa.moller.tandem.core.sync.pantry

import casa.moller.tandem.core.database.TandemDatabaseFactory
import casa.moller.tandem.core.database.entity.SyncStateEntity
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.permissions.isGuest
import casa.moller.tandem.core.sync.platform.SyncClock
import casa.moller.tandem.core.sync.scan.FoodAliasReader
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fas S (hemma-data lane): the pantry's step of a sync run (`SyncEngine`, after the books): the
 * locations and entries in full ([PantryReader], at most every [PANTRY_INTERVAL_MILLIS] unless
 * [forced] or with the small tables), the learned aliases with the small tables ([FoodAliasReader],
 * and once when none were read yet), and staples that wait for a food
 * ([DefaultStaplesRepository.resolvePending]). Nothing for a guest (403 on these lists). A failure
 * keeps the stored data and never fails the run; the engine sends the outbox after it.
 */
@Singleton
internal class PantrySyncStep @Inject constructor(
    private val databases: TandemDatabaseFactory,
    private val reader: PantryReader,
    private val aliases: FoodAliasReader,
    private val staples: DefaultStaplesRepository,
    private val clock: SyncClock,
) {
    suspend fun run(remote: ActiveSource.Remote, background: Boolean, smallTables: Boolean, forced: Boolean) {
        val database = databases.get(remote.kind)
        val accountId = remote.accountId
        if (isGuest(database, accountId)) return
        val states = database.syncStateDao()
        val now = clock.nowMillis()
        val pantryRead = states.get(accountId, PANTRY_KEY)?.lastFullRelistEpochMillis
        if (forced || smallTables || pantryRead == null || now - pantryRead !in 0 until PANTRY_INTERVAL_MILLIS) {
            if (reader.sync(remote, background) is Outcome.Success) {
                states.upsert(SyncStateEntity(accountId = accountId, entity = PANTRY_KEY, lastFullRelistEpochMillis = now))
            }
        }
        val aliasesRead = states.get(accountId, ALIASES_KEY)?.lastFullRelistEpochMillis
        if (smallTables || aliasesRead == null) {
            if (aliases.sync(remote, background) is Outcome.Success) {
                states.upsert(SyncStateEntity(accountId = accountId, entity = ALIASES_KEY, lastFullRelistEpochMillis = now))
            }
        }
        staples.resolvePending(remote, background)
    }

    companion object {
        /** `sync_state`: when the pantry was last read completely. */
        const val PANTRY_KEY: String = "inventory"

        /** `sync_state`: when the learned aliases were last read completely. */
        const val ALIASES_KEY: String = "food_alias"

        /** The pantry is read at most this often by the sync (Hemma's pull to refresh always reads). */
        const val PANTRY_INTERVAL_MILLIS: Long = 5 * 60_000L
    }
}
