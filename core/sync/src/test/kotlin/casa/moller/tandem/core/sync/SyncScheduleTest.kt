package casa.moller.tandem.core.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ExistingWorkPolicy
import casa.moller.tandem.core.model.sync.SyncReason
import casa.moller.tandem.core.sync.work.WorkManagerSyncScheduler
import kotlin.time.Duration.Companion.minutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncScheduleTest {
    @Test
    fun periodicSyncRespectsWorkManagersMinimum() {
        assertTrue(SyncSchedule.PERIODIC >= 15.minutes)
    }

    @Test
    fun thePeriodicSyncStartsOnePeriodAfterLogin() {
        // The onboarding's first sync does the first full sync; a periodic run at once would repeat it.
        val request = WorkManagerSyncScheduler.periodicRequest()

        assertEquals(SyncSchedule.PERIODIC.inWholeMilliseconds, request.workSpec.initialDelay)
        assertEquals(SyncSchedule.PERIODIC.inWholeMilliseconds, request.workSpec.intervalDuration)
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
