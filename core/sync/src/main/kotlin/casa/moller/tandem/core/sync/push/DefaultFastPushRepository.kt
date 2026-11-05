package casa.moller.tandem.core.sync.push

import casa.moller.tandem.core.data.di.ApplicationScope
import casa.moller.tandem.core.data.permissions.SpacePermissionsRepository
import casa.moller.tandem.core.data.push.FastPushRepository
import casa.moller.tandem.core.model.push.FastPushAvailability
import casa.moller.tandem.core.model.push.FastPushError
import casa.moller.tandem.core.model.push.FastPushResult
import casa.moller.tandem.core.model.push.FastPushServiceInfo
import casa.moller.tandem.core.model.push.FastPushState
import casa.moller.tandem.core.model.push.FastPushStatus
import casa.moller.tandem.core.model.push.PushDistributor
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.account.ActiveSources
import casa.moller.tandem.core.sync.platform.SyncClock
import casa.moller.tandem.core.sync.shopping.ShoppingSync
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * What the UnifiedPush service of the app ([TandemPushService]) hands over. Every call is for the
 * registration `instance` = the account id.
 */
internal interface FastPushReceiver {
    suspend fun onNewEndpoint(instance: String, endpoint: String, p256dh: String?, auth: String?)

    suspend fun onMessage(instance: String, content: ByteArray)

    suspend fun onRegistrationFailed(instance: String)

    suspend fun onUnregistered(instance: String)
}

