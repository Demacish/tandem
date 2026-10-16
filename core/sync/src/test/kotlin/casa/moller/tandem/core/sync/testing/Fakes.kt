package casa.moller.tandem.core.sync.testing

import casa.moller.tandem.core.data.server.ServerInfo
import casa.moller.tandem.core.data.session.Session
import casa.moller.tandem.core.data.session.SessionRepository
import casa.moller.tandem.core.data.session.TokenRevocation
import casa.moller.tandem.core.datastore.SecureAccountStore
import casa.moller.tandem.core.model.account.ServerAccount
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.server.ServerCompatibility
import casa.moller.tandem.core.model.server.ServerVersionChange
import casa.moller.tandem.core.model.sync.SyncReason
import casa.moller.tandem.core.network.http.TokenProvider
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.account.ActiveSources
import casa.moller.tandem.core.sync.image.ImageCache
import casa.moller.tandem.core.sync.image.ImagePrefetcher
import casa.moller.tandem.core.sync.image.PrefetchedImages
import casa.moller.tandem.core.sync.platform.NetworkStatus
import casa.moller.tandem.core.sync.platform.SyncClock
import casa.moller.tandem.core.sync.platform.SyncScheduler
import casa.moller.tandem.core.sync.platform.VersionChangeNotifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn

/** The active source, set by the test. */
internal class FakeSources(initial: ActiveSource) : ActiveSources {
    override val source = MutableStateFlow(initial)

    /** The token every request carries; changing it is "a new login". */
    @Volatile
    var token: String? = TOKEN

    override fun tokenFingerprint(accountId: String): Int? = token?.hashCode()

    override suspend fun reloadToken(accountId: String) = Unit

    override fun tokenProvider(accountId: String): TokenProvider = TokenProvider { token }

    companion object {
        const val TOKEN = "tda_00000000_0000_0000_0000_000000000002"
    }
}

/** A clock the test moves: [today] at 12:00 UTC plus [advance]d time. */
internal class FakeClock(today: LocalDate = TODAY) : SyncClock {
    @Volatile
    var millis: Long = today.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds() + NOON

    val days = MutableStateFlow(today)

    fun advance(duration: Duration) {
        millis += duration.inWholeMilliseconds
    }

    override fun nowMillis(): Long = millis

    override fun today(): LocalDate = days.value

    override fun days(): Flow<LocalDate> = days

    override fun ticks(periodMillis: Long): Flow<Unit> = flowOf(Unit)

    companion object {
        val TODAY: LocalDate = LocalDate(2026, 10, 6)
        private const val NOON = 12L * 60 * 60 * 1000
    }
}

internal class FakeNetwork(online: Boolean = true) : NetworkStatus {
    override val online = MutableStateFlow(online)
}

internal class FakeScheduler : SyncScheduler {
    val requested: MutableList<SyncReason> = CopyOnWriteArrayList()
    val calls: MutableList<String> = CopyOnWriteArrayList()

    override fun requestSync(reason: SyncReason) {
        requested += reason
        calls += "sync:$reason"
    }

    override fun scheduleOutbox() {
        calls += "outbox"
    }

    override fun schedulePeriodic() {
        calls += "periodic"
    }

    override fun cancelPeriodic() {
        calls += "cancelPeriodic"
    }
}

/** Records the requested URLs; URLs in [failing] fail temporarily (every other one is settled). */
internal class FakeImagePrefetcher : ImagePrefetcher {
    val urls: MutableList<String> = CopyOnWriteArrayList()
    val failing: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override suspend fun prefetch(urls: List<String>): Set<String> {
        this.urls += urls
        return urls.filterTo(LinkedHashSet()) { it !in failing }
    }
}

/** Counts how often the image caches were emptied (and empties the shared prefetch memory like the real one). */
internal class FakeImageCache(private val prefetched: PrefetchedImages) : ImageCache {
    val clears = AtomicInteger()

    override suspend fun clear() {
        prefetched.clear()
        clears.incrementAndGet()
    }
}

internal class FakeNotifier : VersionChangeNotifier {
    val changes: MutableList<ServerVersionChange> = CopyOnWriteArrayList()

    override fun onVersionChanged(change: ServerVersionChange) {
        changes += change
    }
}

/** A session the test sets; [refreshServerInfo] answers [serverVersion]. */
internal class FakeSessionRepository(initial: Session) : SessionRepository {
    val sessionState = MutableStateFlow(initial)

    @Volatile
    var serverVersion: String = "2.6.13"
    val refreshes = AtomicInteger()

    override val session: Flow<Session> = sessionState

    override suspend fun refreshServerInfo(): Outcome<ServerInfo> {
        refreshes.incrementAndGet()
        return Outcome.Success(ServerInfo("https://tandoor.example.com/", serverVersion, ServerCompatibility.SUPPORTED))
    }

    override suspend fun logout(revokeToken: Boolean): Outcome<TokenRevocation> = Outcome.Success(TokenRevocation.SKIPPED)

    override suspend fun startDemo(): Outcome<Unit> = Outcome.Success(Unit)

    override suspend fun leaveDemo(): Outcome<Unit> = Outcome.Success(Unit)

    override fun acknowledgeRecovery() = Unit

    override suspend fun clearAppData(): Outcome<Unit> = Outcome.Success(Unit)
}

/** Accounts kept in memory; tokens are never read by these tests. */
internal class FakeAccountStore(initial: List<ServerAccount>) : SecureAccountStore {
    val state = MutableStateFlow(initial)

    override val accounts: StateFlow<List<ServerAccount>> = state

    override suspend fun upsert(account: ServerAccount, token: String) {
        state.value = state.value.filterNot { it.id == account.id } + account
    }

    override suspend fun update(account: ServerAccount): Boolean {
        upsert(account, "")
        return true
    }

    override suspend fun token(accountId: String): String? = null

    override suspend fun remove(accountId: String) {
        state.value = state.value.filterNot { it.id == accountId }
    }

    override suspend fun clearAll() {
        state.value = emptyList()
    }
}
