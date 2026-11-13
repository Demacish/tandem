package casa.moller.tandem.smoke

import android.provider.CalendarContract
import android.provider.CalendarContract.Events
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Release smoke test of Fas 5, lane kalender (0.8.0, plan §7 #36; docs/BYGGE.md "Matsedeln i
 * kalendern") in the R8-minified sideload app, against the emulator's real calendar provider:
 *
 * - A dinner planned on the web for tomorrow. Mer → "Meal plan in your calendar" → "Show in the
 *   phone's calendar" (the permissions are granted beforehand, as after "Allow"): the meal is ONE
 *   event "Middag: Krämig svamppasta" at 18:00 in the local calendar of Tandem's own local account.
 *   "Update now" adds nothing (no duplicates); turning it off removes the events.
 */
@RunWith(AndroidJUnit4::class)
class Phase5KalenderSmokeTest : Phase2SmokeBase() {
    override fun servers(): List<FakeTandoor> = listOf(tandoor)

    override fun extraReport(): List<String> = listOf("--- calendar events:") + events().map { it.toString() }

    @Before
    fun allowCalendar() {
        for (permission in listOf(READ_CALENDAR, WRITE_CALENDAR)) app.shell("pm grant ${TandemApp.PACKAGE} $permission")
        // This test APK reads the provider itself to see what a calendar app would show.
        app.shell("pm grant ${InstrumentationRegistry.getInstrumentation().context.packageName} $READ_CALENDAR")
    }

    @Test
    fun turningItOnPutsTheMealInThePhonesCalendar() {
        val tomorrow = LocalDate.now(ZoneId.systemDefault()).plusDays(1)
        tandoor.mealPlan.web("${tomorrow}T12:00:00", recipe = PASTA_ID, recipeName = PASTA)
        logIn()

        app.tap("More")
        app.tapInList(PAGE)
        switch(SETTING, on = true)
        app.device.waitForIdle()
        app.capture("phase5-meal-calendar")
        DeviceState(app).waitUntil("the dinner in the phone's calendar") { events().any { it.title == TITLE } }
        val event = events().single()
        assertEquals("One event with the meal type and the recipe: $event", TITLE, event.title)
        val start = java.time.Instant.ofEpochMilli(event.start).atZone(ZoneId.systemDefault())
        assertEquals("On its day: $event", tomorrow, start.toLocalDate())
        assertEquals("At the meal type's time: $event", 18, start.hour)

        // Writing again changes nothing.
        app.tapInList(REFRESH)
        app.device.wait(Until.hasObject(By.textContains(UPDATED)), TandemApp.UI_TIMEOUT_MS)
        assertEquals("Still one event", listOf(event), events())

        // Off removes the calendar with its events.
        switch(SETTING, on = false)
        DeviceState(app).waitUntil("the calendar removed") { events().isEmpty() }
        assertTrue(events().isEmpty())
    }

    private data class Event(val title: String?, val start: Long)

    /** The events of Tandem's own local calendar, as any calendar app on the phone reads them. */
    private fun events(): List<Event> {
        val resolver = InstrumentationRegistry.getInstrumentation().context.contentResolver
        val selection = "${Events.ACCOUNT_NAME} = ? AND ${Events.ACCOUNT_TYPE} = ? AND ${Events.DELETED} = 0"
        val args = arrayOf(ACCOUNT, CalendarContract.ACCOUNT_TYPE_LOCAL)
        return try {
            resolver.query(Events.CONTENT_URI, arrayOf(Events.TITLE, Events.DTSTART), selection, args, null)?.use { cursor ->
                buildList { while (cursor.moveToNext()) add(Event(cursor.getString(0), cursor.getLong(1))) }
            }.orEmpty()
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    /** The switch row [title] on the page, turned [on]; again while a tap was lost. */
    private fun switch(title: String, on: Boolean) {
        val row = By.checkable(true).hasDescendant(By.text(title))
        val wanted = By.checkable(true).checked(on).hasDescendant(By.text(title))
        app.findInList(row)
        for (attempt in 1..TAP_ATTEMPTS) {
            if (app.device.hasObject(wanted)) break
            clickAtRest(listOf(row))
            app.device.wait(Until.hasObject(wanted), TAP_WAIT_MS)
        }
        app.waitFor(wanted)
    }

    private companion object {
        const val READ_CALENDAR = "android.permission.READ_CALENDAR"
        const val WRITE_CALENDAR = "android.permission.WRITE_CALENDAR"
        const val PAGE = "Meal plan in your calendar"
        const val SETTING = "Show in the phone's calendar"
        const val REFRESH = "Update now"
        const val UPDATED = "Updated"
        const val ACCOUNT = "Tandem"
        const val TITLE = "Middag: $PASTA"
    }
}
