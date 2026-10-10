package casa.moller.tandem.core.model.server

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ServerStatusTest {

    @Test
    fun onlyTheTestedLineSendsChanges() {
        assertEquals(WritePolicy.SEND, ServerCompatibility.SUPPORTED.writePolicy)
        assertEquals(WritePolicy.QUEUE_PAUSED, ServerCompatibility.UNTESTED_NEWER.writePolicy)
        assertEquals(WritePolicy.QUEUE_PAUSED, ServerCompatibility.UNTESTED_OLDER.writePolicy)
        assertEquals(WritePolicy.QUEUE_PAUSED, ServerCompatibility.UNRECOGNIZED.writePolicy)
        assertEquals(WritePolicy.DISABLED, ServerCompatibility.TOO_OLD.writePolicy)
    }

    @Test
    fun safeModeFollowsTheCompatibilityButNeverInDemoMode() {
        val untested = ServerStatus(version = "2.7.0", compatibility = ServerCompatibility.UNTESTED_NEWER, capabilities = Capabilities.None)
        assertTrue(untested.safeMode)
        assertFalse(ServerStatus.Unknown.safeMode)
        assertFalse(ServerStatus.Demo.safeMode)
    }

    @Test
    fun capabilitiesAnswerContains() {
        assertTrue(ServerFeature.COOK_LOG in Capabilities.Tandoor26)
        assertFalse(ServerFeature.AI_IMPORT in Capabilities.Tandoor26)
        assertFalse(ServerFeature.COOK_LOG in Capabilities.None)
    }
}
