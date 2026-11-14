package casa.moller.tandem.smoke

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Release smoke test of Fas 5, lane butik (0.8.0, plan §7 #34; docs/BYGGE.md "Påminnelse nära
 * butiken") in the R8-minified sideload app:
 *
 * - Two things on the list. Mer → "Reminder near the store" → the switch (the permissions are
 *   granted beforehand, as after "Allow" and "Allow all the time"), then the store "Testbutiken"
 *   gets a pasted place (made up, out at sea) through its sheet.
 * - With the process gone, a simulated ENTER of the store (`SmokeWorkerReceiver --es worker store`,
 *   only in this CI build type; the same worker a geofence event starts) posts ONE notification in
 *   the channel "shopping": "You're near Testbutiken", "2 things on the list". A second ENTER without
 *   an EXIT is the same visit and posts nothing.
 *
 * Real geofences (within 200 m, battery) need a phone outdoors: docs/BYGGE.md "Fas 5 – läge".
 */
@RunWith(AndroidJUnit4::class)
class Phase5ButikSmokeTest : Phase2SmokeBase() {
    private val device = DeviceState(app)

    override fun servers(): List<FakeTandoor> = listOf(tandoor)

    override fun extraReport(): List<String> = listOf("--- notifications:") + device.postedNotifications().map { it.toString() }

    @Before
    fun allowLocationAndNotifications() {
        for (permission in PERMISSIONS) app.shell("pm grant ${TandemApp.PACKAGE} $permission")
    }

    @Test
    fun anEnterNearTheStorePostsTheReminderOncePerVisit() {
        tandoor.shopping.seed("mjölk", 1.0, "l")
        tandoor.shopping.seed("bröd")
        logIn()

        app.tap("More")
        app.tapInList(PAGE)
        assertTrue(
            "The emulator image has Google Play services (google_apis); without them the option is hidden",
            !app.device.wait(Until.hasObject(By.text(NO_PLAY_SERVICES)), SHORT_WAIT_MS),
        )
        switchOn(SETTING)
        app.findInList(By.text(STORE)).click()
        assertTrue("The place is typed", app.setNodeText(placeField(), PLACE))
        assertTrue("Saved with the keyboard's Done", app.imeEnter(placeField()))
        app.waitFor(By.text(REMOVE_PLACE))
        app.capture("phase5-store-reminder-sheet")
        app.tap(DONE)
        app.waitFor(By.text(WATCHING_ROW))
        app.capture("phase5-store-reminder")

        app.killInBackground()
        assertEquals("The simulated ENTER reminds", "SUCCEEDED:POSTED", enter(TESTBUTIKEN_ID))
        device.waitUntil("the reminder near the store") { reminders().isNotEmpty() }
        device.saveDumps("phase5-store-reminder")
        val posted = reminders().single()
        assertEquals("In the shopping list's channel: $posted", SHOPPING_CHANNEL, posted.channel)
        assertTrue("It names the store: $posted", posted.title?.contains(STORE) == true)
        assertEquals("It counts what is left: $posted", "2 things on the list", posted.text)

        // The same visit (no EXIT): nothing more.
        assertEquals("A second ENTER is the same visit", "SUCCEEDED:SAME_VISIT", enter(TESTBUTIKEN_ID))
        assertEquals("Exactly one", listOf(posted), reminders())
    }

    /** The sheet's one text field (read again after typing: a node is a snapshot). */
    private fun placeField() = app.waitForNode("the place field") { it.className?.toString() == "android.widget.EditText" }

    /** The minified build's receiver hands the worker an ENTER of [storeId] and tells what it did. */
    private fun enter(storeId: Long): String? {
        val output = app.shell("am broadcast -n ${TandemApp.PACKAGE}/$WORKER_RECEIVER --es worker store --el store $storeId")
        return BROADCAST_DATA.find(output)?.groupValues?.get(1)
    }

    private fun reminders(): List<DeviceState.Posted> =
        device.postedNotifications().filter { it.channel == SHOPPING_CHANNEL && it.title?.contains(STORE) == true }

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
        val PERMISSIONS = listOf(
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.ACCESS_BACKGROUND_LOCATION",
            "android.permission.POST_NOTIFICATIONS",
        )
        const val PAGE = "Reminder near the store"
        const val SETTING = "Remind me near the store"
        const val NO_PLAY_SERVICES = "Needs Google Play services"
        const val STORE = "Testbutiken"

        /** The only store in the recorded 2.6.13 fixtures (fas2/supermarket-list). */
        const val TESTBUTIKEN_ID = 1L

        /** Made up, out at sea in the Atlantic: no real store. */
        const val PLACE = "12.3456, -45.6789"
        const val REMOVE_PLACE = "Remove place"
        const val DONE = "Done"
        const val WATCHING_ROW = "Reminds within 200 m"
        const val SHOPPING_CHANNEL = "shopping"
        const val WORKER_RECEIVER = "casa.moller.tandem.smoke.SmokeWorkerReceiver"
        const val SHORT_WAIT_MS = 2_000L
        val BROADCAST_DATA = Regex("""data="([^"]*)"""")
    }
}
