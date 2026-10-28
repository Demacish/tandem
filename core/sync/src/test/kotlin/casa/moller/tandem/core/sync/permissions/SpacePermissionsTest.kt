package casa.moller.tandem.core.sync.permissions

import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.database.entity.MealPlanEntity
import casa.moller.tandem.core.database.entity.ServerAccountEntity
import casa.moller.tandem.core.database.entity.ShoppingEntryEntity
import casa.moller.tandem.core.model.account.SpaceRole
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.server.Capabilities
import casa.moller.tandem.core.model.server.ServerCompatibility
import casa.moller.tandem.core.model.server.ServerFeature
import casa.moller.tandem.core.model.server.ServerStatus
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.testing.SyncTestGraph
import casa.moller.tandem.core.sync.testing.SyncTestGraph.Companion.ACCOUNT_ID
import casa.moller.tandem.core.testing.data.FakeServerStatusRepository
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.ConscryptMode

/**
 * 0.3.0 review (fix-data, plan 6.2 "kontroll av hushåll", 8.6): a member without a household on a
 * space whose household setup is not finished is told that nothing is shared; a household, a
 * finished setup, a server not checked yet and demo mode never warn.
 */
@RunWith(AndroidJUnit4::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class SpacePermissionsTest {
    private val graph = SyncTestGraph()
    private val status = FakeServerStatusRepository(checked(households = false))
    private val permissions = DefaultSpacePermissionsRepository(graph.sources, graph.databases, status)

    @After
    fun tearDown() {
        graph.close()
    }

    @Test
    fun aMemberWithoutAHouseholdIsWarnedOnlyWhileTheSpaceHasNoHouseholdSetup() = runBlocking {
        val account = graph.account
        graph.real.accountDao().upsert(
            ServerAccountEntity(id = account.id, baseUrl = account.baseUrl, userId = account.userId, spaceId = account.spaceId),
        )
        assertFalse("the role is not read yet", permissions.permissions.first().householdMissing)

        graph.real.accountDao().setMembership(ACCOUNT_ID, groupLevel = "user", householdId = null, householdName = null)
        val missing = permissions.permissions.first()
        assertEquals(SpaceRole.USER, missing.role)
        assertTrue(missing.householdMissing)

        status.status.value = checked(households = true)
        assertFalse("the setup is finished", permissions.permissions.first().householdMissing)

        status.status.value = checked(households = false).copy(checkedAt = null)
        assertFalse("the server is not checked yet", permissions.permissions.first().householdMissing)

        status.status.value = checked(households = false)
        graph.real.accountDao().setMembership(ACCOUNT_ID, groupLevel = "user", householdId = 3, householdName = "Hemma")
        assertFalse("in a household", permissions.permissions.first().householdMissing)

        graph.real.accountDao().setMembership(ACCOUNT_ID, groupLevel = "user", householdId = null, householdName = null)
        graph.sources.source.value = ActiveSource.Demo
        assertFalse("never in demo mode", permissions.permissions.first().householdMissing)
    }

    /**
     * 0.3.0 komplettering ("Delad med: …", plan 8.6): an admin's household members come from
     * `/api/user-space/` (the own household only, never the user himself or someone elsewhere).
     */
    @Test
    fun anAdminIsSharedWithTheMembersOfHisHousehold() = runBlocking {
        mirror()
        graph.real.accountDao().setMembership(ACCOUNT_ID, groupLevel = "admin", householdId = 3, householdName = "Hemma")
        val memberships = listOf(
            membership(1, "Jag", "admin", household = 3),
            membership(2, "Anna", "user", household = 3),
            membership(3, "Granne", "user", household = 9),
            membership(4, "Gäst", "guest", household = null),
        )
        graph.server.respond("GET", "/api/user-space/") {
            json("""{"count":4,"next":null,"previous":null,"results":[${memberships.joinToString(",")}]}""")
        }
        assertEquals(emptyList<String>(), permissions.permissions.first().sharedWith)
        assertTrue(graph.householdMembers.sync(graph.remote, background = false) is Outcome.Success)
        assertEquals(listOf("Anna"), permissions.permissions.first().sharedWith)
        assertTrue("no directory read for an admin", graph.server.requestsTo("GET", "/api/user/").isEmpty())

        // A failed read keeps what was stored.
        graph.server.respond("GET", "/api/user-space/") { MockResponse.Builder().code(503).body("busy").build() }
        assertTrue(graph.householdMembers.sync(graph.remote, background = false) is Outcome.Failure)
        assertEquals(listOf("Anna"), permissions.permissions.first().sharedWith)

        // Out of the household: nothing is shared, nothing is read.
        graph.real.accountDao().setMembership(ACCOUNT_ID, groupLevel = "admin", householdId = null, householdName = null)
        val reads = graph.server.requestsTo("GET", "/api/user-space/").size
        assertTrue(graph.householdMembers.sync(graph.remote, background = false) is Outcome.Success)
        assertEquals(emptyList<String>(), permissions.permissions.first().sharedWith)
        assertEquals(reads, graph.server.requestsTo("GET", "/api/user-space/").size)
    }

    /**
     * 0.3.0 komplettering: a member sees only their own membership, so the household is whoever
     * created the stored rows and meals, named from the row or from the space's users.
     */
    @Test
    fun aMemberIsSharedWithWhoeverCreatedRowsAndMeals() = runBlocking {
        mirror()
        graph.real.accountDao().setMembership(ACCOUNT_ID, groupLevel = "user", householdId = 3, householdName = "Hemma")
        graph.server.respond("GET", "/api/user/") {
            val users = listOf(1L to "Jag", 2L to "Anna", 5L to "Lisa", 9L to "Granne")
            json(users.joinToString(",", "[", "]") { (id, name) -> """{"id":$id,"username":"u$id","display_name":"$name"}""" })
        }
        assertTrue(graph.householdMembers.sync(graph.remote, background = false) is Outcome.Success)
        assertTrue("a member never reads the memberships", graph.server.requestsTo("GET", "/api/user-space/").isEmpty())
        assertEquals("nobody else created anything yet", emptyList<String>(), permissions.permissions.first().sharedWith)

        graph.real.shoppingDao().upsertEntries(
            listOf(
                ShoppingEntryEntity(ACCOUNT_ID, localId = 10, serverId = 10, foodName = "mjölk", createdBy = 1, createdByName = "Jag"),
                ShoppingEntryEntity(ACCOUNT_ID, localId = 11, serverId = 11, foodName = "smör", createdBy = 2, createdByName = "Anna"),
            ),
        )
        graph.real.mealPlanDao().upsert(
            listOf(
                MealPlanEntity(
                    ACCOUNT_ID,
                    localId = 20,
                    serverId = 20,
                    fromDate = "2026-10-28",
                    toDate = "2026-10-28",
                    mealTypeId = 1,
                    createdBy = 5,
                ),
            ),
        )
        assertEquals(listOf("Anna", "Lisa"), permissions.permissions.first().sharedWith)
    }

    @Test
    fun demoModeIsSharedWithTheDemoHousehold() = runBlocking {
        graph.demo.ensureSeeded()
        graph.sources.source.value = ActiveSource.Demo
        assertEquals(listOf("Alex"), permissions.permissions.first().sharedWith)
    }

    private suspend fun mirror() {
        val account = graph.account
        graph.real.accountDao().upsert(
            ServerAccountEntity(id = account.id, baseUrl = account.baseUrl, userId = account.userId, spaceId = account.spaceId),
        )
    }

    private fun membership(userId: Long, name: String, group: String, household: Long?): String {
        val home = household?.let { """{"id":$it,"name":"H$it"}""" } ?: "null"
        return """{"id":$userId,"user":{"id":$userId,"username":"u$userId","display_name":"$name"},"space":1,""" +
            """"groups":[{"id":1,"name":"$group"}],"household":$home}"""
    }

    private fun json(body: String): MockResponse =
        MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(body).build()

    private fun checked(households: Boolean): ServerStatus {
        val features = Capabilities.Tandoor26.features.let { if (households) it else it - ServerFeature.HOUSEHOLDS }
        return ServerStatus(
            version = "2.6.13",
            compatibility = ServerCompatibility.SUPPORTED,
            capabilities = Capabilities("2.6.13", features),
            checkedAt = Instant.fromEpochMilliseconds(1),
        )
    }
}
