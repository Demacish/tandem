package casa.moller.tandem.core.sync.testing

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.ConscryptMode

/**
 * The helper's end of a test (Fas 4's completeness check): nothing on the graph's scope may outlive
 * [SyncTestGraph.close], or it hits the closed database and the next test gets the blame.
 */
@RunWith(AndroidJUnit4::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class SyncTestGraphTest {
    @Test
    fun closeWaitsForWhatStillRunsOnTheScope() {
        val graph = SyncTestGraph()
        val started = CountDownLatch(1)
        val finished = AtomicBoolean(false)
        graph.scope.launch {
            withContext(NonCancellable) {
                started.countDown()
                delay(STILL_RUNNING_MILLIS)
                finished.set(true)
            }
        }
        assertTrue(started.await(WAIT_SECONDS, TimeUnit.SECONDS))

        graph.close()

        assertTrue("The scope's work outlived close()", finished.get())
    }

    private companion object {
        const val STILL_RUNNING_MILLIS = 300L
        const val WAIT_SECONDS = 5L
    }
}
