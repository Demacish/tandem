package casa.moller.tandem.core.sync.push

import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.model.push.FastPushAvailability
import casa.moller.tandem.core.model.push.FastPushError
import casa.moller.tandem.core.model.push.FastPushResult
import casa.moller.tandem.core.model.push.FastPushState
import casa.moller.tandem.core.model.push.FastPushStatus
import casa.moller.tandem.core.model.push.PushDistributor
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.testing.FakeSources
import casa.moller.tandem.core.sync.testing.SyncTestGraph
import casa.moller.tandem.core.sync.testing.TestTls
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.ConscryptMode

/**
 * Fas 4 (push lane): the app's side of the optional fast push (docs/BYGGE.md "Push-tjänst")
 * against an HTTPS MockWebServer standing in for push-service/ (API version 1) and a fake
 * UnifiedPush distributor: turning on, the endpoint's registration with the account's token, the
 * service's refusals, wake-ups and test wake-ups, other accounts, turning off, guests and demo.
 */
@RunWith(AndroidJUnit4::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class FastPushRepositoryTest {
    private val service = MockWebServer()
    private lateinit var graph: SyncTestGraph
    private val gateway = FakeDistributorGateway()
    private val wakes = FakeWakeScheduler()
    private val notifier = FakeFastPushNotifier()

    @Before
    fun setUp() {
        service.useHttps(TestTls.server.sslSocketFactory())
        service.protocolNegotiationEnabled = false
        service.start()
        graph = SyncTestGraph()
    }

    @After
    fun tearDown() {
        graph.close()
        service.close()
    }

    private val serviceUrl: String
        get() = service.url("/").toString().trimEnd('/')

    private fun repository() = DefaultFastPushRepository(
        sources = graph.sources,
        permissions = graph.permissions,
        store = FastPushStore(graph.databases),
        client = PushServiceClient(TestTls.httpClient()),
        gateway = gateway,
        scheduler = wakes,
        notifier = notifier,
        clock = graph.clock,
        scope = graph.scope,
    )

    private fun answer(code: Int, body: String = "") {
        service.enqueue(MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build())
    }

    private fun next(): RecordedRequest = requireNotNull(service.takeRequest(5, TimeUnit.SECONDS)) { "no request" }

    private suspend fun DefaultFastPushRepository.current(): FastPushState = withTimeout(5_000) { state.first() }

    private suspend fun DefaultFastPushRepository.turnedOn(): DefaultFastPushRepository {
        answer(200, INFO)
        assertEquals(FastPushResult.Success(Unit), enable(serviceUrl, DISTRIBUTOR))
        next() // /v1/info
        answer(200, """{"id":"reg-1","poll_seconds":45,"test":true}""")
        onNewEndpoint(SyncTestGraph.ACCOUNT_ID, ENDPOINT, "BPublicKey", "authSecret")
        next() // /v1/register
        return this
    }

    @Test
    fun turningOnChecksTheServiceAndAsksTheDistributor() = runBlocking {
        val repo = repository()
        assertEquals(FastPushStatus.OFF, repo.current().status)
        assertEquals(FastPushAvailability.AVAILABLE, repo.current().availability)

        answer(200, INFO)
        assertEquals(FastPushResult.Success(Unit), repo.enable("$serviceUrl/", DISTRIBUTOR))

        val info = next()
        assertEquals("GET", info.method)
        assertEquals("/v1/info", info.url.encodedPath)
        assertNull("no token for the info request", info.headers["Authorization"])
        assertEquals(listOf(SyncTestGraph.ACCOUNT_ID to DISTRIBUTOR), gateway.registered)
        val state = repo.current()
        assertEquals(FastPushStatus.REGISTERING, state.status)
        assertEquals(serviceUrl, state.serviceUrl)
        assertEquals(DISTRIBUTOR, state.distributor)
    }

    @Test
    fun theEndpointIsRegisteredWithTheAccountsTokenAndPushBecomesActive() = runBlocking {
        val repo = repository()
        answer(200, INFO)
        repo.enable(serviceUrl, DISTRIBUTOR)
        next()
        answer(200, """{"id":"reg-1","poll_seconds":45,"test":true}""")

        repo.onNewEndpoint(SyncTestGraph.ACCOUNT_ID, ENDPOINT, "BPublicKey", "authSecret")

        val register = next()
        assertEquals("POST", register.method)
        assertEquals("/v1/register", register.url.encodedPath)
        assertEquals("Bearer ${FakeSources.TOKEN}", register.headers["Authorization"])
        val body = register.body?.utf8().orEmpty()
        assertTrue(body, body.contains(ENDPOINT) && body.contains("\"p256dh\":\"BPublicKey\"") && body.contains("\"auth\":\"authSecret\""))
        val state = repo.current()
        assertEquals(FastPushStatus.ACTIVE, state.status)
        assertEquals(45, state.pollSeconds)
        assertTrue(state.testSupported)
        assertNull(state.error)
    }

    @Test
    fun theServicesRefusalsAreShown() = runBlocking {
        val cases = listOf(
            Triple(401, """{"error":"unauthorized"}""", FastPushError.UNAUTHORIZED),
            Triple(403, """{"error":"other_household"}""", FastPushError.OTHER_HOUSEHOLD),
            Triple(403, """{"error":"guest"}""", FastPushError.GUEST),
            Triple(409, """{"error":"too_many"}""", FastPushError.TOO_MANY),
            Triple(429, """{"error":"rate_limited"}""", FastPushError.RATE_LIMITED),
            Triple(502, """{"error":"tandoor_unavailable"}""", FastPushError.SERVICE_ERROR),
        )
        val repo = repository()
        for ((code, body, error) in cases) {
            answer(200, INFO)
            repo.enable(serviceUrl, DISTRIBUTOR)
            next()
            answer(code, body)
            repo.onNewEndpoint(SyncTestGraph.ACCOUNT_ID, ENDPOINT, null, null)
            next()
            val state = repo.current()
            assertEquals("$code $body", FastPushStatus.FAILED, state.status)
            assertEquals("$code $body", error, state.error)
        }
    }

    @Test
    fun aWakeUpReadsTheListAndATestShowsTheTestNotification() = runBlocking {
        val repo = repository().turnedOn()

        repo.onMessage(SyncTestGraph.ACCOUNT_ID, """{"tandem":"sync","id":"reg-1"}""".toByteArray())
        assertEquals(1, wakes.count)
        assertEquals(0, notifier.tests)
        assertNotNull(repo.current().lastWakeUp)

        repo.onMessage(SyncTestGraph.ACCOUNT_ID, """{"tandem":"test","id":"reg-1"}""".toByteArray())
        assertEquals(1, wakes.count)
        assertEquals(1, notifier.tests)

        // A test with another registration's id is only a wake-up (no notification someone else asked for).
        repo.onMessage(SyncTestGraph.ACCOUNT_ID, """{"tandem":"test","id":"someone-else"}""".toByteArray())
        assertEquals(2, wakes.count)
        assertEquals(1, notifier.tests)
    }

    @Test
    fun messagesForAnotherAccountOrAfterTurningOffStopTheRegistration() = runBlocking {
        val repo = repository()
        repo.onMessage("another-account", """{"tandem":"sync","id":"x"}""".toByteArray())
        assertEquals(listOf("another-account"), gateway.unregistered)
        assertEquals(0, wakes.count)

        // The active account without fast push on: dropped at the distributor as well.
        repo.onMessage(SyncTestGraph.ACCOUNT_ID, """{"tandem":"sync","id":"x"}""".toByteArray())
        assertEquals(listOf("another-account", SyncTestGraph.ACCOUNT_ID), gateway.unregistered)
        assertEquals(0, wakes.count)
    }

    @Test
    fun sendTestAsksTheServiceWithTheRegistrationsId() = runBlocking {
        val repo = repository().turnedOn()
        answer(204)
        assertEquals(FastPushResult.Success(Unit), repo.sendTest())
        val test = next()
        assertEquals("/v1/test", test.url.encodedPath)
        val body = test.body?.utf8().orEmpty()
        assertTrue(body, body.contains("\"id\":\"reg-1\"") && body.contains(ENDPOINT))
        assertNull("the test needs no token", test.headers["Authorization"])

        answer(429, """{"error":"rate_limited"}""")
        assertEquals(FastPushResult.Failure(FastPushError.RATE_LIMITED), repo.sendTest())
    }

    @Test
    fun turningOffUnregistersAtTheServiceAndTheDistributorAndKeepsTheAddress() = runBlocking {
        val repo = repository().turnedOn()
        answer(204)
        assertEquals(FastPushResult.Success(Unit), repo.disable())
        val unregister = next()
        assertEquals("/v1/unregister", unregister.url.encodedPath)
        assertTrue(unregister.body?.utf8().orEmpty().contains("\"id\":\"reg-1\""))
        assertEquals(listOf(SyncTestGraph.ACCOUNT_ID), gateway.unregistered)
        val state = repo.current()
        assertEquals(FastPushStatus.OFF, state.status)
        assertEquals(serviceUrl, state.serviceUrl)
        assertEquals(DISTRIBUTOR, state.distributor)
    }

    @Test
    fun turningOnIsRefusedForBadAddressesOtherServicesAndWithoutADistributor() = runBlocking {
        val repo = repository()
        assertEquals(FastPushResult.Failure(FastPushError.INVALID_URL), repo.enable("http://push.example.com", DISTRIBUTOR))
        assertEquals(FastPushResult.Failure(FastPushError.INVALID_URL), repo.enable("https://user:pw@push.example.com", DISTRIBUTOR))
        assertEquals(0, service.requestCount)

        answer(200, """{"service":"something-else","api":1}""")
        assertEquals(FastPushResult.Failure(FastPushError.NOT_A_PUSH_SERVICE), repo.enable(serviceUrl, DISTRIBUTOR))
        next()

        answer(200, INFO)
        assertEquals(FastPushResult.Failure(FastPushError.NO_DISTRIBUTOR), repo.enable(serviceUrl, "org.example.missing"))
        next()
        assertEquals(FastPushStatus.OFF, repo.current().status)
        assertTrue(gateway.registered.isEmpty())
    }

    @Test
    fun guestsDemoAndLoggedOutCannotUseIt() = runBlocking {
        val repo = repository()
        graph.permissions.setGuest(true)
        assertEquals(FastPushAvailability.GUEST, repo.current().availability)
        assertEquals(FastPushResult.Failure(FastPushError.GUEST), repo.enable(serviceUrl, DISTRIBUTOR))
        assertEquals(0, service.requestCount)

        graph.permissions.setGuest(false)
        graph.sources.source.value = ActiveSource.Demo
        assertEquals(FastPushAvailability.DEMO, repo.current().availability)
        graph.sources.source.value = ActiveSource.None
        assertEquals(FastPushAvailability.NO_ACCOUNT, repo.current().availability)
    }

    @Test
    fun aWakeUpReadsTheShoppingList() = runBlocking {
        val wakeUp = PushWakeUp(graph.sources, graph.shoppingSync) { }
        wakeUp.run()
        assertTrue(graph.server.requests.toString(), graph.server.requestsTo("GET", "/api/shopping-list-entry/").isNotEmpty())

        graph.server.requests.clear()
        graph.sources.source.value = ActiveSource.Demo
        wakeUp.run()
        assertTrue("demo mode never reads", graph.server.requests.isEmpty())
    }

    @Test
    fun serviceAddressesAreNormalized() {
        assertEquals("https://push.example.com", PushServiceClient.normalize(" https://push.example.com/ "))
        assertEquals("https://example.com/tandem-push", PushServiceClient.normalize("https://example.com/tandem-push"))
        assertNull(PushServiceClient.normalize("push.example.com"))
        assertNull(PushServiceClient.normalize("http://push.example.com"))
        assertNull(PushServiceClient.normalize("https://push.example.com/?x=1"))
    }

    private companion object {
        const val DISTRIBUTOR = "org.example.distributor"
        const val ENDPOINT = "https://ntfy.example.com/upAbc123?up=1"
        const val INFO = """{"service":"tandem-push","version":"1.0.0","api":1,"test":true,"encryption":true,"poll_seconds":45}"""
    }
}

/** A distributor app that is installed and records what it is asked. */
internal class FakeDistributorGateway(
    var installed: List<PushDistributor> = listOf(PushDistributor("org.example.distributor", "Example push")),
) : PushDistributorGateway {
    val registered: MutableList<Pair<String, String>> = CopyOnWriteArrayList()
    val unregistered: MutableList<String> = CopyOnWriteArrayList()

    override fun distributors(): List<PushDistributor> = installed

    override fun register(instance: String, distributor: String) {
        registered += instance to distributor
    }

    override fun unregister(instance: String) {
        unregistered += instance
    }
}

internal class FakeWakeScheduler : PushWakeScheduler {
    @Volatile
    var count = 0

    override fun wake() {
        count++
    }
}

internal class FakeFastPushNotifier : FastPushNotifier {
    @Volatile
    var tests = 0

    override fun postTest(): Boolean {
        tests++
        return true
    }
}
