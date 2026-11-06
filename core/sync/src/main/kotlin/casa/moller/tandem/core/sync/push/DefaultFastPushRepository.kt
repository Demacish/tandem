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
import java.util.concurrent.atomic.AtomicReference
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * What the UnifiedPush service of the app ([TandemPushService]) hands over. Every call is for the
 * registration `instance` = the account id.
 */
internal interface FastPushReceiver {
    suspend fun onNewEndpoint(instance: String, endpoint: String, p256dh: String?, auth: String?)

    /** [decrypted]: the connector could read the message with the registration's Web Push keys. */
    suspend fun onMessage(instance: String, content: ByteArray, decrypted: Boolean)

    suspend fun onRegistrationFailed(instance: String)

    suspend fun onUnregistered(instance: String)

    /** [PushRetryScheduler]'s work: asks the distributor again when a registration failed for a passing reason. */
    suspend fun retryRegistration()
}

/**
 * [FastPushRepository] (Fas 4, push lane; docs/BYGGE.md "Push-tjänst", docs/PUSH.md).
 *
 * - **Turning on:** check the service (`/v1/info`), save the address, the distributor and
 *   [FastPushStatus.REGISTERING], ask the distributor for an endpoint. The endpoint arrives in
 *   [onNewEndpoint]; it goes to the service with the account's in-memory Tandoor token (the service
 *   asks Tandoor for the household once and forgets the token), and the answer's opaque id is kept.
 * - **A wake-up** ([onMessage]) for the active account enqueues the shopping list's read
 *   ([PushWakeScheduler]); `{"tandem":"test"}` shows the test notification instead. Wake-ups for
 *   another account or after turning off unregister at the distributor, so they stop.
 *   Fas 4 review (fix-data): the endpoint is no secret (the distributor's server has it, a public
 *   ntfy topic is a URL), so a message counts only when it carries the registration's id, which
 *   travels inside the RFC 8291 encryption, and could be decrypted (a registration made without
 *   keys has no encryption to ask for). Reads at most every [MIN_WAKE_INTERVAL_MILLIS], kept in
 *   the row across process deaths, so a replayed message costs no more than the service's own pace.
 * - **App start** ([start]): the active account's registration is renewed at the distributor (the
 *   endpoint comes again). Fas 4 review (fix-data): the same endpoint is renewed at the service
 *   with the registration's id alone, so the token leaves the phone only when turning on or when
 *   the address changed. A renewal that fails for a passing reason changes nothing: the service
 *   still has the phone. A new address or a first registration that fails that way is tried again
 *   ([PushRetryScheduler]); until then the old address and id stay, so they can be unregistered.
 * - **The distributor dropped us** ([onUnregistered]): the service is told, and fast push stays
 *   off until the user turns it on again.
 * - A registration answered after turning off or leaving the account is undone, never saved
 *   (state changes run under [rows], never across a request).
 * - **Leaving the account** (another account, logout, demo mode): its registration is dropped at
 *   the distributor and, as well as it can be, at the service ([Held]): an ntfy topic nobody
 *   listens to still answers 200, so the service would otherwise keep waking it for 60 days.
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
    private val retry: PushRetryScheduler,
    @ApplicationScope private val scope: CoroutineScope,
) : FastPushRepository,
    FastPushReceiver {
    private val started = AtomicBoolean(false)

    /** Serializes reading, changing and saving the row; never held across a request. */
    private val rows = Mutex()

    /**
     * The registration this process asked for. Kept in memory because the account's stored row may
     * be gone when it is left: logging out deletes the account's rows at the same moment (Fas 4,
     * integration; before, a registration turned on in this process was never dropped at all).
     */
    private data class Held(val accountId: String, val serviceUrl: String, val endpoint: String?, val id: String?)

    private val held = AtomicReference<Held?>(null)

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
        val registering = rows.withLock {
            val previous = store.load(remote)
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
                lastSyncWakeMillis = previous?.lastSyncWakeMillis,
            ).also { store.save(remote, it) }
        }
        held.set(Held(remote.accountId, url, registering.endpoint, registering.registrationId))
        gateway.register(remote.accountId, distributor)
        return FastPushResult.Success(Unit)
    }

    override suspend fun disable(): FastPushResult<Unit> {
        val remote = sources.current() as? ActiveSource.Remote ?: return FastPushResult.Failure(unavailableError())
        // Off first: a registration answered from now on sees it and is undone (onNewEndpoint).
        val stored = rows.withLock {
            val row = store.load(remote) ?: StoredFastPush()
            store.save(remote, StoredFastPush(serviceUrl = row.serviceUrl, distributor = row.distributor))
            row
        }
        held.set(null)
        unregisterAtService(stored)
        gateway.unregister(remote.accountId)
        return FastPushResult.Success(Unit)
    }

    /** Best effort: the service also drops endpoints the distributor forgot (404/410). */
    private suspend fun unregisterAtService(stored: StoredFastPush) {
        val endpoint = stored.endpoint
        val id = stored.registrationId
        if (endpoint != null && id != null && stored.serviceUrl.isNotEmpty()) client.unregister(stored.serviceUrl, endpoint, id)
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
        val asked = store.load(remote)?.takeIf { it.wanted } ?: return dropRegistration(instance)
        // Both Web Push keys or none (the service encrypts only with both).
        val keys = if (!p256dh.isNullOrBlank() && !auth.isNullOrBlank()) p256dh to auth else null
        val result = register(remote, asked, endpoint, keys)
        val settled = rows.withLock {
            val current = stillWanted(remote, asked) ?: return@withLock null
            current.after(result, endpoint, keys).also { next ->
                store.save(remote, next)
                held.set(Held(remote.accountId, next.serviceUrl, next.endpoint, next.registrationId))
            }
        }
        if (settled == null) {
            // Turned off, or the account left, during the request: nothing of it may stay.
            if (result is FastPushResult.Success) client.unregister(asked.serviceUrl, endpoint, result.value.id)
            gateway.unregister(instance)
            return
        }
        if (result is FastPushResult.Success) {
            // A new address replaced the old one: the service would keep waking it until its TTL.
            if (asked.endpoint != endpoint) unregisterAtService(asked)
        } else if (settled.status == FastPushStatus.FAILED && settled.error in PASSING_ERRORS) {
            retry.retryLater()
        }
    }

    /**
     * The endpoint's registration at the service: by the registration's id when the service has
     * this endpoint (no token), and with the token for a new endpoint or when the service has
     * forgotten the phone (it asks for the token then: 401).
     */
    private suspend fun register(
        remote: ActiveSource.Remote,
        asked: StoredFastPush,
        endpoint: String,
        keys: Pair<String, String>?,
    ): FastPushResult<PushServiceClient.Registered> {
        val id = asked.registrationId.takeIf { asked.endpoint == endpoint }
        if (id != null) {
            val renewed = client.renew(asked.serviceUrl, endpoint, id, keys?.first, keys?.second)
            if ((renewed as? FastPushResult.Failure)?.error != FastPushError.UNAUTHORIZED) return renewed
        }
        val token = sources.tokenProvider(remote.accountId).currentToken()
        if (token.isNullOrBlank()) return FastPushResult.Failure(FastPushError.UNAUTHORIZED)
        return client.register(asked.serviceUrl, token, endpoint, keys?.first, keys?.second)
    }

    /** The row now, unless fast push was turned off, moved to another service or the account left since [asked] was read. */
    private suspend fun stillWanted(remote: ActiveSource.Remote, asked: StoredFastPush): StoredFastPush? {
        if ((sources.current() as? ActiveSource.Remote)?.accountId != remote.accountId) return null
        return store.load(remote)?.takeIf { it.wanted && it.serviceUrl == asked.serviceUrl }
    }

    private fun StoredFastPush.after(
        result: FastPushResult<PushServiceClient.Registered>,
        endpoint: String,
        keys: Pair<String, String>?,
    ): StoredFastPush = when (result) {
        is FastPushResult.Success -> copy(
            status = FastPushStatus.ACTIVE,
            endpoint = endpoint,
            p256dh = keys?.first,
            auth = keys?.second,
            registrationId = result.value.id,
            testSupported = result.value.testSupported,
            pollSeconds = result.value.pollSeconds ?: pollSeconds,
            error = null,
        )

        // The service still has this phone: only "last seen" was not moved.
        is FastPushResult.Failure -> if (isRegistered(endpoint, keys) && result.error in PASSING_ERRORS) {
            this
        } else {
            // The old address and id stay: they are what can be unregistered.
            copy(status = FastPushStatus.FAILED, error = result.error)
        }
    }

    private fun StoredFastPush.isRegistered(endpoint: String, keys: Pair<String, String>?): Boolean = status == FastPushStatus.ACTIVE &&
        registrationId != null && this.endpoint == endpoint && p256dh == keys?.first && auth == keys?.second

    override suspend fun onMessage(instance: String, content: ByteArray, decrypted: Boolean) {
        val remote = activeFor(instance) ?: return
        val test = rows.withLock {
            val stored = store.load(remote)?.takeIf { it.wanted } ?: return dropRegistration(instance)
            if (!decrypted && stored.p256dh != null) return
            val wakeUp = parse(content)
            if (wakeUp?.id == null || wakeUp.id != stored.registrationId) return
            val test = wakeUp.tandem == KIND_TEST
            val now = clock.nowMillis()
            if (!test && stored.lastSyncWakeMillis?.let { now - it in 0 until MIN_WAKE_INTERVAL_MILLIS } == true) return
            store.save(remote, stored.copy(lastWakeUpMillis = now, lastSyncWakeMillis = if (test) stored.lastSyncWakeMillis else now))
            test
        }
        if (test) notifier.postTest() else scheduler.wake()
    }

    override suspend fun onRegistrationFailed(instance: String) {
        val remote = activeFor(instance) ?: return
        rows.withLock {
            val stored = store.load(remote)?.takeIf { it.wanted } ?: return
            store.save(remote, stored.copy(status = FastPushStatus.FAILED, error = FastPushError.DISTRIBUTOR_FAILED))
        }
    }

    override suspend fun onUnregistered(instance: String) {
        val remote = activeFor(instance) ?: return
        distributorGone(remote)
    }

    /**
     * The distributor dropped us (removed, or its own setting): the user has to turn it on again.
     * The service is told as well as it can be: the address may keep answering 200 with nobody
     * listening, and it would keep being woken until its TTL.
     */
    private suspend fun distributorGone(remote: ActiveSource.Remote) {
        val stored = rows.withLock {
            val stored = store.load(remote)?.takeIf { it.wanted } ?: return
            store.save(
                remote,
                stored.copy(
                    status = FastPushStatus.FAILED,
                    error = FastPushError.NO_DISTRIBUTOR,
                    endpoint = null,
                    p256dh = null,
                    auth = null,
                    registrationId = null,
                ),
            )
            stored
        }
        held.set(null)
        unregisterAtService(stored)
    }

    override suspend fun retryRegistration() {
        val remote = sources.current() as? ActiveSource.Remote ?: return
        val stored = store.load(remote)?.takeIf { it.wanted } ?: return
        val distributor = stored.distributor ?: return
        if (stored.status == FastPushStatus.FAILED && stored.error in PASSING_ERRORS) gateway.register(remote.accountId, distributor)
    }

    // --- App start ---

    /**
     * Follows the active account (called once per process by `SyncStarter`): renews the account's
     * registration at the distributor, drops the previous account's ([leave]).
     */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            sources.source
                .filter { it != ActiveSource.Loading }
                .map { it as? ActiveSource.Remote }
                .distinctUntilChanged { old, new -> old?.accountId == new?.accountId }
                .collect { remote ->
                    leave(next = remote?.accountId)
                    if (remote == null) return@collect
                    val stored = store.load(remote)?.takeIf { it.wanted } ?: return@collect
                    val distributor = stored.distributor ?: return@collect
                    // Dropped at the distributor: that stands until the user turns it on again.
                    if (stored.status == FastPushStatus.FAILED && stored.error == FastPushError.NO_DISTRIBUTOR) return@collect
                    if (gateway.distributors().none { it.packageName == distributor }) {
                        distributorGone(remote)
                        return@collect
                    }
                    held.set(Held(remote.accountId, stored.serviceUrl, stored.endpoint, stored.registrationId))
                    gateway.register(remote.accountId, distributor)
                }
        }
    }

    /** [next] is active now: a registration of another account is dropped. Best effort at the service. */
    private suspend fun leave(next: String?) {
        val old = held.get()?.takeIf { it.accountId != next } ?: return
        held.set(null)
        gateway.unregister(old.accountId)
        if (old.endpoint != null && old.id != null) client.unregister(old.serviceUrl, old.endpoint, old.id)
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

        /** Just under the service's own minimum poll (30 s), so a genuine wake-up is never dropped. */
        const val MIN_WAKE_INTERVAL_MILLIS = 25_000L

        /** Failures that pass by themselves (no network, a service restarting or busy); the others need the user. */
        val PASSING_ERRORS = setOf(FastPushError.UNREACHABLE, FastPushError.SERVICE_ERROR, FastPushError.RATE_LIMITED)
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
