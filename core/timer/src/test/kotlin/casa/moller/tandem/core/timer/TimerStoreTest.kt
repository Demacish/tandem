package casa.moller.tandem.core.timer

import androidx.datastore.core.CorruptionException
import casa.moller.tandem.core.model.timer.CookTimer
import casa.moller.tandem.core.testing.time.TestClock
import casa.moller.tandem.core.timer.store.StoredTimer
import casa.moller.tandem.core.timer.store.StoredTimers
import casa.moller.tandem.core.timer.store.StoredTimersSerializer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The persisted form of the timers (plain JVM: no Android types involved). */
class TimerStoreTest {
    private val clock = TestClock()

    @Test
    fun everyStateSurvivesTheRoundTrip() = runTest {
        val now = clock.now()
        val running = CookTimer.start(1L, "Pasta", 10.minutes, now, recipeId = 4L, stepIndex = 2)
        val paused = CookTimer.start(2L, "Sås", 20.minutes, now).pause(now + 5.minutes)
        val ringing = CookTimer.start(3L, "Ägg", 6.minutes, now).ring(now + 6.minutes)
        val finished = ringing.copy(id = 4L).stopRinging()
        val stored = StoredTimers.of(nextId = 5L, timers = listOf(running, paused, ringing, finished))

        val decoded = StoredTimersSerializer.readFrom(ByteArrayInputStream(encode(stored)))

        assertEquals(5L, decoded.nextId)
        assertEquals(listOf(running, paused, ringing, finished), decoded.toTimers())
    }

    @Test
    fun theFileUsesStableNames() = runTest {
        val json = encode(StoredTimers.of(2L, listOf(CookTimer.start(1L, "Pasta", 10.minutes, clock.now())))).decodeToString()
        listOf("\"version\":1", "\"next_id\":2", "\"duration_ms\":600000", "\"state\":\"RUNNING\"", "\"ends_at_ms\"", "\"created_at_ms\"")
            .forEach { assertTrue("$it in $json", json.contains(it)) }
    }

    @Test
    fun damagedRecordsAreDroppedNotFatal() {
        val good = StoredTimer.of(CookTimer.start(1L, "Pasta", 10.minutes, clock.now()))
        val unknownState = good.copy(id = 2L, state = "EXPLODED")
        val runningWithoutEnd = good.copy(id = 3L, endsAtMillis = null)
        val blankLabel = good.copy(id = 4L, label = " ")
        val timers = StoredTimers(nextId = 5L, timers = listOf(good, unknownState, runningWithoutEnd, blankLabel)).toTimers()
        assertEquals(listOf(1L), timers.map { it.id })
    }

    @Test
    fun unknownKeysFromANewerVersionAreIgnored() = runTest {
        val json = """{"version":2,"next_id":3,"timers":[],"something_new":true}"""
        assertEquals(3L, StoredTimersSerializer.readFrom(ByteArrayInputStream(json.encodeToByteArray())).nextId)
    }

    @Test(expected = CorruptionException::class)
    fun anUnreadableFileIsCorruption() = runTest {
        StoredTimersSerializer.readFrom(ByteArrayInputStream("{ not json".encodeToByteArray()))
    }

    private suspend fun encode(timers: StoredTimers): ByteArray {
        val output = ByteArrayOutputStream()
        StoredTimersSerializer.writeTo(timers, output)
        return output.toByteArray()
    }
}
