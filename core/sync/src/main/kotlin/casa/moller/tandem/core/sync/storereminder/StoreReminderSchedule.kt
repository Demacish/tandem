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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
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
 * - while a store is watched, the location setting is followed too ([LocationSettingWatcher], 0.8.0
 *   review, fix-08): Play services drops every geofence when the location goes off, and Android does
 *   not deliver the "location on" broadcast to a manifest receiver, so turning the location back on
 *   registers the geofences again here ([StoreReminderRegistrar.sync] with `force`), and every return
 *   to the foreground with the location on checks them again (a lost set is registered again);
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
    private val location: LocationSettingWatcher,
) {
    private val started = AtomicBoolean(false)

    /** What the geofences depend on (a record's visits change on every event and are left out). */
    private data class Wanted(val identity: String, val enabled: Boolean = false, val spots: List<StoreSpotRecord> = emptyList()) {
        /** A store is watched: the location setting matters (only then is it followed). */
        val watches: Boolean
            get() = enabled && spots.any { it.enabled }
    }

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
                .flatMapLatest { wanted -> if (wanted.watches) syncsWhileWatching() else flowOf(false) }
                // One sync after the other: a forced one is never cut short by the next event.
                .collect { force -> registrar.sync(force) }
        }
        scope.launch {
            accounts.accounts
                .map { list -> list.mapTo(HashSet()) { it.id } }
                .distinctUntilChanged()
                .collect { known -> forgetAccountsThatLeft(known) }
        }
    }

    /**
     * The syncs while a store is watched, as `force` flags: one at once, a forced one when the
     * location was turned on, a plain one (registers only a lost or changed set) whenever the
     * location is found on again (the app came to the foreground). Turning it off needs nothing: Play
     * services drops the geofences itself and says so (`GEOFENCE_NOT_AVAILABLE`, [StoreReminderRegistrar.onGeofencesLost]).
     */
    private fun syncsWhileWatching(): Flow<Boolean> = flow {
        var wasOn: Boolean? = null
        location.locationOn().collect { on ->
            val before = wasOn
            wasOn = on
            when {
                before == null -> emit(false)
                on -> emit(before == false)
                else -> Unit
            }
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
