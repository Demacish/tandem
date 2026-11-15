package casa.moller.tandem.diagnostics

import android.app.ApplicationStartInfo
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Enhetstestet B16: Diagnostics said "Inte mätt än" after five cold starts on the Galaxy S21 FE
 * (Android 16), because the launch's activity is created only after `Application.onCreate` has
 * returned and the main thread has run what it posted.
 */
class ColdStartTest {

    @Test
    fun aLaunchsActivityAfterApplicationOnCreateIsMeasured() {
        val gate = ColdStartGate()
        gate.onApplicationCreated(uptimeMillis = 1_000)
        // Android 14+: the system starts the activity after the app reported that onCreate is done.
        assertTrue(gate.takeForFirstActivity(uptimeMillis = 1_180))
        // Process start requested at 600, first screen with content at 1 450.
        assertEquals(850.milliseconds, coldStartDuration(processStartRequestedUptimeMillis = 600, nowUptimeMillis = 1_450))
    }

    @Test
    fun onlyTheFirstActivityOfAProcessCounts() {
        val gate = ColdStartGate()
        gate.onApplicationCreated(uptimeMillis = 1_000)
        assertTrue(gate.takeForFirstActivity(uptimeMillis = 1_050))
        // A warm start: the process lives on and the activity is created again (or another one).
        assertFalse(gate.takeForFirstActivity(uptimeMillis = 1_100))
        assertFalse(gate.takeForFirstActivity(uptimeMillis = 600_000))
    }

    @Test
    fun aProcessStartedForBackgroundWorkIsNoColdStartWhenTheAppIsOpenedLater() {
        val gate = ColdStartGate()
        // The periodic sync started the process; the user opens the app a minute later.
        gate.onApplicationCreated(uptimeMillis = 1_000)
        assertFalse(gate.takeForFirstActivity(uptimeMillis = 61_000))
    }

    @Test
    fun theLaunchGapIsTheLimit() {
        val gate = ColdStartGate()
        gate.onApplicationCreated(uptimeMillis = 1_000)
        assertTrue(gate.takeForFirstActivity(uptimeMillis = 1_000 + ColdStartGate.LAUNCH_GAP.inWholeMilliseconds))
        val late = ColdStartGate()
        late.onApplicationCreated(uptimeMillis = 1_000)
        assertFalse(late.takeForFirstActivity(uptimeMillis = 1_001 + ColdStartGate.LAUNCH_GAP.inWholeMilliseconds))
    }

    @Test
    fun anActivityBeforeApplicationOnCreateIsNotMeasured() {
        assertFalse(ColdStartGate().takeForFirstActivity(uptimeMillis = 1_000))
    }

    @Test
    fun implausibleDurationsAreDropped() {
        assertNull(coldStartDuration(processStartRequestedUptimeMillis = 2_000, nowUptimeMillis = 1_000))
        assertNull(coldStartDuration(processStartRequestedUptimeMillis = 0, nowUptimeMillis = 31.seconds.inWholeMilliseconds))
        assertEquals(30.seconds, coldStartDuration(processStartRequestedUptimeMillis = 0, nowUptimeMillis = 30_000))
    }

    @Test
    fun androidsOwnStartRecordTellsActivityStartsFromOthers() {
        val cold = ApplicationStartInfo.START_TYPE_COLD
        assertTrue(isActivityColdStart(cold, ApplicationStartInfo.START_REASON_LAUNCHER))
        assertTrue(isActivityColdStart(cold, ApplicationStartInfo.START_REASON_LAUNCHER_RECENTS))
        assertTrue(isActivityColdStart(cold, ApplicationStartInfo.START_REASON_START_ACTIVITY))
        assertFalse(isActivityColdStart(cold, ApplicationStartInfo.START_REASON_JOB))
        assertFalse(isActivityColdStart(cold, ApplicationStartInfo.START_REASON_ALARM))
        assertFalse(isActivityColdStart(cold, ApplicationStartInfo.START_REASON_BROADCAST))
        assertFalse(isActivityColdStart(ApplicationStartInfo.START_TYPE_WARM, ApplicationStartInfo.START_REASON_LAUNCHER))
    }
}
