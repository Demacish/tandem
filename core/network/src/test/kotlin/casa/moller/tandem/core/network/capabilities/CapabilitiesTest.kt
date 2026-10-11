package casa.moller.tandem.core.network.capabilities

import casa.moller.tandem.core.model.server.ServerCompatibility
import casa.moller.tandem.core.model.server.ServerFeature
import casa.moller.tandem.core.model.server.WritePolicy
import casa.moller.tandem.core.network.api.TandoorApiFactory
import casa.moller.tandem.core.network.dto.SpaceDto
import casa.moller.tandem.core.network.http.TokenProvider
import casa.moller.tandem.core.network.testing.Fixture
import casa.moller.tandem.core.network.testing.HttpsServerRule
import casa.moller.tandem.core.network.testing.emptyResponse
import casa.moller.tandem.core.network.testing.jsonResponse
import casa.moller.tandem.core.network.testing.testClient
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CapabilitiesTest {
    @get:Rule
    val tandoor = HttpsServerRule()

    private val api by lazy { TandoorApiFactory(testClient()).create(tandoor.url("/"), TokenProvider { "tda_device_token" }) }
    private val resolver = CapabilityResolver()

    private data class Row(val compatibility: ServerCompatibility, val tested: Boolean, val inRange: Boolean, val writePolicy: WritePolicy)

    @Test
    fun versionTable() {
        val table = mapOf(
            "2.6.0" to Row(ServerCompatibility.SUPPORTED, tested = true, inRange = true, WritePolicy.SEND),
            "2.6.13" to Row(ServerCompatibility.SUPPORTED, tested = true, inRange = true, WritePolicy.SEND),
            "v2.6.15" to Row(ServerCompatibility.SUPPORTED, tested = true, inRange = true, WritePolicy.SEND),
            "2.6.16" to Row(ServerCompatibility.UNTESTED_NEWER, tested = false, inRange = true, WritePolicy.QUEUE_PAUSED),
            "2.7.3" to Row(ServerCompatibility.UNTESTED_NEWER, tested = false, inRange = true, WritePolicy.QUEUE_PAUSED),
            "2.8.0" to Row(ServerCompatibility.UNTESTED_NEWER, tested = false, inRange = false, WritePolicy.DISABLED),
            "3.1.0" to Row(ServerCompatibility.UNTESTED_NEWER, tested = false, inRange = false, WritePolicy.DISABLED),
            "2.5.3" to Row(ServerCompatibility.UNTESTED_OLDER, tested = false, inRange = false, WritePolicy.QUEUE_PAUSED),
            "2.0.0" to Row(ServerCompatibility.UNTESTED_OLDER, tested = false, inRange = false, WritePolicy.QUEUE_PAUSED),
            "1.5.19" to Row(ServerCompatibility.TOO_OLD, tested = false, inRange = false, WritePolicy.DISABLED),
            "nightly" to Row(ServerCompatibility.UNRECOGNIZED, tested = false, inRange = false, WritePolicy.QUEUE_PAUSED),
            "" to Row(ServerCompatibility.UNRECOGNIZED, tested = false, inRange = false, WritePolicy.QUEUE_PAUSED),
        )
        for ((version, expected) in table) {
            val assessment = TandoorVersionTable.assess(version)
            val actual = Row(assessment.compatibility, assessment.isTested, assessment.inSupportedRange, assessment.writePolicy)
            assertEquals("version '$version'", expected, actual)
            assertEquals(version, assessment.rawVersion)
        }
        assertEquals(16, TandoorVersionTable.TESTED.size)
        assertNull(TandoorVersionTable.assess(null).version)
    }

    @Test
    fun knownVersionDecidesWithoutProbes() {
        val space = SpaceDto(aiEnabled = true, householdSetupCompleted = true)
        val plan = TandoorCapabilities.plan(TandoorVersionTable.assess("2.6.13"), space)
        assertEquals(ServerFeature.entries.toSet(), plan.features)
        assertEquals(emptyList<CapabilityProbe>(), plan.probes)

        val noSetup = TandoorCapabilities.plan(TandoorVersionTable.assess("2.6.13"), SpaceDto(aiEnabled = false))
        assertEquals(ServerFeature.entries.toSet() - ServerFeature.AI_IMPORT - ServerFeature.HOUSEHOLDS, noSetup.features)

        val older = TandoorCapabilities.plan(TandoorVersionTable.assess("2.5.3"), space)
        assertFalse(ServerFeature.INVENTORY in older.features)
        assertFalse(ServerFeature.HOUSEHOLDS in older.features)
        assertTrue(ServerFeature.RECIPE_INDEX in older.features)
        assertEquals(emptyList<CapabilityProbe>(), older.probes)

        val newer = TandoorCapabilities.plan(TandoorVersionTable.assess("2.9.0"), null)
        assertEquals(ServerFeature.entries.toSet() - ServerFeature.AI_IMPORT - ServerFeature.HOUSEHOLDS, newer.features)
        assertEquals(emptyList<CapabilityProbe>(), newer.probes)

        assertEquals(emptySet<ServerFeature>(), TandoorCapabilities.plan(TandoorVersionTable.assess("1.5.19"), space).features)
    }

    @Test
    fun unrecognizedVersionProbesAtMostThree() {
        val plan = TandoorCapabilities.plan(TandoorVersionTable.assess("develop"), SpaceDto(householdSetupCompleted = true))
        assertEquals(listOf(CapabilityProbe.VIEW_LOG, CapabilityProbe.INVENTORY, CapabilityProbe.HOUSEHOLDS), plan.probes)
        assertTrue(plan.probes.size <= TandoorCapabilities.MAX_PROBES)
        assertFalse(ServerFeature.VIEW_LOG in plan.features)
        val withoutHouseholds = TandoorCapabilities.plan(TandoorVersionTable.assess("develop"), SpaceDto(householdSetupCompleted = false))
        assertEquals(listOf(CapabilityProbe.VIEW_LOG, CapabilityProbe.INVENTORY), withoutHouseholds.probes)
    }

    @Test
    fun resolverReadsTheSpaceAndSendsNoProbeForAKnownVersion() = runBlocking {
        tandoor.enqueue(Fixture.load("fas1/space-current").toMockResponse())
        val report = resolver.resolve(api, "2.6.13")
        assertEquals(0, report.probesSent)
        assertEquals("/api/space/current/", tandoor.takeRequest().url.encodedPath)
        assertNull("nothing else was requested", tandoor.server.takeRequest(100, TimeUnit.MILLISECONDS))
        assertEquals("2.6.13", report.capabilities.version)
        assertTrue(ServerFeature.AI_IMPORT in report.capabilities)
        assertFalse("household setup not completed", ServerFeature.HOUSEHOLDS in report.capabilities)
        assertTrue(ServerFeature.INVENTORY in report.capabilities)
        assertEquals(WritePolicy.SEND, report.assessment.writePolicy)
        assertEquals(1L, report.space?.id)
    }

    @Test
    fun resolverUsesAGivenSpaceWithoutARequest() = runBlocking {
        val report = resolver.resolve(api, "2.6.13", SpaceDto(id = 1, householdSetupCompleted = true))
        assertTrue(ServerFeature.HOUSEHOLDS in report.capabilities)
        assertNull(tandoor.server.takeRequest(100, TimeUnit.MILLISECONDS))
    }

    @Test
    fun resolverProbesAnUnrecognizedVersion() = runBlocking {
        val space = SpaceDto(id = 1, householdSetupCompleted = true)
        tandoor.enqueue(
            jsonResponse(200, """{"count":0,"next":null,"previous":null,"results":[]}"""),
            // Tandoor answers an unknown /api/ path with a 302 to the login page (FACTS g).
            emptyResponse(302, "Location" to "/accounts/login/?next=/api/inventory-location/"),
            jsonResponse(200, """{"count":1,"next":null,"previous":null,"results":[{"id":1}]}"""),
        )
        val report = resolver.resolve(api, "develop", space)
        assertEquals(3, report.probesSent)
        val paths = List(3) { tandoor.takeRequest().url }
        assertEquals(listOf("/api/view-log/", "/api/inventory-location/", "/api/household/"), paths.map { it.encodedPath })
        assertTrue(paths.all { it.queryParameter("page_size") == "1" })
        assertTrue(ServerFeature.VIEW_LOG in report.capabilities)
        assertFalse(ServerFeature.INVENTORY in report.capabilities)
        assertTrue(ServerFeature.HOUSEHOLDS in report.capabilities)
        assertEquals(ServerCompatibility.UNRECOGNIZED, report.assessment.compatibility)
    }

    @Test
    fun anErrorStopsFurtherProbes() = runBlocking {
        val space = SpaceDto(id = 1, householdSetupCompleted = true)
        tandoor.enqueue(jsonResponse(403, """{"detail":"Authentication credentials were not provided."}"""))
        val report = resolver.resolve(api, "develop", space)
        assertEquals(1, report.probesSent)
        tandoor.takeRequest()
        assertNull("no second probe after a 403", tandoor.server.takeRequest(100, TimeUnit.MILLISECONDS))
        assertFalse(ServerFeature.VIEW_LOG in report.capabilities)
        assertTrue(ServerFeature.RECIPE_INDEX in report.capabilities)
    }

    @Test
    fun tooOldSendsNothing() = runBlocking {
        val report = resolver.resolve(api, "1.5.19")
        assertEquals(WritePolicy.DISABLED, report.assessment.writePolicy)
        assertEquals(emptySet<ServerFeature>(), report.capabilities.features)
        assertNull(tandoor.server.takeRequest(100, TimeUnit.MILLISECONDS))
    }

    @Test
    fun spaceFailureStillGivesTheVersionsFeatures() = runBlocking {
        tandoor.enqueue(emptyResponse(502))
        val report = resolver.resolve(api, "2.6.13")
        assertNull(report.space)
        assertEquals(TandoorCapabilities.fromVersionAndSpace("2.6.13", null), report.capabilities)
        assertFalse(ServerFeature.AI_IMPORT in report.capabilities)
    }
}
