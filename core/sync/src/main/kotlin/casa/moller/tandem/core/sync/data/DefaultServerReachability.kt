package casa.moller.tandem.core.sync.data

import casa.moller.tandem.core.data.di.ApplicationScope
import casa.moller.tandem.core.data.session.SessionRepository
import casa.moller.tandem.core.data.sync.ServerReachability
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.error.isConnectivityProblem
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.account.ActiveSources
import casa.moller.tandem.core.sync.guard.SyncGuard
import casa.moller.tandem.core.sync.platform.NetworkStatus
import casa.moller.tandem.core.sync.platform.SyncClock
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The last answer (or connectivity failure) of each account's server, fed by every request
 * core:sync sends (`RemoteCaller`) and by [DefaultRequestGuard] (0.8.0 device test B19). Pure
 * bookkeeping in memory; [DefaultServerReachability] turns it into the app's offline state.
 */
@Singleton
internal class ServerContact @Inject constructor() {
    private val mutableState = MutableStateFlow(Contact())

    /** The last request's outcome; [Contact.accountId] null before the first one. */
    val state: StateFlow<Contact> = mutableState.asStateFlow()

    /**
     * Records one request of [accountId]: a connectivity failure marks the server unreachable;
     * a success or any other answer (an HTTP error is still an answer) marks it reached.
     */
    fun record(accountId: String, error: TandemError?) {
        mutableState.value = Contact(accountId, unreachable = error?.isConnectivityProblem == true)
    }

    /** Forgets a failure of [accountId] without an answer (see [DefaultServerReachability]). */
    fun forget(accountId: String) {
        if (mutableState.value.accountId == accountId) mutableState.value = Contact()
    }

    /** @property unreachable the last request of [accountId] could not reach its server. */
    data class Contact(val accountId: String? = null, val unreachable: Boolean = false) {
        fun unreachable(accountId: String): Boolean = unreachable && this.accountId == accountId
    }
}

/**
 * [ServerReachability] on the network monitor the sync already uses ([NetworkStatus], no second
 * callback) and [ServerContact]:
 *
 * - [offline]: a remote account is active and either the device has no network or its server's
 *   last request failed with a connectivity problem. Demo mode and no account: never offline.
 * - **Reconnect check:** when the network comes back while the server's last request failed, one
 *   server check (`SessionRepository.refreshServerInfo`, an unauthenticated read of the server's
 *   settings) is sent after [RECONNECT_DEBOUNCE_MILLIS] of steady network. A network that drops
 *   again within that time cancels it, and checks are at least [MIN_CHECK_SPACING_MILLIS] apart,
 *   so a flapping network never sends a burst (plan §5.4). The guard is asked first: while it holds
 *   background requests back (blocked, throttled, breaker, import) nothing is sent and the failure
 *   is forgotten, so the notice follows the network and the guard's own banner explains the pause.
 *   When the last request succeeded there is nothing to correct and nothing is sent.
 */
@Singleton
internal class DefaultServerReachability(
    private val contact: ServerContact,
    private val network: NetworkStatus,
    private val sources: ActiveSources,
    private val sessions: Provider<SessionRepository>,
    private val guard: SyncGuard,
    private val scope: CoroutineScope,
    /** Monotonic time in milliseconds, for [MIN_CHECK_SPACING_MILLIS]. */
    private val elapsedMillis: () -> Long,
) : ServerReachability {
    @Inject
    constructor(
        contact: ServerContact,
        network: NetworkStatus,
        sources: ActiveSources,
        sessions: Provider<SessionRepository>,
        guard: SyncGuard,
        clock: SyncClock,
        @ApplicationScope scope: CoroutineScope,
    ) : this(contact, network, sources, sessions, guard, scope, clock::elapsedMillis)

    /** When the last reconnect check was sent ([elapsedMillis]); null before the first. */
    @Volatile
    private var lastCheckAt: Long? = null

    override val offline: StateFlow<Boolean> = combine(sources.source, network.online, contact.state) { source, online, last ->
        offlineOf(source, online, last)
    }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, offlineOf(sources.source.value, network.online.value, contact.state.value))

    init {
        scope.launch {
            // collectLatest: a network that drops again during the pause cancels the waiting check.
            network.online.collectLatest { online ->
                if (online) {
                    delay(RECONNECT_DEBOUNCE_MILLIS)
                    checkAfterReconnect()
                }
            }
        }
    }

    override fun report(error: TandemError?) {
        val remote = sources.source.value as? ActiveSource.Remote ?: return
        contact.record(remote.accountId, error)
    }

    private suspend fun checkAfterReconnect() {
        val remote = sources.source.value as? ActiveSource.Remote ?: return
        if (!contact.state.value.unreachable(remote.accountId)) return
        lastCheckAt?.let { last ->
            val wait = MIN_CHECK_SPACING_MILLIS - (elapsedMillis() - last)
            if (wait > 0) delay(wait)
        }
        // The state may have changed while waiting: another request answered, or another account.
        if (sources.source.value != remote || !network.online.value || !contact.state.value.unreachable(remote.accountId)) return
        if (guard.check(remote.accountId, background = true) != null) {
            contact.forget(remote.accountId)
            return
        }
        lastCheckAt = elapsedMillis()
        val error = (sessions.get().refreshServerInfo() as? Outcome.Failure)?.error
        // Only for the account that is still shown; a logout during the check records nothing.
        if (sources.source.value == remote) contact.record(remote.accountId, error)
    }

    internal companion object {
        /** The network must stay up this long before the check is sent (flapping Wi-Fi, handovers). */
        const val RECONNECT_DEBOUNCE_MILLIS: Long = 3_000L

        /** At most one reconnect check per this long, however often the network comes back. */
        const val MIN_CHECK_SPACING_MILLIS: Long = 30_000L

        fun offlineOf(source: ActiveSource, online: Boolean, last: ServerContact.Contact): Boolean =
            source is ActiveSource.Remote && (!online || last.unreachable(source.accountId))
    }
}