/**
 * [FastPushRepository] (Fas 4, push lane; docs/BYGGE.md "Push-tjänst", docs/PUSH.md).
 *
 * - **Turning on:** check the service (`/v1/info`), save the address, the distributor and
 *   [FastPushStatus.REGISTERING], ask the distributor for an endpoint. The endpoint arrives in
 *   [onNewEndpoint]; it goes to the service with the account's in-memory Tandoor token (the service
 *   asks Tandoor for the household once and forgets the token), and the answer's opaque id is kept.
 * - **A wake-up** ([onMessage]) for the active account enqueues the shopping list's read
 *   ([PushWakeScheduler]); `{"tandem":"test"}` with the registration's id shows the test
 *   notification instead. Wake-ups for another account or after turning off unregister at the
 *   distributor, so they stop.
 * - **App start** ([start]): the active account's registration is renewed at the distributor (the
 *   endpoint comes again and is sent to the service, which keeps it "last seen"); switching or
 *   leaving the account drops the old one at the distributor.
 * - Demo mode, guests and logged-out: nothing (the state says why).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
internal class DefaultFastPushRepository @Inject constructor(
    private val sources: ActiveSources,
    private val permissions: SpacePermissionsRepository,
    private val store: FastPushStore,
    private val client: PushServiceClient,
    private val gateway: PushDistributorGateway,
    private val scheduler: PushWakeScheduler,
    private val notifier: FastPushNotifier,
    private val clock: SyncClock,
    @ApplicationScope private val scope: CoroutineScope,
) : FastPushRepository, FastPushReceiver {
    private val started = AtomicBoolean(false)

    override val state: Flow<FastPushState> = combine(
        sources.source.filter { it != ActiveSource.Loading },
        permissions.permissions.map { it.readOnly }.distinctUntilChanged(),
    ) { source, readOnly -> source to readOnly }
        .flatMapLatest { (source, readOnly) ->
            when (source) {
                ActiveSource.Demo -> flowOf(FastPushState(availability = FastPushAvailability.DEMO))

                is ActiveSource.Remote -> if (readOnly) {
                    flowOf(FastPushState(availability = FastPushAvailability.GUEST))
                } else {
                    store.observe(source).map { stored -> stateOf(stored ?: StoredFastPush()) }
                }

                else -> flowOf(FastPushState(availability = FastPushAvailability.NO_ACCOUNT))
            }
        }
        .distinctUntilChanged()

    override fun distributors(): List<PushDistributor> = gateway.distributors()

    override suspend fun check(serviceUrl: String): FastPushResult<FastPushServiceInfo> = client.info(serviceUrl)

    override suspend fun enable(serviceUrl: String, distributor: String): FastPushResult<Unit> {
        val remote = member() ?: return FastPushResult.Failure(unavailableError())
        val url = PushServiceClient.normalize(serviceUrl) ?: return FastPushResult.Failure(FastPushError.INVALID_URL)
        val info = when (val result = client.info(url)) {
            is FastPushResult.Failure -> return result
            is FastPushResult.Success -> result.value
        }
        if (gateway.distributors().none { it.packageName == distributor }) {
            return FastPushResult.Failure(FastPushError.NO_DISTRIBUTOR)
        }
        val previous = store.load(remote)
        store.save(
            remote,
            StoredFastPush(
                serviceUrl = url,
                distributor = distributor,
                status = FastPushStatus.REGISTERING,
                // The same endpoint at the same service keeps its id (the service answers it again).
                endpoint = previous?.endpoint.takeIf { previous?.serviceUrl == url },
                registrationId = previous?.registrationId.takeIf { previous?.serviceUrl == url },
                testSupported = info.testSupported,
                pollSeconds = info.pollSeconds.takeIf { it > 0 },
                lastWakeUpMillis = previous?.lastWakeUpMillis,
            ),
        )
        gateway.register(remote.accountId, distributor)
        return FastPushResult.Success(Unit)
    }

    override suspend fun disable(): FastPushResult<Unit> {
        val remote = sources.current() as? ActiveSource.Remote ?: return FastPushResult.Failure(unavailableError())
        val stored = store.load(remote) ?: StoredFastPush()
        val endpoint = stored.endpoint
        val id = stored.registrationId
        if (endpoint != null && id != null && stored.serviceUrl.isNotEmpty()) {
            // Best effort: the service also drops endpoints the distributor forgot (404/410).
            client.unregister(stored.serviceUrl, endpoint, id)
        }
        gateway.unregister(remote.accountId)
        store.save(remote, StoredFastPush(serviceUrl = stored.serviceUrl, distributor = stored.distributor))
        return FastPushResult.Success(Unit)
    }

    override suspend fun sendTest(): FastPushResult<Unit> {
        val remote = member() ?: return FastPushResult.Failure(unavailableError())
        val stored = store.load(remote)
        val endpoint = stored?.endpoint
        val id = stored?.registrationId
        if (stored?.status != FastPushStatus.ACTIVE || endpoint == null || id == null) {
            return FastPushResult.Failure(FastPushError.SERVICE_ERROR)
        }
        if (!stored.testSupported) return FastPushResult.Failure(FastPushError.TEST_UNSUPPORTED)
        return client.test(stored.serviceUrl, endpoint, id)
    }

    // --- FastPushReceiver ---

    override suspend fun onNewEndpoint(instance: String, endpoint: String, p256dh: String?, auth: String?) {
        val remote = activeFor(instance) ?: return
        val stored = store.load(remote)?.takeIf { it.wanted } ?: return dropRegistration(instance)
        val token = sources.tokenProvider(remote.accountId).currentToken()
        if (token.isNullOrBlank()) {
            store.save(remote, stored.copy(status = FastPushStatus.FAILED, error = FastPushError.UNAUTHORIZED))
            return
        }
        // Both Web Push keys or none (the service encrypts only with both).
        val keys = if (!p256dh.isNullOrBlank() && !auth.isNullOrBlank()) p256dh to auth else null
        val next = when (val result = client.register(stored.serviceUrl, token, endpoint, keys?.first, keys?.second)) {
            is FastPushResult.Success -> stored.copy(
                status = FastPushStatus.ACTIVE,
                endpoint = endpoint,
                p256dh = keys?.first,
                auth = keys?.second,
                registrationId = result.value.id,
                testSupported = result.value.testSupported,
                pollSeconds = result.value.pollSeconds ?: stored.pollSeconds,
                error = null,
            )

            is FastPushResult.Failure -> stored.copy(status = FastPushStatus.FAILED, endpoint = endpoint, error = result.error)
        }
        store.save(remote, next)
    }

    override suspend fun onMessage(instance: String, content: ByteArray) {
        val remote = activeFor(instance) ?: return
        val stored = store.load(remote)?.takeIf { it.wanted } ?: return dropRegistration(instance)
        val wakeUp = parse(content)
        store.save(remote, stored.copy(lastWakeUpMillis = clock.nowMillis()))
        if (wakeUp?.tandem == KIND_TEST && wakeUp.id != null && wakeUp.id == stored.registrationId) {
            notifier.postTest()
        } else {
            // Anything else on the account's registration is a "sync now"; the read is cheap and
            // unique work, so even a stray message costs at most one shopping list read.
            scheduler.wake()
        }
    }

    override suspend fun onRegistrationFailed(instance: String) {
        val remote = activeFor(instance) ?: return
        val stored = store.load(remote)?.takeIf { it.wanted } ?: return
        store.save(remote, stored.copy(status = FastPushStatus.FAILED, error = FastPushError.DISTRIBUTOR_FAILED))
    }

    override suspend fun onUnregistered(instance: String) {
        val remote = activeFor(instance) ?: return
        val stored = store.load(remote)?.takeIf { it.wanted } ?: return
        // The distributor dropped us (removed, or its own setting): the user has to turn it on again.
        store.save(remote, stored.copy(status = FastPushStatus.FAILED, error = FastPushError.NO_DISTRIBUTOR))
    }

    // --- App start ---

    /**
     * Follows the active account (called once per process by `SyncStarter`): renews the account's
     * registration at the distributor, drops the previous account's.
     */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            var registered: String? = null
            sources.source
                .filter { it != ActiveSource.Loading }
                .map { it as? ActiveSource.Remote }
                .distinctUntilChanged { old, new -> old?.accountId == new?.accountId }
                .collect { remote ->
                    val previous = registered
                    if (previous != null && previous != remote?.accountId) {
                        gateway.unregister(previous)
                        registered = null
                    }
                    if (remote == null) return@collect
                    val stored = store.load(remote)?.takeIf { it.wanted } ?: return@collect
                    val distributor = stored.distributor ?: return@collect
                    if (gateway.distributors().none { it.packageName == distributor }) {
                        store.save(remote, stored.copy(status = FastPushStatus.FAILED, error = FastPushError.NO_DISTRIBUTOR))
                        return@collect
                    }
                    gateway.register(remote.accountId, distributor)
                    registered = remote.accountId
                }
        }
    }

    private suspend fun member(): ActiveSource.Remote? {
        val remote = sources.current() as? ActiveSource.Remote ?: return null
        return remote.takeIf { !permissions.permissions.first().readOnly }
    }

    private suspend fun unavailableError(): FastPushError =
        if (sources.current() is ActiveSource.Remote) FastPushError.GUEST else FastPushError.UNAUTHORIZED

    /** The active account when it is [instance]; otherwise the registration is dropped. */
    private suspend fun activeFor(instance: String): ActiveSource.Remote? {
        val remote = sources.current() as? ActiveSource.Remote
        if (remote == null || remote.accountId != instance) {
            dropRegistration(instance)
            return null
        }
        return remote
    }

    private fun dropRegistration(instance: String) {
        gateway.unregister(instance)
    }

    private fun stateOf(stored: StoredFastPush) = FastPushState(
        availability = FastPushAvailability.AVAILABLE,
        serviceUrl = stored.serviceUrl,
        distributor = stored.distributor,
        status = stored.status,
        testSupported = stored.testSupported,
        pollSeconds = stored.pollSeconds,
        lastWakeUp = stored.lastWakeUpMillis?.let(Instant::fromEpochMilliseconds),
        error = stored.error.takeIf { stored.status == FastPushStatus.FAILED },
    )

    @Serializable
    private data class WakeUp(val tandem: String? = null, val id: String? = null)

    private fun parse(content: ByteArray): WakeUp? = try {
        json.decodeFromString(WakeUp.serializer(), content.decodeToString())
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    private companion object {
        const val KIND_TEST = "test"
        val json = Json { ignoreUnknownKeys = true }
    }
}

/** Reads the shopping list after a wake-up (the work of [casa.moller.tandem.core.sync.work.PushSyncWorker]). */
@Singleton
internal class PushWakeUp @Inject constructor(
    private val sources: ActiveSources,
    private val sync: ShoppingSync,
    private val wait: PushWakeWait,
) {
    /** One full read of the list (a deletion is invisible to a delta), then time for the notification's batch. */
    suspend fun run() {
        val remote = sources.current() as? ActiveSource.Remote ?: return
        sync.refresh(remote, background = true, full = true)
        // ShoppingChangeNotifications gathers changes for 3 s before it posts: keep the worker (and
        // so the process) alive that long.
        wait.afterRead()
    }
}

/** The pause after a wake-up's read (tests skip it). */
internal fun interface PushWakeWait {
    suspend fun afterRead()
}
