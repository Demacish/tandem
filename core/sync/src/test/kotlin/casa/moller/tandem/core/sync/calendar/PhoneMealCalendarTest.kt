package casa.moller.tandem.core.sync.calendar

import android.Manifest
import android.app.Application
import android.provider.CalendarContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.database.entity.MealPlanEntity
import casa.moller.tandem.core.database.entity.MealTypeEntity
import casa.moller.tandem.core.datastore.LocalCalendarRecord
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.reminder.ReminderClock
import casa.moller.tandem.core.sync.testing.SyncTestGraph
import casa.moller.tandem.core.sync.testing.SyncTestGraph.Companion.ACCOUNT_ID
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
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
 * Fas 5 (kalender lane, plan §7 #36): "Visa i telefonens kalender" writes the stored plan into the
 * phone's calendar provider ([FakeCalendarProvider], the real gateway): its own local calendar, one
 * event per meal (timed when the meal type has a time, else all-day), nothing twice, a moved meal
 * moves and a removed one disappears; turning it off removes the calendar. Offline: only Room.
 */
@RunWith(AndroidJUnit4::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class PhoneMealCalendarTest {
    private val stockholm = TimeZone.of("Europe/Stockholm")
    private val graph = SyncTestGraph()
    private val clock = ReminderClock(LocalDateTime.parse("2026-11-13T12:00").toInstant(stockholm), stockholm)
    private val store = FakeMealCalendarStore(LocalCalendarRecord(enabled = true))
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var provider: FakeCalendarProvider
    private lateinit var calendar: PhoneMealCalendar

    @Before
    fun setUp() {
        provider = FakeCalendarProvider.install()
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        calendar = PhoneMealCalendar(AndroidCalendarGateway(app), graph.sources, graph.mealPlans, store, clock)
        graph.network.online.value = false
        runBlocking {
            graph.real.referenceDao().replaceMealTypes(
                ACCOUNT_ID,
                listOf(MealTypeEntity(ACCOUNT_ID, 2, "Middag", 1, "18:00:00"), MealTypeEntity(ACCOUNT_ID, 3, "Matlåda", 0, null)),
            )
        }
    }

    @After
    fun tearDown() {
        graph.close()
    }

    private fun meal(id: Long, day: String, type: Long = 2, title: String = "Kycklinggryta", note: String = "") = runBlocking {
        graph.real.mealPlanDao().upsert(
            listOf(
                MealPlanEntity(
                    accountId = ACCOUNT_ID,
                    localId = id,
                    serverId = id,
                    title = title,
                    note = note,
                    fromDate = day,
                    toDate = day,
                    mealTypeId = type,
                ),
            ),
        )
    }

    private fun write(): Outcome<Int> = runBlocking { calendar.write() }

    private fun local(millis: Long): LocalDateTime = Instant.fromEpochMilliseconds(millis).toLocalDateTime(stockholm)

    @Test
    fun theWeeksMealsAppearInTheirOwnLocalCalendar() {
        meal(1, "2026-11-14", note = "Dubbel sats.")
        meal(2, "2026-11-15", type = 3, title = "Rester")
        assertEquals(Outcome.Success(2), write())

        val own = provider.calendars().single()
        assertEquals("Tandem", own["account_name"])
        assertEquals(CalendarContract.ACCOUNT_TYPE_LOCAL, own["account_type"])
        assertEquals("tandem_meal_plan", own["name"])
        assertEquals(CalendarContract.Calendars.CAL_ACCESS_READ.toLong(), own["calendar_access_level"])
        val (dinner, lunchbox) = provider.events()
        assertEquals("Middag: Kycklinggryta", dinner["title"])
        assertEquals("Dubbel sats.", dinner["description"])
        assertEquals(LocalDateTime.parse("2026-11-14T18:00"), local(dinner["dtstart"] as Long))
        assertEquals(LocalDateTime.parse("2026-11-14T19:00"), local(dinner["dtend"] as Long))
        assertEquals(0L, dinner["allDay"])
        assertEquals("Europe/Stockholm", dinner["eventTimezone"])
        assertEquals("$ACCOUNT_ID/1", dinner["sync_data1"])
        assertEquals("Matlåda: Rester", lunchbox["title"])
        assertEquals(1L, lunchbox["allDay"])
        assertEquals("UTC", lunchbox["eventTimezone"])
        assertEquals(2, store.state.value.events)
        assertEquals(null, store.state.value.problem)
    }

    @Test
    fun aSecondWriteChangesNothing() {
        meal(1, "2026-11-14")
        meal(2, "2026-11-16")
        write()
        val before = provider.events()
        provider.writes.clear()
        assertEquals(Outcome.Success(2), write())
        assertEquals(emptyList<String>(), provider.writes)
        assertEquals(before, provider.events())
        assertEquals(1, provider.calendars().size)
    }

    @Test
    fun movedRemovedAndForeignEventsAreBroughtInStep() {
        meal(1, "2026-11-14")
        meal(2, "2026-11-16")
        write()
        val calendarId = provider.calendars().single()["_id"] as Long
        // Someone added an event to the read-only calendar anyway: it does not belong there.
        provider.addForeignEvent(calendarId, "Tandvården")
        // Meal 1 moved to Tuesday by the sync, meal 2 removed on the web.
        meal(1, "2026-11-17")
        runBlocking { graph.real.mealPlanDao().delete(ACCOUNT_ID, 2) }
        write()
        val events = provider.events()
        assertEquals(listOf("Middag: Kycklinggryta"), events.map { it["title"] })
        assertEquals(LocalDateTime.parse("2026-11-17T18:00"), local(events.single()["dtstart"] as Long))
    }

    @Test
    fun onlyTheSyncWindowIsWritten() {
        meal(1, "2026-10-01")
        meal(2, "2026-10-30")
        meal(3, "2026-12-25")
        meal(4, "2026-12-26")
        write()
        assertEquals(listOf("$ACCOUNT_ID/2", "$ACCOUNT_ID/3"), provider.events().map { it["sync_data1"] })
    }

    @Test
    fun offOrWithoutAnAccountNothingIsWrittenAndRemoveTakesTheCalendar() {
        meal(1, "2026-11-14")
        write()
        assertTrue(runBlocking { calendar.remove() })
        assertTrue(provider.calendars().isEmpty())
        assertTrue(provider.events().isEmpty())
        assertFalse(runBlocking { calendar.remove() })

        store.state.value = LocalCalendarRecord(enabled = false)
        assertEquals(Outcome.Success(0), write())
        assertTrue(provider.calendars().isEmpty())

        store.state.value = LocalCalendarRecord(enabled = true)
        graph.sources.source.value = ActiveSource.Demo
        assertTrue(write() is Outcome.Failure)
        assertTrue(provider.calendars().isEmpty())
    }

    @Test
    fun aRevokedPermissionIsRecordedNotThrown() {
        shadowOf(app).denyPermissions(Manifest.permission.WRITE_CALENDAR)
        assertTrue(write() is Outcome.Failure)
        assertEquals("PERMISSION", store.state.value.problem)
        assertFalse(calendar.hasPermission())
        assertFalse(runBlocking { calendar.remove() })
    }
}
