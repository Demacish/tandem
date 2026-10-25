package casa.moller.tandem.smoke

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.io.File
import java.time.OffsetDateTime
import java.util.regex.Pattern
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith

/**
 * Release smoke test for Phase 1b (0.2.0 "laga", plan §10 Fas 1 "Klart när"; docs/BYGGE.md
 * "Release-röktest"): cook mode, two timers that ring on a locked screen without any network,
 * "Klart!" and the cook log sent once when the network is back, in the R8-minified sideload app.
 *
 * - [offlineCookMode_twoTimersRingWhileLocked_klartIsSentOnceOnline]: login and first sync
 *   against [FakeTandoor], Shakshuka downloaded, airplane mode, "Start cooking" from the recipe,
 *   both steps, the in-context notification question (only then is the permission granted),
 *   two text timers (50 s and 55 s) at once, a rotation that keeps the step and the timers, the
 *   power button; both alarms are posted on the alarm channel with the full-screen intent while
 *   the keyguard is locked, the ringing service runs in the foreground and the lock-screen alarm
 *   shows; "Stop", "Done!" with 4 stars and a comment, then exactly one `POST /api/cook-log/`
 *   with that body once the network is back.
 * - [demoMode_cookTonightWithATimer_klartStaysOnTheDevice]: demo mode's "Cook" on Tonight, a
 *   timer with "Not now", the timer bar on Today opens cook mode again, "Klart!" is saved in the
 *   demo; not a single request to a server.
 */
