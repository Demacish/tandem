package casa.moller.tandem.core.sync.account

import casa.moller.tandem.core.data.DataProblem
import casa.moller.tandem.core.data.account.AccountDirectory
import casa.moller.tandem.core.data.account.AccountRemoval
import casa.moller.tandem.core.data.account.AccountSessions
import casa.moller.tandem.core.data.session.Session
import casa.moller.tandem.core.data.session.SessionRepository
import casa.moller.tandem.core.database.DatabaseKind
import casa.moller.tandem.core.database.TandemDatabaseFactory
import casa.moller.tandem.core.database.entity.ServerAccountEntity
import casa.moller.tandem.core.datastore.SecureAccountStore
import casa.moller.tandem.core.model.account.AccountSummary
import casa.moller.tandem.core.model.account.Household
import casa.moller.tandem.core.model.account.ServerAccount
import casa.moller.tandem.core.model.account.SpaceRole
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.catchingUnexpected
import casa.moller.tandem.core.model.sync.SyncReason
import casa.moller.tandem.core.network.capabilities.TandoorVersionTable
import casa.moller.tandem.core.sync.data.RoomPendingChangesExporter
import casa.moller.tandem.core.sync.permissions.SpaceRoleSync
import casa.moller.tandem.core.sync.platform.SyncScheduler
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull

// Phase 2 (konto lane): several stored accounts, one active (decision B14, plan 6.2 "Konto/server",
// Stitch konto_servrar), and the household on the account card (plan 8.6).

