package casa.moller.tandem.core.sync.account

import casa.moller.tandem.core.data.di.ApplicationScope
import casa.moller.tandem.core.data.session.Session
import casa.moller.tandem.core.data.session.SessionRepository
import casa.moller.tandem.core.database.DatabaseKind
import casa.moller.tandem.core.database.TandemDatabaseFactory
import casa.moller.tandem.core.database.entity.ServerAccountEntity
import casa.moller.tandem.core.datastore.SecureAccountStore
import casa.moller.tandem.core.datastore.StorageHealthMonitor
import casa.moller.tandem.core.model.account.ServerAccount
import casa.moller.tandem.core.model.server.ServerVersionChange
import casa.moller.tandem.core.sync.demo.DemoSeeder
import casa.moller.tandem.core.sync.platform.SyncClock
import casa.moller.tandem.core.sync.platform.VersionChangeNotifier
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Keeps the real database in step with the session (plan 5.3, 5.7, 6.2, 11.1):
 *
 * - **Mirror:** the logged-in account's non-secret facts are stored in `server_account`. When the
 *   same local account id now means another user, space or server, its old rows are removed
 *   first ([MirrorDecision]).
 * - **Version changes** (plan B18): a server version that differs from the stored one is emitted
 *   on [versionChanges] and reported to the [VersionChangeNotifier] (a notification if allowed).
 *   It works whoever checked the version (the shell's probe or the sync).
 * - **Logout and account removal:** rows of accounts that are no longer stored are deleted. An
 *   expired token (Unauthorized) removes nothing: the account stays, so the outbox continues after
 *   logging in again to the same account and space.
 * - **Demo data** is cleared as soon as a real account is logged in (plan 6.2).
 *
 * Nothing happens while local storage needs recovery, so "Export pending changes" still finds
 * the outbox.
 */
@Singleton
internal class AccountMirror @Inject constructor(
    private val sessions: SessionRepository,
    private val accountStore: SecureAccountStore,
    private val health: StorageHealthMonitor,
    private val databases: TandemDatabaseFactory,
    private val demo: DemoSeeder,
    private val notifier: VersionChangeNotifier,
    private val clock: SyncClock,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val changes = MutableSharedFlow<ServerVersionChange>(extraBufferCapacity = CHANGE_BUFFER)
    private val started = AtomicBoolean(false)

    /** Version changes found while the app runs; not replayed. */
    val versionChanges: Flow<ServerVersionChange> = changes.asSharedFlow()

    /** Starts following the session (once per process). */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch { sessions.session.collect { onSession(it) } }
    }

    /** Applies one session state; never throws (a failure is retried with the next session change). */
    suspend fun onSession(session: Session) {
        if (session is Session.RecoveryNeeded) return
        try {
            if (session is Session.LoggedIn) {
                mirror(session.account)
                demo.clearIfSeeded()
            }
            removeForgottenAccounts()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Storage trouble: keep going; the next session change tries again.
        }
    }

    private suspend fun mirror(account: ServerAccount) {
        val dao = databases.get(DatabaseKind.REAL).accountDao()
        val stored = dao.get(account.id)
        val decision = MirrorDecision.of(stored, account)
        if (decision.clearStoredData) databases.clearAccount(DatabaseKind.REAL, account.id)
        val kept = stored?.takeUnless { decision.clearStoredData }
        dao.upsert(
            ServerAccountEntity(
                id = account.id,
                baseUrl = account.baseUrl,
                userId = account.userId,
                spaceId = account.spaceId,
                householdId = kept?.householdId,
                serverVersion = account.serverVersion ?: kept?.serverVersion,
                groupLevel = kept?.groupLevel,
                tokenId = account.tokenId,
                updatedAtEpochMillis = clock.nowMillis(),
            ),
        )
        decision.versionChange?.let { change ->
            changes.tryEmit(change)
            notifier.onVersionChanged(change)
        }
    }

    private suspend fun removeForgottenAccounts() {
        // A store that was just reset reads as empty: never mistake that for a logout.
        if (health.health.value.recoveryNeeded) return
        val known = accountStore.accounts.first().mapTo(HashSet()) { it.id }
        val mirrored = databases.get(DatabaseKind.REAL).accountDao().observeAll().first()
        for (row in mirrored) {
            if (row.id !in known) databases.clearAccount(DatabaseKind.REAL, row.id)
        }
    }

    private companion object {
        const val CHANGE_BUFFER = 8
    }
}
