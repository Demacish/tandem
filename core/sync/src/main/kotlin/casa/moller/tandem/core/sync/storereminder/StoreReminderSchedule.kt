package casa.moller.tandem.core.sync.storereminder

import casa.moller.tandem.core.data.di.ApplicationScope
import casa.moller.tandem.core.datastore.SecureAccountStore
import casa.moller.tandem.core.datastore.StorageHealthMonitor
import casa.moller.tandem.core.datastore.StoreReminderStore
import casa.moller.tandem.core.datastore.StoreSpotRecord
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.account.ActiveSources
import casa.moller.tandem.core.sync.account.identity
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Fas 5 (butik lane, plan §7 #34): keeps the reminder near the store in step, started by
 * `SyncStarter` at every process start (an app start, a background sync, a reboot or an app update
 * through [StoreReminderSystemReceiver], a geofence event):
 *
 * - the geofences follow the active account, its on/off and its stores ([StoreReminderRegistrar]);
 *   logging out, demo mode or an account without the reminder removes them;
 * - an account that left the phone takes its places and visits along (never while local storage
 *   needs recovery: a store that was just reset reads as empty and is no logout).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
internal class StoreReminderSchedule @Inject constructor(
    private val sources: ActiveSources,
    private val store: StoreReminderStore,
    private val registrar: StoreReminderRegistrar,
    private val accounts: SecureAccountStore,
    private val health: StorageHealthMonitor,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)

    /** What the geofences depend on (a record's visits change on every event and are left out). */
    private data class Wanted(val identity: String, val enabled: Boolean = false, val spots: List<StoreSpotRecord> = emptyList())

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            sources.source
                .filter { it != ActiveSource.Loading }
                .distinctUntilChangedBy { it.identity }
                .flatMapLatest { source ->
                    if (source is ActiveSource.Remote) {
                        store.observe(source.accountId).map { Wanted(source.identity, it.enabled, it.spots) }
                    } else {
                        flowOf(Wanted(source.identity))
                    }
                }
                .distinctUntilChanged()
                // A store that cannot be read: nothing to follow until the next start.
                .catch { emit(Wanted("unreadable")) }
                .collectLatest { registrar.sync() }
        }
        scope.launch {
            accounts.accounts
                .map { list -> list.mapTo(HashSet()) { it.id } }
                .distinctUntilChanged()
                .collect { known -> forgetAccountsThatLeft(known) }
        }
    }

    // Storage trouble of any kind waits for the next change; cancellation is rethrown first.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun forgetAccountsThatLeft(known: Set<String>) {
        if (health.health.value.recoveryNeeded) return
        try {
            if (!known.containsAll(store.accounts())) store.retainOnly(known)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Storage trouble: the next change (at the latest the next start) tries again.
        }
    }
}
