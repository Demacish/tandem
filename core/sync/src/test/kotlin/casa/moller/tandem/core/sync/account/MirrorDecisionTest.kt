package casa.moller.tandem.core.sync.account

import casa.moller.tandem.core.database.entity.ServerAccountEntity
import casa.moller.tandem.core.model.account.ServerAccount
import casa.moller.tandem.core.model.server.ServerCompatibility
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a logged-in account means for its stored mirror row (plan 4.1, 11.1, B18). */
class MirrorDecisionTest {
    private val account = ServerAccount(
        id = "a",
        baseUrl = "https://tandoor.example.com",
        username = "cook",
        userId = 1,
        spaceId = 1,
        serverVersion = "2.6.15",
        addedAt = Instant.fromEpochMilliseconds(0),
    )
    private val stored = ServerAccountEntity(
        id = "a",
        baseUrl = "https://tandoor.example.com/",
        userId = 1,
        spaceId = 1,
        serverVersion = "2.6.13",
    )

    @Test
    fun aNewVersionOfTheSameAccountIsAChange() {
        val decision = MirrorDecision.of(stored, account)
        assertFalse(decision.clearStoredData)
        assertEquals("2.6.13", decision.versionChange?.previousVersion)
        assertEquals("2.6.15", decision.versionChange?.newVersion)
        assertEquals(ServerCompatibility.SUPPORTED, decision.versionChange?.compatibility)
    }

    @Test
    fun theSameVersionOrAFirstLoginIsNoChange() {
        assertNull(MirrorDecision.of(stored.copy(serverVersion = "2.6.15"), account).versionChange)
        assertNull(MirrorDecision.of(null, account).versionChange)
    }

    @Test
    fun anotherSpaceUserOrServerUnderTheSameIdClearsTheOldRows() {
        assertTrue(MirrorDecision.of(stored.copy(spaceId = 2), account).clearStoredData)
        assertTrue(MirrorDecision.of(stored.copy(userId = 2), account).clearStoredData)
        assertTrue(MirrorDecision.of(stored.copy(baseUrl = "https://other.example.com"), account).clearStoredData)
    }

    @Test
    fun anUntestedNewVersionIsReportedWithItsCompatibility() {
        val change = MirrorDecision.of(stored, account.copy(serverVersion = "2.7.0")).versionChange
        assertEquals(ServerCompatibility.UNTESTED_NEWER, change?.compatibility)
    }
}