@RunWith(AndroidJUnit4::class)
class Phase1bSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = TandemApp(UiDevice.getInstance(instrumentation))
    private val device = DeviceState(app)
    private var networkTurnedOff = false

    /**
     * The device is always left usable for the next test, also after a failure: network on,
     * screen on and unlocked, natural orientation with auto-rotation. The outermost rule, so a
     * failure is captured (below) in the state it happened in.
     */
    @get:Rule(order = 0)
    val restore = object : TestWatcher() {
        override fun finished(description: Description) {
            runCatching { app.device.setOrientationNatural() }
            runCatching { app.device.unfreezeRotation() }
            runCatching { device.unlockScreen() }
            if (networkTurnedOff) app.setNetworkEnabled(true)
        }
    }

    @get:Rule(order = 1)
    val tandoor = FakeTandoor(instrumentation.context.assets)

    /** On failure: screenshot, window hierarchy, notifications, services and the server's log. */
    @get:Rule(order = 2)
    val artifacts = object : TestWatcher() {
        override fun failed(e: Throwable, description: Description) {
            val name = "FAILED-${description.methodName}"
            app.capture(name)
            device.saveDumps(name)
            runCatching {
                File(TandemApp.outputDir(), "$name-requests.txt").writeText(
                    (
                        tandoor.requests + listOf("--- while offline:") + tandoor.requestsWhileOffline +
                            listOf("--- cook log bodies:") + tandoor.cookLogPosts + listOf(app.networkReport())
                        ).joinToString("\n"),
                )
            }
        }
    }

    @Before
    fun startFresh() {
        app.clearData()
        // A fresh install as far as notifications go: the app must ask in context (plan 8.7).
        app.shell("pm revoke ${TandemApp.PACKAGE} $POST_NOTIFICATIONS")
        app.shell("pm clear-permission-flags ${TandemApp.PACKAGE} $POST_NOTIFICATIONS user-set user-fixed")
    }

    @Test
    fun offlineCookMode_twoTimersRingWhileLocked_klartIsSentOnceOnline() {
        // The sideload build declares USE_FULL_SCREEN_INTENT; Android 14+ lets the user (or a
        // store) revoke it. Logged as the emulator grants it, then allowed so that the lock-screen
        // alarm path (canUseFullScreenIntent) is what this run checks.
        Log.i(TAG, "USE_FULL_SCREEN_INTENT after install: ${device.appOp(FULL_SCREEN_OP)}")
        app.shell("appops set ${TandemApp.PACKAGE} $FULL_SCREEN_OP allow")

        logIn()
        downloadTimerRecipeAndGoOffline()

        // Cook mode from the recipe (entry point "Börja laga"), stepped through to the last step.
        app.tap("Start cooking")
        app.waitFor(By.text("Step 1 of 2"))
        app.tap("Next")
        app.waitFor(By.text("Step 2 of 2"))
        app.capture("phase1b-cook-step2")

        val firstStarted = startTwoTimersAskingInContext()
        rotateKeepsStepAndTimers()

        // The power button. Both timers must still be running, or this would test nothing.
        device.lockScreen()
        val lockedAfter = SystemClock.elapsedRealtime() - firstStarted
        assertTrue(
            "Locked ${lockedAfter / MILLIS} s after the first start: too slow to test ringing while locked",
            lockedAfter < FIRST_TIMER_SECONDS * MILLIS,
        )
        assertTrue("No alarm before the timers end", device.alarmNotifications().isEmpty())

        bothAlarmsRingWhileLockedThenStop()

        // Back in cook mode at the same step: "Done!" with 4 stars and a comment, still offline.
        val savedAt = saveKlartOffline()
        assertTrue("Nothing is sent offline: ${tandoor.cookLogPosts}", tandoor.cookLogPosts.isEmpty())

        // The network comes back: the outbox sends the entry exactly once, with its date kept.
        tandoor.offline = false
        app.setNetworkEnabled(true)
        networkTurnedOff = false
        assertCookLogSentOnce(savedAt)
    }

    /** Shakshuka (two text timers in its last step) downloaded, then airplane mode. */
    private fun downloadTimerRecipeAndGoOffline() {
        app.tap("Recipes")
        openRecipe(FakeTandoor.TIMER_RECIPE_NAME)
        app.tapDescription("Download for offline use")
        app.waitFor(By.desc("Remove offline copy"))

        // Airplane mode, and the fake server refuses every connection (loopback survives it).
        tandoor.offline = true
        networkTurnedOff = true
        app.setNetworkEnabled(false)
        assertFalse("The emulator must be offline: ${app.networkReport()}", app.hasNetwork())
    }

    /**
     * The first timer asks in context, never at first start (plan 8.7), and the system dialog
     * follows "Continue". Only now is the permission granted, by the instrumentation. The second
     * timer starts at once without a second question. Returns when the first one started.
     */
    private fun startTwoTimersAskingInContext(): Long {
        assertFalse("Not granted before the first timer", device.hasPermission(POST_NOTIFICATIONS))
        app.tapDescriptionMatching(startTimer(FIRST_TIMER_SECONDS))
        confirmTime(FIRST_TIMER_SECONDS)
        app.waitFor(By.text("Hear the timer with the screen locked?"))
        app.capture("phase1b-notification-question")
        app.tap("Continue")
        val systemDialog = app.waitFor(By.res(PERMISSION_ALLOW_BUTTON), SYSTEM_DIALOG_TIMEOUT_MS)
        app.capture("phase1b-system-permission-dialog")
        app.shell("pm grant ${TandemApp.PACKAGE} $POST_NOTIFICATIONS")
        runCatching { systemDialog.click() }
        app.waitUntilGone(By.res(PERMISSION_ALLOW_BUTTON))
        assertTrue("POST_NOTIFICATIONS granted", device.hasPermission(POST_NOTIFICATIONS))
        val firstStarted = SystemClock.elapsedRealtime()
        app.waitFor(runningTimer(FIRST_TIMER_SECONDS))

        app.tapDescriptionMatching(startTimer(SECOND_TIMER_SECONDS))
        confirmTime(SECOND_TIMER_SECONDS)
        app.waitFor(runningTimer(SECOND_TIMER_SECONDS))
        assertFalse(app.device.hasObject(By.text("Hear the timer with the screen locked?")))
        device.waitUntil("two countdown notifications (timer_ongoing)") {
            device.countdownNotifications().count { it.channel == ONGOING_CHANNEL } == 2
        }
        app.capture("phase1b-two-timers")
        return firstStarted
    }

    /**
     * Rotation mid-cook (plan §10): the activity is recreated, the step and both timers stay. In
     * landscape the phone shows two panes and the timers are cards in the side pane, under the
     * servings and the step's ingredients, so the side pane is scrolled to them.
     */
    private fun rotateKeepsStepAndTimers() {
        app.device.setOrientationLandscape()
        app.waitFor(By.text("Step 2 of 2"))
        findInSidePane(runningTimer(FIRST_TIMER_SECONDS))
        findInSidePane(runningTimer(SECOND_TIMER_SECONDS))
        app.capture("phase1b-landscape")
        app.device.setOrientationNatural()
        app.waitFor(By.text("Step 2 of 2"))
        app.waitFor(runningTimer(FIRST_TIMER_SECONDS))
        app.waitFor(runningTimer(SECOND_TIMER_SECONDS))
    }

    /** Both alarms ring on the locked screen, without network; "Stop" for both on the lock-screen alarm. */
    private fun bothAlarmsRingWhileLockedThenStop() {
        device.waitUntil("both alarm notifications", ALARM_TIMEOUT_MS) { device.alarmNotifications().size == 2 }
        device.saveDumps("phase1b-ringing")
        val alarms = device.alarmNotifications()
        assertTrue("Alarms on the alarm channel: $alarms", alarms.all { it.channel == ALARM_CHANNEL })
        assertTrue("Sideload alarms carry the full-screen intent: $alarms", alarms.all { it.fullScreen })
        assertTrue("Still locked while ringing", device.isKeyguardLocked())
        assertTrue("The ringing service runs in the foreground", device.ringingServiceInForeground())
        assertFalse("Still offline", app.hasNetwork())
        // The full-screen intent turned the screen on with the lock-screen alarm.
        app.waitFor(By.text(ALARM_TITLE))
        device.waitUntil("the screen is on for the alarm") { device.isInteractive() }
        app.capture("phase1b-alarm-on-locked-screen")

        repeat(2) {
            app.waitFor(By.text("Stop")).click()
            app.device.waitForIdle()
        }
        app.waitUntilGone(By.text(ALARM_TITLE))
        device.waitUntil("no alarm notification") { device.alarmNotifications().isEmpty() }
        device.waitUntil("the ringing service stopped") { !device.ringingServiceInForeground() }
        device.unlockScreen()
    }

    /** "Done!" with 4 stars and a comment while offline. Returns when "Save" was tapped. */
    private fun saveKlartOffline(): Long {
        app.waitFor(By.text("Step 2 of 2"))
        app.tap("Done!")
        app.waitFor(By.text("Enjoy your meal!"))
        // The "Klart!" page scrolls: the stars, the comment and the offline note are further down.
        // The note is an icon with a label, one TalkBack item: its text is the content description.
        app.scrollTo(By.clazz(EDIT_TEXT))
        app.tapDescription("4 stars")
        app.waitFor(By.text("4 of 5 stars"))
        app.type(0, COMMENT)
        app.scrollTo(By.desc("You are offline. The entry is saved now and sent to Tandoor when you are online again."))
        app.capture("phase1b-klart")
        val savedAt = System.currentTimeMillis()
        device.expectToast("Saved. The entry is sent when you are online again.") { app.tap("Save in the cook log") }
        app.waitFor(By.desc("Remove offline copy"))
        return savedAt
    }

    /** Exactly one `POST /api/cook-log/` with the "Klart!" body and the offline tap's `created_at`. */
    private fun assertCookLogSentOnce(savedAt: Long) {
        device.waitUntil("POST /api/cook-log/ (${tandoor.requests})", COOK_LOG_TIMEOUT_MS) { tandoor.cookLogPosts.isNotEmpty() }
        SystemClock.sleep(SETTLE_MS)
        assertEquals("Exactly one cook log entry: ${tandoor.cookLogPosts}", 1, tandoor.cookLogPosts.size)
        val body = Json.parseToJsonElement(tandoor.cookLogPosts.single()).jsonObject
        assertEquals(FakeTandoor.TIMER_RECIPE_ID, body.getValue("recipe").jsonPrimitive.content.toInt())
        assertEquals(TIMER_RECIPE_SERVINGS, body.getValue("servings").jsonPrimitive.content.toInt())
        assertEquals(RATING, body.getValue("rating").jsonPrimitive.content.toInt())
        assertEquals(COMMENT, body.getValue("comment").jsonPrimitive.content)
        val createdAt = OffsetDateTime.parse(body.getValue("created_at").jsonPrimitive.content).toInstant().toEpochMilli()
        assertTrue(
            "created_at is when \"Save\" was tapped offline, not when it was sent: $body",
            createdAt in (savedAt - CLOCK_SLACK_MS)..(savedAt + CLOCK_SLACK_MS),
        )
        assertTrue("Only the outbox posts: ${tandoor.requests}", tandoor.requests.count { it == "POST /api/cook-log/" } == 1)
    }

    /**
     * Opens a recipe from the list. A tap that lands while the list still settles after the swipe
     * that found the card can be lost (seen once in CI): tapped again until the detail is open.
     */
    private fun openRecipe(name: String) {
        repeat(OPEN_ATTEMPTS) {
            runCatching { app.findInList(By.text(name)).click() }
            if (app.device.wait(Until.hasObject(By.desc("Download for offline use")), OPEN_WAIT_MS) == true) return
        }
        app.waitFor(By.desc("Download for offline use"))
    }

    /** The "Klart!" page, scrolled or not (its heading may be above the screen). */
    private fun onDonePage(): Boolean = DONE_PAGE_TEXTS.any { app.device.hasObject(By.text(it)) }

    /**
     * "Next", or "Done!" on the last step, without scrolling: while the pager settles neither may
     * be found at once, and a scroll gesture then would page or scroll the content instead.
     */
    private fun clickPageButton() {
        val buttons = app.clickableWithText("Done!") + app.clickableWithText("Next")
        device.waitUntil("\"Next\" or \"Done!\"") { onDonePage() || buttons.any { app.device.hasObject(it) } }
        if (onDonePage()) return
        buttons.firstNotNullOfOrNull { app.device.findObject(it) }?.click()
        app.device.waitForIdle()
    }

    /** Finds [selector] in cook mode's side pane (landscape and tablet), scrolling it down if needed. */
    private fun findInSidePane(selector: BySelector): UiObject2 {
        app.device.findObject(selector)?.let { return it }
        val pane = app.waitFor(By.res(SIDE_PANE))
        return pane.scrollUntil(Direction.DOWN, Until.findObject(selector))
            ?: app.device.findObject(selector)
            ?: throw AssertionError("Not in the side pane: $selector")
    }

    @Test
    fun demoMode_cookTonightWithATimer_klartStaysOnTheDevice() {
        app.launch()
        app.waitFor(By.text(WELCOME_TEXT))
        app.tap("Try demo mode")
        app.waitFor(By.text(DEMO_TONIGHT))

        // Cook mode from Today's Tonight card (entry point "Laga"), a timer without notifications.
        app.tapDescription("Cook $DEMO_TONIGHT")
        app.waitFor(By.text(Pattern.compile("Step 1 of \\d+")))
        app.tapDescriptionMatching(Pattern.compile("Start .*"))
        app.waitFor(By.text("Hear the timer with the screen locked?"))
        app.tap("Not now")
        app.waitFor(By.desc(Pattern.compile(".* left")))
        app.waitFor(By.text("Notifications are off"))
        app.capture("phase1b-demo-timer")

        // Closing cook mode: the timer bar on Today shows the timer and opens cook mode again.
        app.tapDescription("Close cook mode")
        app.waitFor(By.text(DEMO_TONIGHT))
        app.waitFor(By.res(TIMER_HUD))
        app.tapDescriptionMatching(Pattern.compile(".* left"))
        app.waitFor(By.text(Pattern.compile("Step \\d+ of \\d+")))

        // Step to "Done!" and save: demo mode keeps the entry on the device.
        repeat(MAX_STEPS) {
            if (!onDonePage()) clickPageButton()
        }
        app.waitFor(By.text("How did it taste?"))
        app.scrollTo(By.desc("Demo mode: the entry stays on this device."))
        device.expectToast("Saved in the demo.") { app.tap("Save in the cook log") }
        app.capture("phase1b-demo-saved")
        assertEquals("Demo mode never talks to a server", emptyList<String>(), tandoor.requests.toList())
    }

    /** Password login and the real first sync, like [Phase1SmokeTest]. */
    private fun logIn() {
        app.launch()
        app.waitFor(By.text(WELCOME_TEXT))
        app.tap("Get started")
        app.waitFor(By.text("Connect to your Tandoor"))
        app.type(0, tandoor.address)
        app.tap("Connect")
        app.waitFor(By.text("Use your Tandoor username and password."))
        app.type(0, "smoke-user")
        app.type(1, "smoke-password")
        app.tap("Log in")
        app.waitFor(By.text("You’re connected"))
        app.tap("Continue")
        app.waitForClickable("Recipes", FIRST_SYNC_TIMEOUT_MS)
    }

    /** "Adjust the time" opens first; its "Start 50 s" starts the timer with the suggested time. */
    private fun confirmTime(seconds: Int) {
        app.waitFor(By.text("Adjust the time"))
        app.tap("Start $seconds s")
    }

    /** A timer start button: "Start a 50 s timer, 50 seconds" (core:ui ui_timer_start_description). */
    private fun startTimer(seconds: Int): Pattern = Pattern.compile("Start (a )?$seconds s( timer)?.*")

    /** A running timer in the timer bar or a timer card: "Eggs · 50 seconds, 43 s left". */
    private fun runningTimer(seconds: Int): BySelector = By.desc(Pattern.compile(".*$seconds seconds.*, .* left"))

    private companion object {
        const val TAG = "Phase1bSmokeTest"
        const val WELCOME_TEXT = "Welcome to Tandem"
        const val EDIT_TEXT = "android.widget.EditText"
        const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
        const val FULL_SCREEN_OP = "USE_FULL_SCREEN_INTENT"

        /** The system's "Allow" button (com.android or com.google.android permissioncontroller). */
        val PERMISSION_ALLOW_BUTTON: Pattern = Pattern.compile(".*permissioncontroller:id/permission_allow_button")

        /** FakeTandoor.TIMER_STEP_TEXT. */
        const val FIRST_TIMER_SECONDS = 50
        const val SECOND_TIMER_SECONDS = 55

        /** fas1/recipe-shakshuka: 2 servings, the saved choice. */
        const val TIMER_RECIPE_SERVINGS = 2
        const val RATING = 4
        const val COMMENT = "More chili next time"

        /** core:model TandemNotificationChannel ids. */
        const val ALARM_CHANNEL = "timer_alarm"
        const val ONGOING_CHANNEL = "timer_ongoing"

        /** core:timer timer_alarm_title on TimerAlarmActivity. */
        const val ALARM_TITLE = "Time's up"

        /** feature:shell TIMER_HUD_TAG (a resource id through testTagsAsResourceId). */
        const val TIMER_HUD = "shell_timer_hud"

        /** feature:cook COOK_SIDE_PANE_TAG. */
        const val SIDE_PANE = "cook:side-pane"

        const val DEMO_TONIGHT = "Baked salmon with dill potatoes"
        const val MAX_STEPS = 12
        const val OPEN_ATTEMPTS = 3
        const val OPEN_WAIT_MS = 7_000L

        /** feature:cook's "Klart!" page, top to bottom. */
        val DONE_PAGE_TEXTS = listOf("Enjoy your meal!", "How did it taste?", "Save in the cook log")

        const val MILLIS = 1_000L
        const val FIRST_SYNC_TIMEOUT_MS = 90_000L
        const val SYSTEM_DIALOG_TIMEOUT_MS = 15_000L

        /** The second timer's end plus a slow emulator's alarm delivery. */
        const val ALARM_TIMEOUT_MS = 90_000L
        const val COOK_LOG_TIMEOUT_MS = 120_000L
        const val SETTLE_MS = 5_000L
        const val CLOCK_SLACK_MS = 30_000L
    }
}
