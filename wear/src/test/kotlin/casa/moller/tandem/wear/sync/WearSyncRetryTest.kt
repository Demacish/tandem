package casa.moller.tandem.wear.sync

import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.8.0 review: a check made on the watch while the server could not be reached never waits hours
 * for WorkManager's backoff once the server is back.
 */
class WearSyncRetryTest {
    @Test
    fun theWorkerRetriesAFewTimesThenPlansTheNextTryInAQuarterOfAnHour() {
        // 30 s, 1, 2, 4 min through WorkManager, then the worker ends and plans 15 min.
        assertTrue((0 until WearSyncRetry.MAX_RETRIES).all(WearSyncRetry::retries))
        assertFalse(WearSyncRetry.retries(WearSyncRetry.MAX_RETRIES))
        assertFalse(WearSyncRetry.retries(WearSyncRetry.MAX_RETRIES + 10))
        assertEquals(30_000L, WearSyncRetry.BACKOFF_MILLIS)
        assertEquals(15 * 60_000L, WearSyncRetry.LATER_MILLIS)
        // The longest WorkManager wait (the last retry) stays below the planned quarter of an hour.
        val longestBackoff = WearSyncRetry.BACKOFF_MILLIS shl (WearSyncRetry.MAX_RETRIES - 1)
        assertTrue(longestBackoff < WearSyncRetry.LATER_MILLIS)
    }

    @Test
    fun aTapReplacesAJobThatWaitsOutARetry() {
        assertEquals(ExistingWorkPolicy.REPLACE, WearSyncRetry.policyForNow(listOf(WorkInfo.State.ENQUEUED to 2)))
        assertEquals(
            ExistingWorkPolicy.REPLACE,
            WearSyncRetry.policyForNow(listOf(WorkInfo.State.SUCCEEDED to 0, WorkInfo.State.ENQUEUED to 1)),
        )
    }

    @Test
    fun aTapKeepsAPlannedOrRunningJob() {
        // Planned and never run (it waits for the network): kept, it sends every check.
        assertEquals(ExistingWorkPolicy.KEEP, WearSyncRetry.policyForNow(listOf(WorkInfo.State.ENQUEUED to 0)))
        // Running: kept, a check made meanwhile goes in the same job.
        assertEquals(ExistingWorkPolicy.KEEP, WearSyncRetry.policyForNow(listOf(WorkInfo.State.RUNNING to 3)))
        // Nothing there, or only finished jobs: KEEP enqueues a new one.
        assertEquals(ExistingWorkPolicy.KEEP, WearSyncRetry.policyForNow(emptyList()))
        assertEquals(ExistingWorkPolicy.KEEP, WearSyncRetry.policyForNow(listOf(WorkInfo.State.FAILED to 4)))
    }
}
