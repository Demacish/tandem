package casa.moller.tandem.core.sync

import androidx.work.ExistingWorkPolicy
import casa.moller.tandem.core.model.sync.SyncReason
import casa.moller.tandem.core.sync.work.WorkManagerSyncScheduler
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

    @Test
    fun onlyPullToRefreshReplacesAWaitingOrRunningSync() {
        SyncReason.entries.forEach { reason ->
            val expected = if (reason == SyncReason.USER_REFRESH) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
            assertEquals(reason.name, expected, WorkManagerSyncScheduler.policyFor(reason))
        }
    }
}
