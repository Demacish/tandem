package casa.moller.tandem.core.sync.calendar

import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.data.DataProblem
import casa.moller.tandem.core.data.dataProblem
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.mealplan.MealCalendarLinkBlock
import casa.moller.tandem.core.network.api.TandoorApiFactory
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.pantry.PantrySyncEnv
import casa.moller.tandem.core.sync.reminder.ReminderClock
import casa.moller.tandem.core.sync.testing.FakeTandoorServer
import casa.moller.tandem.core.sync.testing.SyncTestGraph
import casa.moller.tandem.core.sync.testing.TestTls
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import mockwebserver3.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.ConscryptMode

/**
 * Fas 5 (kalender lane, plan §3.1, §7 #36): the subscription link carries a token of its OWN with
 * the scope `mealplan` (never the account's token), in the address as `?access_token=` (FACTS "Fas
 * 5: matsedeln i kalendern"); "Byt länk" revokes the old token after the new one exists, "Stäng av"
 * revokes it and keeps the link when that fails offline; the token is renewed in place before it
 * expires; guests and demo mode get no link.
 */
@RunWith(AndroidJUnit4::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class MealCalendarLinksTest {
    private val stockholm = TimeZone.of("Europe/Stockholm")
    private val graph = SyncTestGraph()
    private val clock = ReminderClock(LocalDateTime.parse("2026-11-13T12:00").toInstant(stockholm), stockholm)
    private val links = MealCalendarLinks(graph.sources, graph.databases, graph.caller, clock, TandoorApiFactory(TestTls.httpClient()))
    private val nextId = AtomicLong(40)
    private val created: MutableList<String> = CopyOnWriteArrayList()

    init {
        graph.server.respond("POST", "/api/access-token/") { request ->
            created += request.body?.utf8().orEmpty()
            val id = nextId.incrementAndGet()
            val token = "tda_00000000_0000_0000_0000_0000000000$id"
            FakeTandoorServer.json(201, """{"id":$id,"token":"$token","expires":"2027-11-13T12:00:00+01:00","scope":"mealplan"}""")
        }
        graph.server.respond("DELETE", "/api/access-token/41/") { MockResponse.Builder().code(204).build() }
        graph.server.respond("DELETE", "/api/access-token/42/") { MockResponse.Builder().code(204).build() }
    }

    @After
    fun tearDown() {
        graph.close()
    }

    @Test
    fun enablingCreatesAMealplanTokenOfItsOwnAndBuildsTheAddress() = runBlocking {
        assertNull(links.observe().first().url)
        assertEquals(Outcome.Success(Unit), links.enable())
        val body = created.single()
        assertTrue(body, body.contains("\"scope\":\"mealplan\""))
        assertTrue(body, body.contains("\"expires\":\"2027-11-13T11:00:00Z\""))
        val link = links.observe().first()
        assertEquals("${graph.server.baseUrl}api/meal-plan/ical/?access_token=tda_00000000_0000_0000_0000_000000000041", link.url)
        assertEquals("webcal://" + link.url!!.removePrefix("https://"), link.webcalUrl)
        assertEquals(LocalDateTime.parse("2027-11-13T12:00").toInstant(stockholm), link.expires)
        // The account's own token never appears in the address.
        assertTrue(graph.sources.token!! !in link.url!!)
        // A second "on" asks for nothing.
        assertEquals(Outcome.Success(Unit), links.enable())
        assertEquals(1, created.size)
    }

    @Test
    fun replacingRevokesTheOldTokenAfterTheNewOne() = runBlocking {
        links.enable()
        assertEquals(Outcome.Success(Unit), links.replace())
        assertTrue(links.observe().first().url!!.endsWith("000000000042"))
        val order = graph.server.requests.filter { it.contains("/api/access-token/") }
        assertEquals(listOf("POST /api/access-token/", "POST /api/access-token/", "DELETE /api/access-token/41/"), order)
    }

    @Test
    fun turningOffRevokesTheTokenAndOfflineKeepsTheLink() = runBlocking {
        links.enable()
        graph.server.respond("DELETE", "/api/access-token/41/") { FakeTandoorServer.json(502, "") }
        assertTrue(links.disable() is Outcome.Failure)
        assertTrue(links.observe().first().isOn)
        graph.server.respond("DELETE", "/api/access-token/41/") { MockResponse.Builder().code(204).build() }
        assertEquals(Outcome.Success(Unit), links.disable())
        assertNull(links.observe().first().url)
        // A token the server already forgot (404) counts as revoked.
        links.enable()
        val gone = """{"detail":"No AccessToken matches the given query."}"""
        graph.server.respond("DELETE", "/api/access-token/42/") { FakeTandoorServer.json(404, gone) }
        assertEquals(Outcome.Success(Unit), links.disable())
        assertNull(links.observe().first().url)
    }

    @Test
    fun theTokenIsRenewedInPlaceBeforeItExpires() = runBlocking {
        val patched: MutableList<String> = CopyOnWriteArrayList()
        graph.server.respond("PATCH", "/api/access-token/41/") { request ->
            patched += request.body?.utf8().orEmpty()
            val token = "tda_00000000_0000_0000_0000_000000000041"
            FakeTandoorServer.json(200, """{"id":41,"token":"$token","expires":"2028-09-14T12:00:00+02:00","scope":"mealplan"}""")
        }
        links.enable()
        val address = links.observe().first().url
        assertEquals(Outcome.Success(Unit), links.renewIfDue())
        assertTrue(patched.isEmpty())
        // 310 days later: 55 days left, renewed for a year from then; the address stays.
        clock.instant += 310.days
        assertEquals(Outcome.Success(Unit), links.renewIfDue())
        assertTrue(patched.single(), patched.single().contains("\"expires\":\"2028-09-18T11:00:00Z\""))
        assertEquals(address, links.observe().first().url)
        assertEquals(LocalDateTime.parse("2028-09-14T12:00").toInstant(stockholm), links.observe().first().expires)
    }

    @Test
    fun theAccountLeavingThePhoneRevokesItsLinkWithTheAccountsToken() = runBlocking {
        links.enable()
        // "Byt länk" while the server failed left token 41 waiting; both go when the account leaves.
        graph.server.respond("DELETE", "/api/access-token/41/") { FakeTandoorServer.json(502, "") }
        links.replace()
        graph.server.respond("DELETE", "/api/access-token/41/") { MockResponse.Builder().code(204).build() }
        graph.server.requests.clear()

        links.beforeRemoval(graph.account, graph.sources.token!!)

        val deletes = graph.server.requests.filter { it.startsWith("DELETE") }
        assertEquals(listOf("DELETE /api/access-token/41/", "DELETE /api/access-token/42/"), deletes)
    }

    @Test
    fun anAccountWithoutALinkLeavesWithoutARequest() = runBlocking {
        links.beforeRemoval(graph.account, graph.sources.token!!)
        assertTrue(graph.server.requests.none { it.contains("access-token") })
    }

    @Test
    fun guestsAndDemoModeGetNoLink() = runBlocking {
        PantrySyncEnv.mirror(graph, groupLevel = "guest")
        assertEquals(MealCalendarLinkBlock.GUEST, links.observe().first().block)
        val refused = links.enable()
        assertEquals(DataProblem.READ_ONLY, (refused as Outcome.Failure).error.dataProblem())
        assertTrue(created.isEmpty())

        graph.sources.source.value = ActiveSource.Demo
        assertEquals(DataProblem.NO_ACCOUNT, (links.enable() as Outcome.Failure).error.dataProblem())
        assertNull(links.observe().first().block)
        assertTrue(graph.server.requests.none { it.contains("access-token") })
    }

    @Test
    fun aBrokenRowReadsAsNoLink() {
        assertNull(MealCalendarLinks.decode("{not json"))
        assertNull(MealCalendarLinks.decode(null))
        val stored = MealCalendarLinks.StoredLink(id = 1, token = "tda_x")
        assertTrue("tda_x" !in stored.toString())
    }
}
