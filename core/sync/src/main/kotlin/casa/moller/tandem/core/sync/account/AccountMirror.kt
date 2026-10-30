package casa.moller.tandem.core.sync.account

import casa.moller.tandem.core.data.di.ApplicationScope
import casa.moller.tandem.core.data.session.Session
import casa.moller.tandem.core.data.session.SessionRepository
import casa.moller.tandem.core.database.DatabaseKind
import casa.moller.tandem.core.database.TandemDatabaseFactory
import casa.moller.tandem.core.database.entity.ServerAccountEntity
import casa.moller.tandem.core.datastore.SecureAccountStore
import casa.moller.tandem.core.datastore.SettingsStore
import casa.moller.tandem.core.datastore.StorageHealthMonitor
import casa.moller.tandem.core.model.account.ServerAccount
import casa.moller.tandem.core.model.server.ServerVersionChange
import casa.moller.tandem.core.model.settings.PendingVersionNotice
import casa.moller.tandem.core.sync.demo.DemoSeeder
import casa.moller.tandem.core.sync.drink.DrinkImageStore
import casa.moller.tandem.core.sync.image.ImageCache
import casa.moller.tandem.core.sync.platform.SyncClock
import casa.moller.tandem.core.sync.platform.VersionChangeNotifier
import casa.moller.tandem.core.sync.platform.VersionNotificationPolicy
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
 *   When no notification could be posted, a change to a tested version is stored as the in-app
 *   notice (`AppSettings.versionNotice`, shown once by the shell). It works whoever checked the
 *   version (the shell's probe or the background sync, also while no screen is open). The first
 *   login stores the version without any notice.
 * - **Logout and account removal:** rows of accounts that are no longer stored are deleted, in
 *   every table: also rows that a response still in flight wrote after the account's mirror row
 *   was gone (they are found by their `account_id`, at the latest at the next app start). The
 *   image cache is emptied with them (it is shared, decision B14), and after "Clear app data".
 *   An expired token (Unauthorized) removes nothing: the account stays, so the outbox continues
 *   after logging in again to the same account and space.
 * - **Demo data** is cleared as soon as a real account is logged in (plan 6.2).
 *
 * Nothing happens while local storage needs recovery, so "Export pending changes" still finds
 * the outbox.
 */
@Singleton
internal class AccountMirror @Inject constructor(
    private val sessions: SessionRepository,
    private val accountStore: SecureAccountStore,
    private val settings: SettingsStore,
    private val health: StorageHealthMonitor,
    private val databases: TandemDatabaseFactory,
    private val demo: DemoSeeder,
    private val imageCache: ImageCache,
    private val notifier: VersionChangeNotifier,
    private val clock: SyncClock,
    @ApplicationScope private val scope: CoroutineScope,
    // Fas S review (fix-data): the photos of drinks created offline go with the account's rows.
    private val drinkImages: DrinkImageStore,
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
            removeForgottenAccounts(session)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Storage trouble: keep going; the next session change tries again.
        }
    }

    /**
     * Phase 2 (konto lane): removes the rows of every account that is no longer stored, now. Called
     * after another (not the active) account was removed: that does not change the session, so
     * [onSession] would only find it at the next session change. Never throws.
     */
    suspend fun removeForgotten() {
        try {
            removeForgottenAccounts(sessions.session.first())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Storage trouble: the next session change (at the latest the next start) tries again.
        }
    }

    private suspend fun mirror(account: ServerAccount) {
        val dao = databases.get(DatabaseKind.REAL).accountDao()
        val stored = dao.get(account.id)
        val decision = MirrorDecision.of(stored, account)
        if (decision.clearStoredData) {
            databases.clearAccount(DatabaseKind.REAL, account.id)
            imageCache.clear()
            drinkImages.deleteAccount(account.id)
        }
        val kept = stored?.takeUnless { decision.clearStoredData }
        dao.upsert(
            ServerAccountEntity(
                id = account.id,
                baseUrl = account.baseUrl,
                userId = account.userId,
                spaceId = account.spaceId,
                householdId = kept?.householdId,
                // Phase 2 (konto lane): the household's name read with the group stays too.
                householdName = kept?.householdName,
                serverVersion = account.serverVersion ?: kept?.serverVersion,
                groupLevel = kept?.groupLevel,
                tokenId = account.tokenId,
                updatedAtEpochMillis = clock.nowMillis(),
                // Phase 2 (inköp-data lane): the polling floor read with the shopping tables stays.
                shoppingMinAutosyncSeconds = kept?.shoppingMinAutosyncSeconds,
            ),
        )
        decision.versionChange?.let { change ->
            changes.tryEmit(change)
            val posted = notifier.onVersionChanged(change)
            val notice = PendingVersionNotice(account.id, change.previousVersion, change.newVersion)
            val inApp = VersionNotificationPolicy.needsInAppNotice(change, posted)
            // A newer change replaces an older notice of the same account that was never shown.
            settings.update { current ->
                val kept = current.versionNotice?.takeUnless { it.accountId == account.id }
                current.copy(versionNotice = if (inApp) notice else kept)
            }
        }
    }

    private suspend fun removeForgottenAccounts(session: Session) {
        // A store that was just reset reads as empty: never mistake that for a logout.
        if (health.health.value.recoveryNeeded) return
        // The ids with rows are read BEFORE the stored accounts: an account that logs in meanwhile
        // is stored before its first row is written, so it is always in `known`.
        val withRows = databases.get(DatabaseKind.REAL).accountDao().getStoredAccountIds()
        val known = accountStore.accounts.first().mapTo(HashSet()) { it.id }
        val forgotten = withRows.filter { it !in known }
        forgotten.forEach {
            databases.clearAccount(DatabaseKind.REAL, it)
            drinkImages.deleteAccount(it)
        }
        // An in-app version notice for an account that is gone is never shown.
        if (settings.settings.first().versionNotice?.accountId?.let { it !in known } == true) {
            settings.update { current -> current.copy(versionNotice = current.versionNotice?.takeIf { it.accountId in known }) }
        }
        // Without any account (logout, "Clear app data") no image may stay either, also when an
        // earlier clean-up removed the rows but did not get to the images.
        if (forgotten.isNotEmpty() || (known.isEmpty() && session == Session.NoAccount)) imageCache.clear()
        if (known.isEmpty() && session == Session.NoAccount) drinkImages.deleteAll()
    }

    private companion object {
        const val CHANGE_BUFFER = 8
    }
}
