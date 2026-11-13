package casa.moller.tandem.core.sync.calendar

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import casa.moller.tandem.core.database.entity.MealPlanEntity
import casa.moller.tandem.core.database.entity.MealTypeEntity
import casa.moller.tandem.core.datastore.LocalCalendarRecord
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.reminder.ReminderClock
import casa.moller.tandem.core.sync.testing.SyncTestGraph
import casa.moller.tandem.core.sync.testing.SyncTestGraph.Companion.ACCOUNT_ID
import casa.moller.tandem.core.sync.work.MealCalendarJob
import casa.moller.tandem.core.sync.work.MealCalendarWorker
import casa.moller.tandem.core.sync.work.WorkManagerMealCalendarWork
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.ConscryptMode

/**
 * Fas 5 (kalender lane, plan §7 #36): the local calendar follows the stored plan while the app
 * lives (a meal added or removed by the sync is written within seconds), the twice-daily work
 * exists exactly while an option is on for a real account, and logging out or demo mode removes
 * the calendar and turns the option off. The work's run reads the plan when online and writes it.
 */
@RunWith(AndroidJUnit4::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class MealCalendarScheduleTest {
    private val stockholm = TimeZone.of("Europe/Stockholm")
    private val graph = SyncTestGraph()
    private val clock = ReminderClock(LocalDateTime.parse("2026-11-13T12:00").toInstant(stockholm), stockholm)
    private val store = FakeMealCalendarStore()
    private val work = RecordingMealCalendarWork()
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var provider: FakeCalendarProvider
    private lateinit var calendar: PhoneMealCalendar
    private lateinit var links: MealCalendarLinks

    @Before
    fun setUp() {
        provider = FakeCalendarProvider.install()
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        calendar = PhoneMealCalendar(AndroidCalendarGateway(app), graph.sources, graph.mealPlans, store, clock)
        links = MealCalendarLinks(graph.sources, graph.databases, graph.caller, clock)
        graph.network.online.value = false
        runBlocking {
            graph.real.referenceDao().replaceMealTypes(ACCOUNT_ID, listOf(MealTypeEntity(ACCOUNT_ID, 2, "Middag", 0, "18:00:00")))
        }
    }

    @After
    fun tearDown() {
        graph.close()
    }

    private fun meal(id: Long, day: String) = runBlocking {
        val row = MealPlanEntity(
            accountId = ACCOUNT_ID,
            localId = id,
            serverId = id,
            title = "Middag $id",
            fromDate = day,
            toDate = day,
            mealTypeId = 2,
        )
        graph.real.mealPlanDao().upsert(listOf(row))
    }

    private fun schedule() = MealCalendarSchedule(store, graph.sources, links, calendar, work, graph.mealPlans, clock, graph.scope)

    @Test
    fun theCalendarFollowsThePlanAndGoesWithTheAccount() {
        meal(1, "2026-11-14")
        schedule().start()
        waitFor { work.calls == listOf("cancel") }
        assertTrue(provider.calendars().isEmpty())

        runBlocking { store.setEnabled(true) }
        waitFor { provider.events().size == 1 }
        assertEquals("ensure", work.calls.last())

        // The sync brings a new meal and removes the old one: the calendar follows.
        meal(2, "2026-11-15")
        waitFor { provider.events().size == 2 }
        runBlocking { graph.real.mealPlanDao().delete(ACCOUNT_ID, 1) }
        waitFor { provider.events().map { it["title"] } == listOf("Middag: Middag 2") }

        // Logged out: the calendar is removed and the option is off.
        graph.sources.source.value = ActiveSource.None
        waitFor { provider.calendars().isEmpty() && !store.state.value.enabled }
        assertEquals("cancel", work.calls.last())
    }

    @Test
    fun demoModeRemovesTheCalendarAndTurnsItOff() {
        meal(1, "2026-11-14")
        store.state.value = LocalCalendarRecord(enabled = true)
        schedule().start()
        waitFor { provider.events().size == 1 }
        graph.sources.source.value = ActiveSource.Demo
        waitFor { provider.calendars().isEmpty() }
        waitFor { !store.state.value.enabled }
    }

    @Test
    fun theWorkRunReadsThePlanWhenOnlineAndWritesIt() {
        val job = MealCalendarJob(calendar, links, store, graph.sources, graph.mealPlans, graph.network)
        meal(1, "2026-11-14")
        runBlocking { job.run() }
        // Off: nothing written, nothing read.
        assertTrue(provider.calendars().isEmpty())
        assertTrue(graph.server.requestsTo("GET", "/api/meal-plan/").isEmpty())

        store.state.value = LocalCalendarRecord(enabled = true)
        runBlocking { job.run() }
        assertEquals(1, provider.events().size)
        assertTrue(graph.server.requestsTo("GET", "/api/meal-plan/").isEmpty())

        graph.network.online.value = true
        runBlocking { job.run() }
        assertFalse(graph.server.requestsTo("GET", "/api/meal-plan/").isEmpty())
        // No link: no token request.
        assertTrue(graph.server.requests.none { it.contains("access-token") })
    }

    @Test
    fun theWorkIsPeriodicTwiceADay() {
        val context: Context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val scheduler = WorkManagerMealCalendarWork(context)
        scheduler.ensure()
        scheduler.ensure()
        val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(WorkManagerMealCalendarWork.NAME).get()
        val planned = infos.single()
        assertEquals(WorkInfo.State.ENQUEUED, planned.state)
        assertEquals(TimeUnit.HOURS.toMillis(12), planned.periodicityInfo?.repeatIntervalMillis)
        assertTrue(MealCalendarWorker::class.java.name in planned.tags)
        scheduler.cancel()
        val after = WorkManager.getInstance(context).getWorkInfosForUniqueWork(WorkManagerMealCalendarWork.NAME).get()
        assertTrue(after.all { it.state == WorkInfo.State.CANCELLED })
    }

    private fun waitFor(condition: () -> Boolean) {
        val until = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (!condition()) {
            check(System.currentTimeMillis() < until) { "timed out; work: ${work.calls}, events: ${provider.events()}" }
            Thread.sleep(POLL_MILLIS)
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
        const val POLL_MILLIS = 20L
    }
}
