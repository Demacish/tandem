package casa.moller.tandem.core.sync.scan

import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.data.DataProblem
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.scan.BarcodeLookupResult
import casa.moller.tandem.core.model.scan.FoodAlias
import casa.moller.tandem.core.model.scan.FoodAliasKind
import casa.moller.tandem.core.network.openfoodfacts.OpenFoodFactsClient
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.pantry.PantrySyncEnv
import casa.moller.tandem.core.sync.testing.TestTls
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.flow.first
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.ConscryptMode

/**
 * Fas S (hemma-data lane): learned barcodes and receipt names as `FOOD_ALIAS` automations (plan
 * §8.8, FACTS "Fas S d") against a stateful Tandoor in the recorded shapes of 2.6.13 and 2.7.1, and
 * the barcode lookup (alias, cache, Open Food Facts on an HTTPS MockWebServer; never the real service).
 */
@RunWith(AndroidJUnit4::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ScanDataSyncTest {
    private val openFoodFacts = MockWebServer()

    @Before
    fun startOpenFoodFacts() {
        openFoodFacts.useHttps(TestTls.server.sslSocketFactory())
        openFoodFacts.protocolNegotiationEnabled = false
        openFoodFacts.start()
    }

    @After
    fun stopOpenFoodFacts() {
        openFoodFacts.close()
    }

    private fun forEachVersion(block: suspend PantrySyncEnv.() -> Unit) = PantrySyncEnv.forEachVersion(block)

    private fun PantrySyncEnv.lookup(): DefaultBarcodeLookup {
        val http = OpenFoodFactsClient.httpClient(OpenFoodFactsClient.userAgent("0.4.0-test")) {
            sslSocketFactory(TestTls.client.sslSocketFactory(), TestTls.client.trustManager)
        }
        val client = OpenFoodFactsClient(http, openFoodFacts.url("/").toString())
        return DefaultBarcodeLookup(graph.sources, graph.databases, graph.foodAliases, client, graph.network, graph.clock)
    }

    private fun answer(code: Int, body: String) {
        openFoodFacts.enqueue(MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build())
    }

    // ------------------------------------------------------------------------------ aliases

    @Test
    fun learningOfflineNeverMakesADuplicate() = forEachVersion {
        val aliases = graph.foodAliases
        offline()
        val code = value(aliases.learn(BARCODE, "kikärtor", FoodAliasKind.BARCODE))
        // The same code again (another case, spaces): the same alias, nothing new queued.
        assertEquals(code.id, value(aliases.learn(" $BARCODE ", "Kikärtor", FoodAliasKind.BARCODE)).id)
        val line = value(aliases.learn(RECEIPT_LINE.lowercase(), "kikärtor", FoodAliasKind.RECEIPT))
        // Re-pointed before it was sent: one create with the final food.
        assertEquals(line.id, value(aliases.learn(RECEIPT_LINE, "kikärtor eko", FoodAliasKind.RECEIPT)).id)
        assertEquals(2, outboxCount())
        assertEquals("kikärtor eko", aliases.find("Kikärtor 380g EKO")?.foodName)

        online()
        drainAll()

        val sent = tandoor.aliases().map { it.param1 to it.param2 }
        assertEquals(listOf(BARCODE to "kikärtor", RECEIPT_LINE.lowercase() to "kikärtor eko"), sent)
        assertEquals(listOf("Tandem: streckkod", "Tandem: kvitto"), tandoor.aliases().map { it.name })
        assertEquals(2, tandoor.requests("POST", "/api/automation/").size)
        assertTrue(tandoor.requests("PATCH", "/api/automation/").isEmpty())
        val stored = aliases.observeAliases().first()
        assertEquals(tandoor.aliases().map { it.id }.toSet(), stored.map { it.id }.toSet())
        assertTrue(stored.none { it.pending })
    }

    @Test
    fun aCodeAnotherPhoneLearnedFirstIsTakenOverNotDuplicated() = forEachVersion {
        val aliases = graph.foodAliases
        val existing = tandoor.seedAlias(BARCODE, "kikärtor")
        val other = tandoor.seedAlias("7310000000005", "havregryn")
        value(aliases.learn(BARCODE, "kikärtor", FoodAliasKind.BARCODE))
        // The other phone's code, pointed at another food here: re-pointed on the server.
        value(aliases.learn("7310000000005", "havregryn glutenfria", FoodAliasKind.BARCODE))

        drainAll()

        assertTrue(tandoor.requests("POST", "/api/automation/").isEmpty())
        assertEquals(listOf(existing, other), tandoor.aliases().map { it.id })
        assertEquals("havregryn glutenfria", tandoor.aliases().single { it.id == other }.param2)
        assertEquals(setOf(existing, other), aliases.observeAliases().first().map { it.id }.toSet())
    }

    @Test
    fun theSpacesAliasesAreReadWithTheirKindAndMatchedWithoutCase() = forEachVersion {
        tandoor.seedAlias(BARCODE, "kikärtor")
        tandoor.seedAlias(RECEIPT_LINE, "kikärtor", name = "Tandem: kvitto")
        tandoor.seedAlias("Grädde 36%", "vispgrädde", name = "Från webben")
        ok(graph.foodAliases.refresh())

        val stored = graph.foodAliases.observeAliases().first().associateBy { it.alias }
        assertEquals(FoodAliasKind.BARCODE, stored.getValue(BARCODE).kind)
        assertEquals(FoodAliasKind.RECEIPT, stored.getValue(RECEIPT_LINE).kind)
        assertEquals(FoodAliasKind.OTHER, stored.getValue("Grädde 36%").kind)
        assertEquals("vispgrädde", graph.foodAliases.find("GRÄDDE 36%")?.foodName)
        // A web alias wins: the app does not change it.
        assertEquals("vispgrädde", value(graph.foodAliases.learn("grädde 36%", "matlagningsgrädde", FoodAliasKind.RECEIPT)).foodName)
        assertEquals(0, outboxCount())
    }

    @Test
    fun aTooLongTextIsCutToTheServersLimitAndForgettingDeletesIt() = forEachVersion {
        val long = "A".repeat(FoodAlias.MAX_LENGTH + 10)
        val alias = value(graph.foodAliases.learn(long, "mjölk", FoodAliasKind.RECEIPT))
        assertEquals(FoodAlias.MAX_LENGTH, alias.alias.length)
        drainAll()
        assertEquals(FoodAlias.MAX_LENGTH, tandoor.aliases().single().param1.length)

        val sent = graph.foodAliases.observeAliases().first().single()
        ok(graph.foodAliases.forget(sent.id))
        drainAll()
        assertTrue(tandoor.aliases().isEmpty())
        assertTrue(graph.foodAliases.observeAliases().first().isEmpty())
    }

    @Test
    fun aGuestCannotLearn() = forEachVersion {
        graph.permissions.setGuest(true)
        assertEquals(Outcome.Failure(DataProblem.READ_ONLY.toError()), graph.foodAliases.learn(BARCODE, "kikärtor", FoodAliasKind.BARCODE))
        assertEquals(0, outboxCount())
        PantrySyncEnv.mirror(graph, groupLevel = "guest")
        ok(graph.foodAliasReader.sync(graph.remote, background = false))
        assertTrue("a guest's list is 403: not asked", tandoor.calls.isEmpty())
    }

    // ------------------------------------------------------------------------------ barcodes

    @Test
    fun aLearnedCodeIsAnsweredWithoutOpenFoodFacts() = forEachVersion {
        val lookup = lookup()
        value(graph.foodAliases.learn(BARCODE, "kikärtor", FoodAliasKind.BARCODE))

        val found = value(lookup.lookup(BARCODE)) as BarcodeLookupResult.Found

        assertEquals("kikärtor", found.product.name)
        assertEquals(0, openFoodFacts.requestCount)
    }

    @Test
    fun openFoodFactsIsAskedOnceAndItsAnswerCached() = forEachVersion {
        val lookup = lookup()
        val before = openFoodFacts.requestCount
        answer(200, FOUND)

        val found = value(lookup.lookup(UPC)) as BarcodeLookupResult.Found
        assertEquals("Thai peanut noodle kit", found.product.name)
        assertEquals("Simply Asia", found.product.brand)
        assertEquals("155 g", found.product.quantity)
        assertEquals(found, value(lookup.lookup(UPC)))
        assertEquals(before + 1, openFoodFacts.requestCount)
        val request = openFoodFacts.takeRequest()
        assertEquals("/api/v2/product/$UPC", request.url.encodedPath)
        assertEquals(null, request.headers["Authorization"])

        // A month later the product is asked again.
        graph.clock.advance(31.days)
        answer(200, FOUND)
        value(lookup.lookup(UPC))
        assertEquals(before + 2, openFoodFacts.requestCount)
    }

    @Test
    fun anUnknownCodeIsRememberedAndOfflineUsesTheCache() = forEachVersion {
        val lookup = lookup()
        val before = openFoodFacts.requestCount
        answer(404, NOT_FOUND_BODY)
        assertEquals(BarcodeLookupResult.NotFound, value(lookup.lookup(UNKNOWN)))
        assertEquals(BarcodeLookupResult.NotFound, value(lookup.lookup(UNKNOWN)))
        assertEquals(before + 1, openFoodFacts.requestCount)

        offline()
        graph.clock.advance(8.days)
        // Stale but stored: used offline rather than nothing.
        assertEquals(BarcodeLookupResult.NotFound, value(lookup.lookup(UNKNOWN)))
        assertEquals(Outcome.Failure(TandemError.NetworkUnavailable), lookup.lookup(UPC))
        assertEquals(before + 1, openFoodFacts.requestCount)
    }

    @Test
    fun anInvalidCodeAndDemoModeNeverReachOpenFoodFacts() = forEachVersion {
        val lookup = lookup()
        val before = openFoodFacts.requestCount
        // A wrong check digit, letters, a length no food code has.
        assertTrue(lookup.lookup("737628064503") is Outcome.Failure)
        assertTrue(lookup.lookup("73762806450a") is Outcome.Failure)
        assertTrue(lookup.lookup("12345") is Outcome.Failure)

        graph.sources.source.value = ActiveSource.Demo
        assertEquals(BarcodeLookupResult.NotFound, value(lookup.lookup(UPC)))
        assertEquals(before, openFoodFacts.requestCount)
    }

    private companion object {
        /** Synthetic (GS1 prefix 20–29 = in-store code, never a real product), as in the contract. */
        const val BARCODE = "2000000000015"
        const val RECEIPT_LINE = "KIKÄRTOR 380G EKO"

        /** A public Open Food Facts product (UPC-A), and a code with a valid check digit that does not exist. */
        const val UPC = "737628064502"
        const val UNKNOWN = "2999999999991"

        const val FOUND = """{"code":"0737628064502","product":{"brands":"Simply Asia, Thai Kitchen","code":"0737628064502",""" +
            """"product_name":"Thai peanut noodle kit","quantity":"155 g"},"status":1,"status_verbose":"product found"}"""
        const val NOT_FOUND_BODY = """{"code":"2999999999991","status":0,"status_verbose":"product not found"}"""
    }
}
