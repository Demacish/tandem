package casa.moller.tandem.core.sync.platform

import casa.moller.tandem.core.model.account.ServerAccount
import casa.moller.tandem.core.model.timer.TimerSource
import casa.moller.tandem.core.model.timer.TimerStartResult
import casa.moller.tandem.core.model.timer.TimerState
import casa.moller.tandem.core.network.api.TandoorApiFactory
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.testing.FakeSources
import casa.moller.tandem.core.testing.data.FakeTimerController
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Enhetstestet B17: no demo timer is left once demo mode is left (a finished one showed up in cook mode). */
@OptIn(ExperimentalCoroutinesApi::class)
class DemoTimerCleanupTest {
    private val timers = FakeTimerController()
    private val account = ServerAccount(
        id = "account-a",
        baseUrl = "https://tandoor.example.com",
        username = "cook",
        userId = 1,
        spaceId = 1,
        addedAt = Instant.fromEpochMilliseconds(0),
    )
    private val remote = ActiveSource.Remote(
        account,
        TandoorApiFactory(OkHttpClient()).create("https://tandoor.example.com/", { null }),
    )

    private fun TestScope.start(sources: FakeSources) {
        DemoTimerCleanup(sources, timers, TestScope(StandardTestDispatcher(testScheduler))).start()
        runCurrent()
    }

    private suspend fun startTimer(label: String, source: String?, seconds: Int = 600): Long {
        val started = timers.start(label, seconds.seconds, recipeId = 6, stepIndex = 0, sourceKey = source)
        return (started as TimerStartResult.Started).timer.id
    }

    @Test
    fun loggingInRemovesEveryDemoTimerAndKeepsTheOthers() = runTest {
        val sources = FakeSources(ActiveSource.Demo)
        start(sources)
        startTimer("Potatis · 40 s", TimerSource.DEMO, seconds = 40)
        startTimer("Sås · 10 min", TimerSource.DEMO)
        val accounts = startTimer("Pasta · 10 min", account.id)
        val free = startTimer("Ägg", source = null)
        // "Potatis · 40 s" rings and is stopped: a finished demo timer, as in the report.
        timers.advanceTimeBy(1.minutes)
        timers.stopRinging(timers.timers.value.first { it.label.startsWith("Potatis") }.id)
        assertEquals(TimerState.FINISHED, timers.timers.value.first { it.label.startsWith("Potatis") }.state)
        runCurrent()
        assertEquals("demo mode keeps its timers while it lasts", 4, timers.timers.value.size)

        sources.source.value = remote
        runCurrent()

        assertEquals(listOf(accounts, free), timers.timers.value.map { it.id })
        assertTrue(timers.timers.value.none { it.sourceKey == TimerSource.DEMO })
    }

    @Test
    fun leavingDemoModeForTheIntroRemovesThemToo() = runTest {
        val sources = FakeSources(ActiveSource.Demo)
        start(sources)
        startTimer("Potatis · 40 s", TimerSource.DEMO)

        sources.source.value = ActiveSource.None
        runCurrent()

        assertTrue(timers.timers.value.isEmpty())
    }

    /** The app was closed in demo mode and the next process starts logged in: the stored demo timer goes. */
    @Test
    fun aDemoTimerFromAnEarlierProcessGoesAtTheFirstSource() = runTest {
        startTimer("Potatis · 40 s", TimerSource.DEMO)
        start(FakeSources(remote))
        assertTrue(timers.timers.value.isEmpty())
    }

    @Test
    fun nothingIsRemovedWhileTheSourceIsStillLoading() = runTest {
        startTimer("Potatis · 40 s", TimerSource.DEMO)
        val sources = FakeSources(ActiveSource.Loading)
        start(sources)
        assertEquals(1, timers.timers.value.size)

        sources.source.value = ActiveSource.Demo
        runCurrent()
        assertEquals("a process that starts in demo mode keeps them", 1, timers.timers.value.size)
    }
}
