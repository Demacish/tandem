package casa.moller.tandem.core.sync.permissions

import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.database.entity.ServerAccountEntity
import casa.moller.tandem.core.model.account.SpaceRole
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
