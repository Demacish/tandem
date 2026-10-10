package casa.moller.tandem.core.network.http

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenBucketTest {
    private var now = 1_000_000_000L
    private val slept = mutableListOf<Long>()
    private val bucket = TokenBucket(permitsPerSecond = 5, burst = 5, nanoTime = { now }, sleeper = { slept += it })

    private val ms = 1_000_000L

    @Test
    fun burstOfFiveThenFivePerSecond() {
        repeat(5) { assertEquals(0L, bucket.reserve()) }
        assertEquals(200 * ms, bucket.reserve())
        assertEquals(400 * ms, bucket.reserve())
    }

    @Test
    fun refillsOverTime() {
        repeat(7) { bucket.reserve() }
        now += 1_000 * ms
        // 7 reserved at t=0; after 1 s three slots are free again.
        repeat(3) { assertEquals(0L, bucket.reserve()) }
        assertEquals(200 * ms, bucket.reserve())
    }

    @Test
    fun steadyRateNeverWaits() {
        repeat(20) {
            assertEquals(0L, bucket.reserve())
            now += 200 * ms
        }
    }

    @Test
    fun acquireSleepsOnlyWhenNeeded() {
        repeat(6) { bucket.acquire() }
        // One wait of 200 ms, slept in short slices so a cancellation is noticed quickly.
        assertEquals(200 * ms, slept.sum())
        assertTrue(slept.all { it <= TokenBucket.WAIT_SLICE_NANOS })
    }

    @Test
    fun cancelledCallNeitherWaitsNorUsesASlot() {
        assertThrows(IOException::class.java) { bucket.acquire { true } }
        assertTrue(slept.isEmpty())
        // All five burst slots are still free.
        repeat(5) { assertEquals(0L, bucket.reserve()) }
    }

    @Test
    fun callCancelledWhileWaitingGivesItsSlotBack() {
        repeat(5) { bucket.reserve() }
        var checks = 0
        // Not cancelled when it reserves, cancelled after the first wait slice.
        assertThrows(IOException::class.java) { bucket.acquire { checks++ >= 2 } }
        assertEquals(listOf(TokenBucket.WAIT_SLICE_NANOS), slept)
        // The next caller gets the slot the cancelled one gave back: 200 ms, not 400 ms.
        assertEquals(200 * ms, bucket.reserve())
    }

    @Test
    fun callAfterABatchOfCancelledQueuedCallsIsNotDelayed() {
        repeat(5) { bucket.reserve() }
        // E.g. a list screen was left and its 30 queued requests were cancelled.
        repeat(30) { assertThrows(IOException::class.java) { bucket.acquire { true } } }
        assertTrue(slept.isEmpty())
        assertEquals(200 * ms, bucket.reserve())
    }

    @Test
    fun aSlotIsNotGivenBackWhenALaterCallerAlreadyReserved() {
        repeat(5) { bucket.reserve() }
        var cancelled = false
        val later = mutableListOf<Long>()
        assertThrows(IOException::class.java) {
            bucket.acquire {
                // While this caller waits, another one reserves the slot after it; then it is cancelled.
                if (!cancelled && slept.isNotEmpty()) {
                    later += bucket.reserve()
                    cancelled = true
                }
                cancelled
            }
        }
        assertEquals(listOf(400 * ms), later)
        // The slot is kept, so the later caller's place in line never moves.
        assertEquals(600 * ms, bucket.reserve())
    }
}
