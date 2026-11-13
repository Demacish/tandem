package casa.moller.tandem.smoke

import androidx.test.ext.junit.runners.AndroidJUnit4
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
 * Release smoke test of Fas 5, lane planera (0.7.0, plan §8.3; docs/BYGGE.md "Påminnelser kvällen
 * innan och på morgonen") in the R8-minified sideload app:
 *
 * - A meal planned on the web for tomorrow with the note "Påminn: Tina ut kycklingen": with
 *   "Reminder the evening before" off, the evening worker posts nothing; switched on, the same
 *   worker (`SmokeWorkerReceiver --es worker meal`, a run by hand is for this evening whatever the
 *   time) posts ONE notification on the channel "meal_reminders" with the reminder's words, and a
 *   second run posts nothing more. The process is gone during the runs, as on a real evening.
 */
@RunWith(AndroidJUnit4::class)
class Phase5PlaneraSmokeTest : Phase2SmokeBase() {
    private val device = DeviceState(app)

    override fun servers(): List<FakeTandoor> = listOf(tandoor)

    override fun extraReport(): List<String> = listOf("--- notifications:") + device.postedNotifications().map { it.toString() }

    @Before
    fun allowNotifications() {
        app.shell("pm grant ${TandemApp.PACKAGE} $POST_NOTIFICATIONS")
    }

    @Test
    fun aRemindLineGivesOneNotificationTheEveningBefore() {
        // The device's tomorrow, at noon in the server's zone, so both read the same day.
        val tomorrow = LocalDate.now(ZoneId.systemDefault()).plusDays(1)
        tandoor.mealPlan.web("${tomorrow}T12:00:00", recipe = PASTA_ID, recipeName = PASTA, note = "Dubbel sats.\n$REMINDER_LINE")
        logIn()

        app.killInBackground()
        assertEquals("The worker ran", WORK_SUCCEEDED, runEveningReminder())
        assertTrue("Nothing with the setting off: ${device.postedNotifications()}", reminders().isEmpty())

        app.launch()
        app.tap("More")
        app.tapInList("Sync and storage")
        switchOn(SETTING)
        app.capture("phase5-meal-reminder-setting")
        app.killInBackground()
        assertEquals("The worker ran", WORK_SUCCEEDED, runEveningReminder())
        device.waitUntil("the evening reminder") { reminders().isNotEmpty() }
        device.saveDumps("phase5-meal-reminder")
        val posted = reminders().single()
        assertEquals("On the planned channel: $posted", MEAL_REMINDERS_CHANNEL, posted.channel)
        assertEquals("It says the reminder: $posted", REMINDER, posted.title)
        assertTrue("It names the meal: $posted", posted.text?.contains(PASTA) == true)

        // Once per meal and evening: a second run changes nothing.
        assertEquals("The worker ran", WORK_SUCCEEDED, runEveningReminder())
        assertEquals("Exactly one", listOf(posted), reminders())
    }

    /** The minified build's receiver runs the real evening worker once and tells how it ended. */
    private fun runEveningReminder(): String? {
        val output = app.shell("am broadcast -n ${TandemApp.PACKAGE}/$WORKER_RECEIVER --es worker meal")
        return BROADCAST_DATA.find(output)?.groupValues?.get(1)
    }

    private fun reminders(): List<DeviceState.Posted> = device.postedNotifications().filter { it.channel == MEAL_REMINDERS_CHANNEL }

    /** The switch row [title] on the page, on; again while a tap was lost. */
    private fun switchOn(title: String) {
        val row = By.checkable(true).hasDescendant(By.text(title))
        val on = By.checkable(true).checked(true).hasDescendant(By.text(title))
        app.findInList(row)
        for (attempt in 1..TAP_ATTEMPTS) {
            if (app.device.hasObject(on)) break
            clickAtRest(listOf(row))
            app.device.wait(Until.hasObject(on), TAP_WAIT_MS)
        }
        app.waitFor(on)
    }

    private companion object {
        const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
        const val SETTING = "Reminder the evening before"
        const val REMINDER = "Tina ut kycklingen"
        const val REMINDER_LINE = "Påminn: $REMINDER"
        const val MEAL_REMINDERS_CHANNEL = "meal_reminders"
        const val WORKER_RECEIVER = "casa.moller.tandem.smoke.SmokeWorkerReceiver"
        const val WORK_SUCCEEDED = "SUCCEEDED"
        val BROADCAST_DATA = Regex("""data="([^"]*)"""")
    }
}
