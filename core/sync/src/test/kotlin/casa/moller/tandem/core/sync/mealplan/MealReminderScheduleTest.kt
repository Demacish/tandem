package casa.moller.tandem.core.sync.mealplan

import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.database.entity.MealPlanEntity
import casa.moller.tandem.core.database.entity.MealTypeEntity
import casa.moller.tandem.core.domain.mealplan.MealReminder
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.platform.MealReminderNotifier
import casa.moller.tandem.core.sync.reminder.RecordingReminderScheduler
import casa.moller.tandem.core.sync.reminder.ReminderClock
import casa.moller.tandem.core.sync.testing.SyncTestGraph
import casa.moller.tandem.core.sync.testing.SyncTestGraph.Companion.ACCOUNT_ID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.ConscryptMode

/**
 * Fas 5 (planera lane): the evening reminder is planned while its setting is on and a real account
 * is active, again at a new time, cancelled (and its notifications withdrawn) when switched off,
 * and during the evening a meal added to tomorrow (here, on the web or by the sync) is reminded of
 * at once while a removed one takes its notification along.
 */
@RunWith(AndroidJUnit4::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class MealReminderScheduleTest {
    private val stockholm = TimeZone.of("Europe/Stockholm")
    private val graph = SyncTestGraph()
    private val clock = ReminderClock(LocalDateTime.parse("2026-11-13T12:00").toInstant(stockholm), stockholm)
    private val scheduler = RecordingReminderScheduler(clock)
    private val posted: MutableList<MealReminder> = CopyOnWriteArrayList()
    private val cancelled: MutableList<String> = CopyOnWriteArrayList()
    private val notifier = object : MealReminderNotifier {
        override fun canPost() = true

        override fun post(reminder: MealReminder): Boolean = posted.add(reminder)

        override fun cancel(key: String) {
            cancelled += key
        }
    }
    private val reminderCheck =
        MealReminderCheck(graph.settings, graph.sources, graph.databases, graph.mealPlans, graph.network, notifier, clock)
    private val schedule =
        MealReminderSchedule(graph.settings, graph.sources, scheduler, reminderCheck, graph.mealPlans, clock, graph.scope)

    @After
    fun tearDown() {
        graph.close()
    }

    private fun meal(id: Long, note: String, day: String = "2026-11-14") = runBlocking {
        graph.real.mealPlanDao().upsert(
            listOf(
                MealPlanEntity(
                    accountId = ACCOUNT_ID,
                    localId = id,
                    serverId = id,
                    title = "Middag $id",
                    note = note,
                    fromDate = day,
                    toDate = day,
                    mealTypeId = 2,
                ),
            ),
        )
    }

    @Test
    fun thePlanFollowsTheSettingItsTimeAndTheAccount() {
        schedule.start()
        waitFor { scheduler.calls == listOf("cancel MEAL") }
        graph.settings.state.update { it.copy(mealReminders = true) }
        waitFor { scheduler.calls.lastOrNull() == "ensure MEAL 19:00" }
        graph.settings.state.update { it.copy(mealReminderTime = LocalTime(20, 15)) }
        waitFor { scheduler.calls.lastOrNull() == "ensure MEAL 20:15" }
        graph.sources.source.value = ActiveSource.Demo
        waitFor { scheduler.calls.lastOrNull() == "cancel MEAL" }
        graph.sources.source.value = graph.remote
        waitFor { scheduler.calls.lastOrNull() == "ensure MEAL 20:15" }
        graph.settings.state.update { it.copy(mealReminders = false) }
        waitFor { scheduler.calls.lastOrNull() == "cancel MEAL" }
    }

    @Test
    fun duringTheEveningChangesToTomorrowAreFollowedAtOnce() {
        graph.network.online.value = false
        runBlocking {
            graph.real.referenceDao().replaceMealTypes(ACCOUNT_ID, listOf(MealTypeEntity(ACCOUNT_ID, 2, "Middag", 0, "18:00:00")))
        }
        clock.instant = LocalDateTime.parse("2026-11-13T20:00").toInstant(stockholm)
        graph.settings.state.update { it.copy(mealReminders = true) }
        schedule.start()

        meal(1, "Påminn: Tina ut kycklingen")
        waitFor { posted.map { it.text } == listOf("Tina ut kycklingen") }
        // Removed by the sync (deleted on the web): its notification goes.
        runBlocking { graph.real.mealPlanDao().delete(ACCOUNT_ID, 1) }
        waitFor { cancelled.size == 1 }
        assertEquals(posted.single().key, cancelled.single())

        // Switching it off withdraws what is still posted.
        meal(2, "Påminn: Blötlägg bönorna")
        waitFor { posted.size == 2 }
        graph.settings.state.update { it.copy(mealReminders = false) }
        waitFor { posted.last().key in cancelled }
        assertTrue(scheduler.calls.last() == "cancel MEAL")
    }

    private fun waitFor(condition: () -> Boolean) {
        val until = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (!condition()) {
            check(System.currentTimeMillis() < until) { "timed out" }
            Thread.sleep(POLL_MILLIS)
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 15_000L
        const val POLL_MILLIS = 20L
    }
}