/**
 * [AccountDirectory] over the encrypted account store (which accounts exist), the session (which
 * one is active), the real database (household, group and outbox of each account) and
 * [AccountSessions] (activating and removing, with the login's write lock).
 *
 * - **Switching** only changes the active account. Everything else follows the session: the
 *   [ActiveSourceProvider] gives every repository the new account's rows and server, the
 *   [casa.moller.tandem.core.sync.SyncStarter] cancels the old account's background work (the
 *   one-off sync is replaced, a running outbox drain stops at its next row, polling reads the new
 *   source) and asks for the new account's sync. Each account keeps its rows and its outbox, so
 *   switching back continues where it was.
 * - **Removing** refuses while the account has unsent changes, unless the caller confirms that
 *   the user saw them ([AccountRemoval.PendingChanges]); then the account, its token and every row
 *   of it are removed (the image cache too, it is shared).
 * - **Household and role** come from `/api/user-space/all_personal/` ([SpaceRoleSync], with the
 *   small tables and on [refreshHousehold]). Demo mode is never an account.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
internal class DefaultAccountDirectory @Inject constructor(
    private val accountSessions: AccountSessions,
    private val sessions: SessionRepository,
    private val accountStore: SecureAccountStore,
    private val databases: TandemDatabaseFactory,
    private val sources: ActiveSources,
    private val roles: SpaceRoleSync,
    private val mirror: AccountMirror,
    private val exporter: RoomPendingChangesExporter,
    private val scheduler: SyncScheduler,
) : AccountDirectory {

    override fun observeAccounts(): Flow<List<AccountSummary>> = flow {
        val database = databases.get(DatabaseKind.REAL)
        val activeId = sessions.session.map { it.activeAccountId }.distinctUntilChanged()
        val snapshots = combine(accountStore.accounts, activeId, database.accountDao().observeAll()) { accounts, active, rows ->
            Snapshot(accounts, active, rows.associateBy { it.id })
        }
        emitAll(
            snapshots.flatMapLatest { snapshot ->
                if (snapshot.accounts.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    combine(snapshot.accounts.map { database.outboxDao().observeCount(it.id) }) { counts ->
                        snapshot.accounts.mapIndexed { index, account -> snapshot.summaryOf(account, counts[index]) }
                    }
                }
            },
        )
    }
        .catch { emit(emptyList()) }
        .distinctUntilChanged()

    override suspend fun switchTo(accountId: String): Outcome<Unit> = catchingUnexpected {
        if (accountStore.accounts.first().none { it.id == accountId }) {
            return@catchingUnexpected Outcome.Failure(DataProblem.NO_ACCOUNT.toError())
        }
        val current = sessions.session.first()
        if (current is Session.LoggedIn && current.account.id == accountId) return@catchingUnexpected Outcome.Success(Unit)
        val result = accountSessions.activate(accountId)
        if (result is Outcome.Success && current !is Session.LoggedIn) {
            // From demo mode the starter asks for no sync (a login from the intro runs its own first
            // sync), so the switch asks for it once the new source is in place.
            val active = withTimeoutOrNull(SOURCE_SWITCH_TIMEOUT) {
                sources.source.first { it is ActiveSource.Remote && it.accountId == accountId }
            }
            if (active != null) scheduler.requestSync(SyncReason.ACCOUNT_CHANGED)
        }
        result
    }

    override fun observeHousehold(): Flow<Household?> = sources.source
        .flatMapLatest { source ->
            when (source) {
                is ActiveSource.Remote -> databases.get(source.kind).accountDao().observe(source.accountId).map { it?.household() }
                ActiveSource.Demo, ActiveSource.Loading, ActiveSource.None -> flowOf(null)
            }
        }
        .catch { emit(null) }
        .distinctUntilChanged()

    override suspend fun refreshHousehold(): Outcome<Household?> = catchingUnexpected {
        val remote = sources.current() as? ActiveSource.Remote ?: return@catchingUnexpected Outcome.Success(null)
        when (val read = roles.sync(remote, background = false)) {
            is Outcome.Failure -> read
            is Outcome.Success -> Outcome.Success(databases.get(remote.kind).accountDao().get(remote.accountId)?.household())
        }
    }

    override suspend fun remove(accountId: String, revokeToken: Boolean, discardPendingChanges: Boolean): Outcome<AccountRemoval> =
        catchingUnexpected {
            if (accountStore.accounts.first().none { it.id == accountId }) {
                return@catchingUnexpected Outcome.Failure(DataProblem.NO_ACCOUNT.toError())
            }
            if (!discardPendingChanges) {
                // Never silently: an unreadable outbox throws and nothing is removed.
                val pending = databases.get(DatabaseKind.REAL).outboxDao().observeCount(accountId).first()
                if (pending > 0) return@catchingUnexpected Outcome.Success(AccountRemoval.PendingChanges(pending))
            }
            when (val removed = accountSessions.remove(accountId, revokeToken)) {
                is Outcome.Failure -> removed

                is Outcome.Success -> {
                    // Another account's removal does not change the session: clean up now.
                    mirror.removeForgotten()
                    val active = (sessions.session.first() as? Session.LoggedIn)?.account
                    Outcome.Success(AccountRemoval.Removed(removed.value, active))
                }
            }
        }

    override suspend fun exportPendingChanges(accountId: String): Outcome<String> = exporter.exportAccount(accountId)

    private class Snapshot(val accounts: List<ServerAccount>, val activeId: String?, val rows: Map<String, ServerAccountEntity>) {
        fun summaryOf(account: ServerAccount, pending: Int): AccountSummary {
            val row = rows[account.id]
            return AccountSummary(
                account = account,
                isActive = account.id == activeId,
                household = row?.household(),
                role = SpaceRole.of(listOfNotNull(row?.groupLevel)),
                pendingChanges = pending,
                compatibility = TandoorVersionTable.assess(account.serverVersion ?: row?.serverVersion).compatibility,
            )
        }
    }

    private companion object {
        /** How long a switch from demo mode waits for the new source before it asks for the sync. */
        val SOURCE_SWITCH_TIMEOUT = 5.seconds

        /** The logged-in account's id; demo mode, no account and recovery have none. */
        val Session.activeAccountId: String?
            get() = (this as? Session.LoggedIn)?.account?.id

        fun ServerAccountEntity.household(): Household? {
            val id = householdId ?: return null
            val name = householdName?.takeIf { it.isNotBlank() } ?: return null
            return Household(id, name)
        }
    }
}
