package casa.moller.tandem.core.sync

import kotlin.time.Duration.Companion.minutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncScheduleTest {
    @Test
    fun periodicSyncRespectsWorkManagersMinimum() {
        assertTrue(SyncSchedule.PERIODIC >= 15.minutes)
    }

    @Test
    fun workNamesAreUnique() {
        val names = listOf(SyncSchedule.WORK_PERIODIC, SyncSchedule.WORK_ONE_TIME, SyncSchedule.WORK_OUTBOX)
        assertEquals(names.size, names.toSet().size)
    }
}
