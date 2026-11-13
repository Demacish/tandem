package casa.moller.tandem.core.sync.mealplan

import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.database.entity.MealPlanEntity
import casa.moller.tandem.core.database.entity.MealTypeEntity
import casa.moller.tandem.core.domain.mealplan.MealReminder
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.pantry.PantrySyncEnv
import casa.moller.tandem.core.sync.platform.MealReminderNotifier
import casa.moller.tandem.core.sync.reminder.ReminderClock
import casa.moller.tandem.core.sync.testing.SyncTestGraph
import casa.moller.tandem.core.sync.testing.SyncTestGraph.Companion.ACCOUNT_ID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Instant
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
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
 * Fas 5 (planera lane), plan §8.3: the evening check behind "Tina ut kycklingen" works offline from
 * the local plan, posts one notification per meal with a reminder once per evening, removes the
 * notification when the meal moves, is removed or loses its reminder (also through the sync), and
 * never posts when off, logged out, in demo mode, for a guest, past midnight or without permission.
 */
@RunWith(AndroidJUnit4::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class MealReminderCheckTest {
    private val stockholm = TimeZone.of("Europe/Stockholm")
    private val graph = SyncTestGraph()
    private val clock = ReminderClock(at("2026-11-13T19:00"), stockholm)
    private val notifier = RecordingNotifier()
    private val check = MealReminderCheck(graph.settings, graph.sources, graph.databases, graph.mealPlans, graph.network, notifier, clock)
    private val friday = LocalDate(2026, 11, 13)
    private val saturday = LocalDate(2026, 11, 14)

    private class RecordingNotifier : MealReminderNotifier {
        @Volatile
        var allowed = true
        val posted: MutableList<MealReminder> = CopyOnWriteArrayList()
        val cancelled: MutableList<String> = CopyOnWriteArrayList()

        override fun canPost(): Boolean = allowed

        override fun post(reminder: MealReminder): Boolean = allowed && posted.add(reminder)

        override fun cancel(key: String) {
            cancelled += key
        }
    }

    private fun at(time: String, zone: TimeZone = stockholm): Instant = LocalDateTime.parse(time).toInstant(zone)

    private fun setUp(on: Boolean = true) = runBlocking {
        graph.network.online.value = false
        graph.settings.state.update { it.copy(mealReminders = on) }
        graph.real.referenceDao().replaceMealTypes(
            ACCOUNT_ID,
            listOf(MealTypeEntity(ACCOUNT_ID, DINNER, "Middag", 1, "18:00:00"), MealTypeEntity(ACCOUNT_ID, LUNCH, "Lunch", 0, "12:00:00")),
        )
    }

    private fun meal(id: Long, title: String, note: String, day: LocalDate = saturday, type: Long = DINNER) = runBlocking {
        graph.real.mealPlanDao().upsert(
            listOf(
                MealPlanEntity(
                    accountId = ACCOUNT_ID,
                    localId = id,
                    serverId = id,
                    title = title,
                    note = note,
                    fromDate = day.toString(),
                    toDate = day.toString(),
                    mealTypeId = type,
                ),
            ),
        )
    }

    private fun run(evening: LocalDate = friday, onTime: Boolean = true) = runBlocking { check.run(evening, onTime) }

    @After
    fun tearDown() {
        graph.close()
    }

    @Test
    fun tomorrowsMealsWithAReminderGetOneNotificationEachOncePerEvening() {
        setUp()
        meal(1, "Kycklinggryta", "Dubbel sats.\nPåminn: Tina ut kycklingen")
        meal(2, "Soppa", "Remind: Soak the beans", type = LUNCH)
        meal(3, "Pasta", "Ingen påminnelse")
        meal(4, "Fisk", "Påminn: Tina ut fisken", day = LocalDate(2026, 11, 15))

        assertEquals(MealReminderOutcome.POSTED, run())
        assertEquals(listOf("Soak the beans", "Tina ut kycklingen"), notifier.posted.map { it.text })
        assertEquals(listOf("Lunch", "Middag"), notifier.posted.map { it.mealTypeName })
        assertTrue(notifier.posted.all { it.date == saturday })

        // The same evening again (the in-app watcher, a second run): nothing new.
        assertEquals(MealReminderOutcome.NOTHING_NEW, run())
        assertEquals(2, notifier.posted.size)
        assertTrue(notifier.cancelled.isEmpty())
    }

    @Test
    fun aMealThatMovesOrLosesItsReminderTakesItsNotificationAlong() {
        setUp()
        meal(1, "Kycklinggryta", "Påminn: Tina ut kycklingen")
        meal(2, "Soppa", "Påminn: Blötlägg bönorna")
        run()
        val chicken = notifier.posted.first { it.mealId == 1L }.key
        val soup = notifier.posted.first { it.mealId == 2L }.key

        // Moved to Sunday (as the sync would write it) and the soup's reminder removed on the web.
        meal(1, "Kycklinggryta", "Påminn: Tina ut kycklingen", day = LocalDate(2026, 11, 15))
        meal(2, "Soppa", "")
        assertEquals(MealReminderOutcome.NOTHING_NEW, run(onTime = false))
        assertEquals(setOf(chicken, soup), notifier.cancelled.toSet())

        // Back on Saturday with a new text: a new reminder.
        meal(2, "Soppa", "Påminn: Koka buljong")
        assertEquals(MealReminderOutcome.POSTED, run(onTime = false))
        assertEquals("Koka buljong", notifier.posted.last().text)
    }

    @Test
    fun aMealPlannedOfflineIsRemindedOfAndKeepsItsReminderWhenSent() {
        setUp()
        meal(-3, "Gryta", "Påminn: Tina ut")
        assertEquals(MealReminderOutcome.POSTED, run())
        // The outbox sent it: the row now has its server id, the reminder is the same one.
        runBlocking { graph.real.mealPlanDao().delete(ACCOUNT_ID, -3) }
        meal(41, "Gryta", "Påminn: Tina ut")
        assertEquals(MealReminderOutcome.NOTHING_NEW, run(onTime = false))
        assertEquals(1, notifier.posted.size)
        assertTrue(notifier.cancelled.isEmpty())
    }

    @Test
    fun beforeTheChosenTimeTheWatcherWaitsAndPastMidnightNothingComes() {
        setUp()
        meal(1, "Kycklinggryta", "Påminn: Tina ut kycklingen")
        clock.instant = at("2026-11-13T18:30")
        assertEquals(MealReminderOutcome.EARLY, run(onTime = false))
        graph.settings.state.update { it.copy(mealReminderTime = LocalTime(18, 0)) }
        assertEquals(MealReminderOutcome.POSTED, run(onTime = false))

        // A run for Friday evening that only starts on Saturday morning posts nothing.
        notifier.posted.clear()
        clock.instant = at("2026-11-14T00:30")
        assertEquals(MealReminderOutcome.TOO_LATE, run())
        assertTrue(notifier.posted.isEmpty())
    }

    @Test
    fun theDayIsThePhonesEveningInItsZone() {
        setUp()
        meal(1, "Kycklinggryta", "Påminn: Tina ut kycklingen")
        // 19:00 in New York is already Saturday in Stockholm: the phone's evening is Friday there.
        clock.timeZone = TimeZone.of("America/New_York")
        clock.instant = at("2026-11-13T19:00", TimeZone.of("America/New_York"))
        assertEquals(MealReminderOutcome.POSTED, run(friday))
    }

    @Test
    fun offLoggedOutDemoGuestAndNoPermissionPostNothing() {
        setUp(on = false)
        meal(1, "Kycklinggryta", "Påminn: Tina ut kycklingen")
        assertEquals(MealReminderOutcome.OFF, run())
        graph.settings.state.update { it.copy(mealReminders = true) }

        notifier.allowed = false
        assertEquals(MealReminderOutcome.NOT_ALLOWED, run())
        notifier.allowed = true

        graph.sources.source.value = ActiveSource.Demo
        assertEquals(MealReminderOutcome.DEMO, run())
        graph.sources.source.value = ActiveSource.None
        assertEquals(MealReminderOutcome.NO_ACCOUNT, run())
        graph.sources.source.value = graph.remote

        runBlocking { PantrySyncEnv.mirror(graph, groupLevel = "guest") }
        assertEquals(MealReminderOutcome.GUEST, run())
        assertTrue(notifier.posted.isEmpty())
    }

    @Test
    fun switchingItOffWithdrawsThePostedReminders() {
        setUp()
        meal(1, "Kycklinggryta", "Påminn: Tina ut kycklingen")
        run()
        val key = notifier.posted.single().key
        runBlocking { check.withdrawAll() }
        assertEquals(listOf(key), notifier.cancelled)
    }

    private companion object {
        const val DINNER = 2L
        const val LUNCH = 1L
    }
}
