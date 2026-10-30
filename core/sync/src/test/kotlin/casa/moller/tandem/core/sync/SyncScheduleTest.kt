package casa.moller.tandem.core.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ExistingWorkPolicy
import casa.moller.tandem.core.model.sync.SyncReason
import casa.moller.tandem.core.sync.work.WorkManagerSyncScheduler
import kotlin.time.Duration.Companion.minutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun onlyPullToRefreshAndAnAccountSwitchReplaceAWaitingOrRunningSync() {
        SyncReason.entries.forEach { reason ->
            // Konto lane: a switch cancels the old account's run instead of being dropped by it.
            val replacing = reason == SyncReason.USER_REFRESH || reason == SyncReason.ACCOUNT_CHANGED
            val expected = if (replacing) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
            assertEquals(reason.name, expected, WorkManagerSyncScheduler.policyFor(reason))
        }
    }

    // Fas S (rester lane): adding an account while another is logged in synced twice in a row.

    @Test
    fun anAccountJustAddedThatNeverSyncedLeavesItsFirstSyncToTheIntro() {
        val now = 1_000_000_000L
        // Logged in a moment ago, nothing synced yet: the intro's FIRST_SYNC is its first sync.
        assertFalse(SyncStarter.syncsOnAccountChange(addedAtMillis = now - 5_000, lastSuccessMillis = null, nowMillis = now))
        // A clock set back after the login still counts as just added.
        assertFalse(SyncStarter.syncsOnAccountChange(addedAtMillis = now + 60_000, lastSuccessMillis = null, nowMillis = now))
    }

    @Test
    fun aSwitchToAnAccountThatSyncedBeforeOrWasAddedLongAgoStillSyncs() {
        val now = 1_000_000_000L
        val longAgo = now - SyncStarter.JUST_ADDED.inWholeMilliseconds
        assertTrue(SyncStarter.syncsOnAccountChange(addedAtMillis = now - 5_000, lastSuccessMillis = now - 1_000, nowMillis = now))
        assertTrue(SyncStarter.syncsOnAccountChange(addedAtMillis = longAgo, lastSuccessMillis = 1, nowMillis = now))
        // Its first sync never finished (the intro was left with "continue in the background"): sync now.
        assertTrue(SyncStarter.syncsOnAccountChange(addedAtMillis = longAgo, lastSuccessMillis = null, nowMillis = now))
    }
}
