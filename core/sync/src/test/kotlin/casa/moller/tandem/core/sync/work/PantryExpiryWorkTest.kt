package casa.moller.tandem.core.sync.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import casa.moller.tandem.core.domain.pantry.ExpiryReminderSummary
import casa.moller.tandem.core.model.settings.AppSettings
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.pantry.PantryExpiryCheck
import casa.moller.tandem.core.sync.pantry.PantryExpiryReminders
import casa.moller.tandem.core.sync.platform.AppVisibility
import casa.moller.tandem.core.sync.platform.PantryExpiryNotifier
import casa.moller.tandem.core.sync.platform.WallClock
import casa.moller.tandem.core.sync.testing.FakeSettingsStore
import casa.moller.tandem.core.sync.testing.SyncTestGraph
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.ConscryptMode

/**
 * Fas 4 (skafferinotis lane): the WorkManager side of the morning expiry check: the worker runs the
 * check and always succeeds, the request (24 hours, battery not low, no network needed, first run
 * at the next 08:00 of the zone), and the schedule following the setting and the account.
 */
@RunWith(AndroidJUnit4::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class PantryExpiryWorkTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val stockholm = TimeZone.of("Europe/Stockholm")

    private class FixedClock(val instant: Instant, val timeZone: TimeZone) : WallClock {
        override fun now(): Instant = instant

        override fun zone(): TimeZone = timeZone
    }

    private class RecordingScheduler : PantryExpiryScheduler {
        val calls: MutableList<String> = CopyOnWriteArrayList()

        override fun schedule() {
            calls += "schedule"
        }

        override fun cancel() {
            calls += "cancel"
        }
    }

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    @Test
    fun theRequestRunsDailyFromTheNextMorningWithoutNeedingANetwork() {
        val now = LocalDateTime.parse("2026-11-05T21:00").toInstant(stockholm)
        val scheduler = WorkManagerPantryExpiryScheduler(context, FixedClock(now, stockholm))

        scheduler.schedule()

        val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(WorkManagerPantryExpiryScheduler.WORK_NAME).get()
        assertEquals(1, infos.size)
        assertEquals(WorkInfo.State.ENQUEUED, infos.single().state)
        val request = WorkManagerPantryExpiryScheduler.request(11.hours)
        assertEquals(24.hours.inWholeMilliseconds, request.workSpec.intervalDuration)
        assertEquals(11.hours.inWholeMilliseconds, request.workSpec.initialDelay)
        assertTrue(request.workSpec.constraints.requiresBatteryNotLow())
        assertEquals(NetworkType.NOT_REQUIRED, request.workSpec.constraints.requiredNetworkType)
        assertTrue(WorkManagerPantryExpiryScheduler.TAG in infos.single().tags)

        // Scheduling again (every app start) keeps the one schedule; cancelling removes it.
        scheduler.schedule()
        assertEquals(1, WorkManager.getInstance(context).getWorkInfosForUniqueWork(WorkManagerPantryExpiryScheduler.WORK_NAME).get().size)
        scheduler.cancel()
        val cancelled = WorkManager.getInstance(context).getWorkInfosForUniqueWork(WorkManagerPantryExpiryScheduler.WORK_NAME).get()
        assertTrue(cancelled.all { it.state == WorkInfo.State.CANCELLED })
    }

    @Test
    fun theWorkerRunsTheCheckAndAlwaysSucceeds() {
        val graph = SyncTestGraph()
        try {
            val posted = mutableListOf<ExpiryReminderSummary>()
            val notifier = object : PantryExpiryNotifier {
                override fun canPost() = true

                override fun post(summary: ExpiryReminderSummary, today: LocalDate, locationNames: Map<Long, String>): Boolean = posted.add(summary)
            }
            val check = PantryExpiryCheck(
                settings = graph.settings,
                sources = graph.sources,
                databases = graph.databases,
                pantry = graph.pantry,
                makeNow = graph.makeNow,
                reader = graph.pantryReader,
                network = graph.network,
                visibility = AppVisibility { false },
                notifier = notifier,
                clock = FixedClock(LocalDateTime.parse("2026-10-06T08:00").toInstant(stockholm), stockholm),
            )
            val factory = object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                    PantryExpiryWorker(appContext, workerParameters, check)
            }
            val worker = TestListenableWorkerBuilder<PantryExpiryWorker>(context).setWorkerFactory(factory).build()

            // Off (the default): nothing, still a success.
            assertEquals(ListenableWorker.Result.success(), runBlocking { worker.doWork() })
            // On, with nothing in the pantry: nothing, still a success.
            graph.settings.state.update { it.copy(pantryExpiryNotifications = true) }
            assertEquals(ListenableWorker.Result.success(), runBlocking { worker.doWork() })
            assertTrue(posted.isEmpty())
        } finally {
            graph.close()
        }
    }

    @Test
    fun theScheduleFollowsTheSettingAndTheAccount() {
        val graph = SyncTestGraph()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val settings = FakeSettingsStore(AppSettings.Default)
            val scheduler = RecordingScheduler()
            PantryExpiryReminders(settings, graph.sources, scheduler, scope).start()
            waitFor { scheduler.calls == listOf("cancel") }

            settings.state.update { it.copy(pantryExpiryNotifications = true) }
            waitFor { scheduler.calls == listOf("cancel", "schedule") }

            // Demo mode and logging out cancel it; back to the account schedules it again.
            graph.sources.source.value = ActiveSource.Demo
            waitFor { scheduler.calls == listOf("cancel", "schedule", "cancel") }
            graph.sources.source.value = graph.remote
            waitFor { scheduler.calls == listOf("cancel", "schedule", "cancel", "schedule") }
            settings.state.update { it.copy(pantryExpiryNotifications = false) }
            waitFor { scheduler.calls.lastOrNull() == "cancel" && scheduler.calls.size == 5 }
        } finally {
            scope.cancel()
            graph.close()
        }
    }

    private fun waitFor(condition: () -> Boolean) {
        val until = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (!condition()) {
            check(System.currentTimeMillis() < until) { "timed out" }
            Thread.sleep(POLL_MILLIS)
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
        const val POLL_MILLIS = 20L
    }
}
