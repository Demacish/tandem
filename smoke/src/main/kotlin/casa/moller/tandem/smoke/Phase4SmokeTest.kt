package casa.moller.tandem.smoke

import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Until
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.regex.Pattern
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
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
 * Release smoke test for Phase 4 (0.6.0 "Smart hushåll", plan §10 Fas 4 "Klart när"; docs/BYGGE.md
 * "Release-röktest"), and the page reader of Phase 3 (§8.4 #38), in the R8-minified sideload app:
 *
 * - [twoRecipesAtOnce_bothRingWithTheirRecipe_klartSavesOnlyTheOneLeft]: two recipes as tabs in one
 *   cook mode, a timer in each, both named on the timer cards, both ringing on a locked screen with
 *   their recipe in the notification, the session back after process death, one cook log entry.
 * - [expiryNotification_oneOnMealReminders_noneWithTheSettingOff]: the morning check's worker with
 *   a pantry entry that expires tomorrow, with the setting off and on.
 * - [fastPush_encryptedMessagesThroughTheConnector_wakeUpBecomesTheListNotification]: a real
 *   UnifiedPush exchange with [FakeDistributor] and [FakePushService]; the app's process is dead
 *   when the wake-up arrives.
 * - [weekSuggestions_chosenKeyword_sevenDifferentDinnersReachTheServer]: "Suggest the week's meals".
 * - [sharedLinkTheServerCannotOpen_pageIsReadInTheApp_andBecomesADraft]: the WebView reader.
 * - [aiImportEndsWithTheAppAway_theNotificationSaysSo_andTheResultWaits]: the keep-alive worker and
 *   the notification "import_done", with the app off the screen; the answer outlives the process.
 * - [demoMode_twoRecipesAndWeekSuggestions_withoutFastPush_neverTalkToAServer]: the same features in
 *   demo mode, without one request.
 */
@RunWith(AndroidJUnit4::class)
class Phase4SmokeTest : Phase2SmokeBase() {
    private val device = DeviceState(app)
    private var originalZone: String? = null

    /** The device is left unlocked and in its own time zone, also after a failure. The outermost rule. */
    @get:Rule(order = -1)
    val restore = object : TestWatcher() {
        override fun finished(description: Description) {
            runCatching { device.unlockScreen() }
            originalZone?.let { zone -> setTimeZone(zone) }
        }
    }

    override fun servers(): List<FakeTandoor> = listOf(tandoor)

    override fun extraReport(): List<String> = listOf("--- import posts:") + tandoor.importPosts.map { it.take(REPORT_CHARS) } +
        listOf("--- cook log:") + tandoor.cookLogPosts +
        listOf("--- phones:") + tandoor.pushService.phones.map { it.endpoint } +
        listOf("--- notifications:") + device.postedNotifications().map { it.toString() } +
        PhaseSPantrySmokeTest.pantryReport(tandoor)

    /** After [startFresh]: notifications are allowed (Phase1bSmokeTest covers asking in context). */
    @Before
    fun allowNotifications() {
        app.shell("pm grant ${TandemApp.PACKAGE} $POST_NOTIFICATIONS")
        app.shell("appops set ${TandemApp.PACKAGE} USE_FULL_SCREEN_INTENT allow")
        FakeDistributor.reset()
        tandoor.pushService.deliver = { endpoint, message -> FakeDistributor.deliver(instrumentation.context, endpoint, message) }
    }

    // ------------------------------------------------------------------------------ (a) two recipes

    @Test
    fun twoRecipesAtOnce_bothRingWithTheirRecipe_klartSavesOnlyTheOneLeft() {
        tandoor.timerTexts[PASTA_ID] = PASTA_TIMER_TEXT
        logIn()
        openFirstRecipeAtItsTimers()
        val firstStarted = startTimer(FIRST_TIMER_SECONDS)
        app.waitFor(By.desc(Pattern.compile(".*$FIRST_TIMER_SECONDS seconds.*, .* left")))

        addSecondRecipe()
        app.tap("Next")
        app.waitFor(By.desc(tabDescription(PASTA, 2, PASTA_STEPS)))
        app.tap("Next")
        app.waitFor(By.desc(tabDescription(PASTA, PASTA_STEPS, PASTA_STEPS)))
        startTimer(SECOND_TIMER_SECONDS)

        // Both timers on the screen, each with its recipe, also from the other tab.
        app.waitFor(timerOf(FakeTandoor.TIMER_RECIPE_NAME))
        app.waitFor(timerOf(PASTA))
        app.capture("phase4-two-recipes-timers")
        app.waitFor(tab(FakeTandoor.TIMER_RECIPE_NAME)).click()
        app.waitFor(By.text(SHAKSHUKA_LAST_STEP))
        app.waitFor(timerOf(FakeTandoor.TIMER_RECIPE_NAME))
        app.waitFor(timerOf(PASTA))

        device.lockScreen()
        val lockedAfter = SystemClock.elapsedRealtime() - firstStarted
        assertTrue(
            "Locked ${lockedAfter / MILLIS} s after the first start: too slow to test ringing while locked",
            lockedAfter < SECOND_TIMER_SECONDS * MILLIS,
        )
        bothAlarmsRingNamingTheirRecipe()

        // The session comes back after process death: both tabs, each at its own step.
        app.killInBackground()
        app.launch()
        app.waitFor(By.desc(tabDescription(FakeTandoor.TIMER_RECIPE_NAME, SHAKSHUKA_STEPS, SHAKSHUKA_STEPS)))
        app.waitFor(By.desc(tabDescription(PASTA, PASTA_STEPS, PASTA_STEPS)))
        app.capture("phase4-two-recipes-restored")

        // ✕ closes the shown recipe only; the other's "Klart!" sends one entry, for that recipe.
        app.tapDescription("Stop cooking ${FakeTandoor.TIMER_RECIPE_NAME}")
        app.waitUntilGone(tab(FakeTandoor.TIMER_RECIPE_NAME))
        app.waitFor(By.text("Step $PASTA_STEPS of $PASTA_STEPS"))
        app.tap("Done!")
        app.waitFor(By.text("Enjoy your meal!"))
        app.tap("Save in the cook log")
        waitForServer("the cook log entry") { if (tandoor.cookLogPosts.isNotEmpty()) null else "none yet" }
        SystemClock.sleep(SETTLE_MS)
        val entry = Json.parseToJsonElement(tandoor.cookLogPosts.single()).jsonObject
        assertEquals("The entry is the recipe that was left: $entry", PASTA_ID.toString(), entry.getValue("recipe").jsonPrimitive.content)
    }

    /** Shakshuka from the recipe list, on its last step, where its text timers are. */
    private fun openFirstRecipeAtItsTimers() {
        app.tap("Recipes")
        val detail = By.desc("Download for offline use")
        repeat(TAP_ATTEMPTS) {
            runCatching { app.findInList(By.text(FakeTandoor.TIMER_RECIPE_NAME)).click() }
            if (app.device.wait(Until.hasObject(detail), TAP_WAIT_MS) == true) return@repeat
        }
        app.waitFor(detail)
        app.tap("Start cooking")
        app.waitFor(By.text("Step 1 of $SHAKSHUKA_STEPS"))
        app.tap("Next")
        app.waitFor(By.text("Step $SHAKSHUKA_STEPS of $SHAKSHUKA_STEPS"))
    }

    /** "Cook another recipe" (+), the pasta in the sheet: it opens as the second, selected tab. */
    private fun addSecondRecipe() {
        pickSecondRecipe(PASTA)
        app.waitFor(By.desc(tabDescription(PASTA, 1, PASTA_STEPS)))
        app.waitFor(By.desc(tabDescription(FakeTandoor.TIMER_RECIPE_NAME, SHAKSHUKA_STEPS, SHAKSHUKA_STEPS)))
    }

    /** The sheet is its own window, and a long list holds [name] below the fold: searched, then through accessibility actions. */
    private fun pickSecondRecipe(name: String) {
        app.tapDescription("Cook another recipe")
        val search = app.waitForNode("the picker's search", predicate = ::isAddField)
        assertTrue("The search takes text", app.setNodeText(search, name))
        // The search field says the same as the row: the row is the node that is no text field.
        val isRow = { node: AccessibilityNodeInfo -> node.text?.toString() == name && !isAddField(node) && app.clickableOf(node) != null }
        app.waitForNode("the picker's \"$name\"", predicate = isRow)
        app.capture("phase4-recipe-picker")
        for (attempt in 1..TAP_ATTEMPTS) {
            app.findNode(isRow)?.let(app::clickableOf)?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (app.device.wait(Until.hasObject(tab(name)), TAP_WAIT_MS) == true) break
        }
    }

    /** Starts the step's [seconds] text timer ("Adjust the time", "Start n s"). Returns when it started. */
    private fun startTimer(seconds: Int): Long {
        app.tapDescriptionMatching(Pattern.compile("Start (a )?$seconds s( timer)?.*"))
        app.waitFor(By.text("Adjust the time"))
        app.tap("Start $seconds s")
        return SystemClock.elapsedRealtime()
    }

    /** Both alarm notifications while locked, each saying its recipe; "Stop" for both on the lock-screen alarm. */
    private fun bothAlarmsRingNamingTheirRecipe() {
        device.waitUntil("both alarm notifications", ALARM_TIMEOUT_MS) { device.alarmNotifications().size == 2 }
        device.saveDumps("phase4-ringing")
        val alarms = device.alarmNotifications()
        assertTrue("Alarms on the alarm channel: $alarms", alarms.all { it.channel == ALARM_CHANNEL })
        assertEquals("Each alarm says its recipe: $alarms", setOf(FakeTandoor.TIMER_RECIPE_NAME, PASTA), alarms.map { it.subText }.toSet())
        app.waitFor(By.text(ALARM_TITLE))
        app.capture("phase4-alarm-two-recipes")
        repeat(2) {
            app.waitFor(By.text("Stop")).click()
            app.device.waitForIdle()
        }
        app.waitUntilGone(By.text(ALARM_TITLE))
        device.waitUntil("no alarm notification") { device.alarmNotifications().isEmpty() }
        device.unlockScreen()
    }

    private fun tab(name: String): BySelector = By.desc(Pattern.compile("${Pattern.quote(name)}, (Step \\d+ of \\d+|Done!).*"))

    private fun tabDescription(name: String, step: Int, steps: Int): Pattern =
        Pattern.compile("${Pattern.quote(name)}, Step $step of $steps.*")

    private fun timerOf(recipe: String): BySelector = By.desc(Pattern.compile("${Pattern.quote(recipe)}: .* left"))

    // ------------------------------------------------------------------------------ (b) expiry notification

    @Test
    fun expiryNotification_oneOnMealReminders_noneWithTheSettingOff() {
        val tomorrow = localNoonZoneDate().plusDays(1)
        val fridge = tandoor.pantry.seedLocation("Fridge")
        tandoor.pantry.seedEntry(fridge, EXPIRING_FOOD, amount = 1.0, unit = "kg", expires = tomorrow.toString())
        logIn()

        // Off (the default): the worker runs and nothing is posted. The process is gone as on a
        // real morning; Tandem still "on screen" (ProcessLifecycleOwner waits 700 ms) would post nothing either.
        app.killInBackground()
        assertEquals("The worker ran", WORK_SUCCEEDED, runMorningCheck())
        assertTrue("Nothing with the setting off: ${device.postedNotifications()}", expiryNotifications().isEmpty())

        // On: one notification on the meal reminder channel.
        app.launch()
        app.tap("More")
        app.tapInList("Sync and storage")
        switchOn("Notify when something expires")
        app.capture("phase4-expiry-setting")
        app.killInBackground()
        assertEquals("The worker ran", WORK_SUCCEEDED, runMorningCheck())
        device.waitUntil("the expiry notification") { expiryNotifications().isNotEmpty() }
        device.saveDumps("phase4-expiry")
        val posted = expiryNotifications().single()
        assertEquals("On the planned channel: $posted", MEAL_REMINDERS_CHANNEL, posted.channel)
        assertTrue("It says what expires: $posted", posted.title?.contains(EXPIRING_FOOD, ignoreCase = true) == true)

        // Never twice the same day: a second run changes nothing.
        assertEquals("The worker ran", WORK_SUCCEEDED, runMorningCheck())
        assertEquals("Exactly one", listOf(posted), expiryNotifications())
    }

    /**
     * The check keeps quiet between 21 and 7 by the device's clock, and CI runs at any hour: the
     * emulator goes to the zone where it is about noon now (restored by [restore]). Returns today
     * in that zone.
     */
    private fun localNoonZoneDate(): LocalDate {
        originalZone = app.shell("getprop persist.sys.timezone").trim().ifEmpty { "GMT" }
        val offset = NOON - Instant.now().atOffset(ZoneOffset.UTC).hour
        // POSIX signs: Etc/GMT-3 is three hours AHEAD of UTC.
        val zone = if (offset == 0) "Etc/GMT" else "Etc/GMT${if (offset > 0) "-" else "+"}${kotlin.math.abs(offset)}"
        setTimeZone(zone)
        device.waitUntil("the device's zone is $zone") { app.shell("getprop persist.sys.timezone").trim() == zone }
        Log.i(TAG, "Zone $zone: the device says ${app.shell("date").trim()}")
        return LocalDate.now(ZoneId.of(zone))
    }

    private fun setTimeZone(zone: String) {
        app.shell("cmd alarm set-timezone $zone")
    }

    /** The minified build's receiver runs the real worker once and tells how it ended ("SUCCEEDED"). */
    private fun runMorningCheck(): String? {
        val output = app.shell("am broadcast -n ${TandemApp.PACKAGE}/$WORKER_RECEIVER")
        return BROADCAST_DATA.find(output)?.groupValues?.get(1)
    }

    private fun expiryNotifications(): List<DeviceState.Posted> =
        device.postedNotifications().filter { it.id == PANTRY_EXPIRY_ID || it.channel == MEAL_REMINDERS_CHANNEL }

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

    // ------------------------------------------------------------------------------ (c) fast push

    @Test
    fun fastPush_encryptedMessagesThroughTheConnector_wakeUpBecomesTheListNotification() {
        logIn()
        turnOnFastPush()

        // Only a message the app could decrypt says its own id: this proves the connector's Web
        // Push keys and Tink under R8. The test notification lands on the list channel (plan 8.7).
        app.tap("Send test notification")
        device.waitUntil("the test notification", PUSH_TIMEOUT_MS) { pushNotification(FAST_PUSH_TEST_ID) != null }
        assertEquals(SHOPPING_CHANNEL, pushNotification(FAST_PUSH_TEST_ID)?.channel)
        assertTrue("The connector acknowledged the message", FakeDistributor.acknowledged.isNotEmpty())

        // The partner adds an item with Tandem's process gone: the wake-up starts it, the shopping
        // list's worker reads the list and the ordinary list notification shows within a minute.
        app.killInBackground()
        val partnerAdded = SystemClock.elapsedRealtime()
        tandoor.shopping.partnerAdd(PARTNER_FOOD)
        val reads = listReads()
        assertTrue("The service reaches the phone", tandoor.pushService.wakeAll())
        device.waitUntil("the list notification", PUSH_TIMEOUT_MS) { pushNotification(SHOPPING_CHANGES_ID) != null }
        device.saveDumps("phase4-push")
        val notification = pushNotification(SHOPPING_CHANGES_ID)
        assertEquals("On the planned channel: $notification", SHOPPING_CHANNEL, notification?.channel)
        assertTrue("It names the item: $notification", notification?.text?.contains(PARTNER_FOOD) == true)
        assertTrue("The wake-up read the list", listReads() > reads)
        assertTrue("Within a minute", SystemClock.elapsedRealtime() - partnerAdded < PUSH_TIMEOUT_MS)

        // Turning it off drops the phone at the service and at the distributor.
        app.launch()
        app.tap("Turn off")
        waitForServer("the phone unregistered") { if (tandoor.pushService.phones.isEmpty()) null else "still registered" }
        waitForServer("the distributor told") { if (FakeDistributor.unregistered.isNotEmpty()) null else "not told" }
    }

    /** More → Sync and storage → Fast push: the address, the only distributor, "Turn on", registered. */
    private fun turnOnFastPush() {
        app.tap("More")
        app.tapInList("Sync and storage")
        app.findInList(By.text(FAST_PUSH_ROW))
        app.tapUntil(FAST_PUSH_ROW, By.text("Service address"))
        app.scrollTo(By.clazz(EDIT_TEXT))
        app.type(0, tandoor.baseUrl)
        app.capture("phase4-fast-push")
        app.tap("Turn on")
        waitForServer("the phone's registration") { if (tandoor.pushService.phones.isNotEmpty()) null else "none yet" }
        val phone = tandoor.pushService.phones.single()
        assertTrue("The app sent its Web Push keys: $phone", phone.p256dh.isNotEmpty() && phone.auth.isNotEmpty())
        assertEquals("The service checks the account's own token", FakeTandoor.DEVICE_TOKEN, phone.token)
        app.findInList(By.text("Send test notification"))
    }

    private fun pushNotification(id: Int): DeviceState.Posted? = device.postedNotifications().firstOrNull { it.id == id }

    private fun listReads(): Int = tandoor.requests.count { it == "GET $ENTRIES" }

    // ------------------------------------------------------------------------------ (d) week suggestions

    @Test
    fun weekSuggestions_chosenKeyword_sevenDifferentDinnersReachTheServer() {
        WEEKDAY_RECIPES.forEach { tandoor.partnerAddsKeyword(it, KEYWORD) }
        logIn()
        app.tap("Week")
        app.waitFor(By.res(WEEK_AGENDA))
        app.tapDescription("Next week")
        openSuggestions()

        // The sheet is its own window: its chips and buttons through accessibility actions.
        app.waitForNode("the keyword \"$KEYWORD\"") { it.text?.toString() == KEYWORD }
        for (attempt in 1..TAP_ATTEMPTS) {
            app.clickNodeWithText(KEYWORD)
            if (nodeShows(TAP_WAIT_MS, ::isChosenKeyword)) break
        }
        assertTrue("\"$KEYWORD\" is chosen", app.findNode(::isChosenKeyword) != null)
        // The rows follow the chip a moment later: the button must not take the old suggestions.
        // A row is "<weekday>: <recipe>"; the accessibility cache can hold a replaced row for long.
        device.waitUntil("only the keyword's recipes in the sheet") {
            instrumentation.uiAutomation.clearCache()
            app.findNode { node -> node.contentDescription?.let { WITHOUT_KEYWORD.containsMatchIn(it) } == true } == null
        }
        app.waitForNode("\"$ACCEPT_SEVEN\"") { it.text?.toString() == ACCEPT_SEVEN }
        app.capture("phase4-week-suggestions")
        for (attempt in 1..TAP_ATTEMPTS) {
            app.clickNodeWithText(ACCEPT_SEVEN)
            if (nodeGone(TAP_WAIT_MS) { it.text?.toString() == ACCEPT_SEVEN }) break
        }

        waitForServer("seven meals") { if (tandoor.mealPlan.all().size >= SEVEN) null else "${tandoor.mealPlan.all().size} so far" }
        waitUntilNothingWaits()
        val meals = tandoor.mealPlan.all()
        assertEquals("Seven dinners: $meals", SEVEN, meals.size)
        assertTrue("All are dinners: $meals", meals.all { it.mealType == FakeMealPlan.DINNER })
        assertEquals("Seven different recipes: $meals", SEVEN, meals.mapNotNull { it.recipe }.toSet().size)
        assertEquals("Exactly the keyword's recipes: $meals", WEEKDAY_RECIPES.toSet(), meals.mapNotNull { it.recipe }.toSet())
        val days = meals.map { it.fromDay }.sorted()
        assertEquals("Seven days in a row: $days", days.first().plusDays(SEVEN - 1L), days.last())
        assertEquals("One a day: $days", SEVEN, days.toSet().size)
        assertTrue("No 404: ${tandoor.notFound}", tandoor.notFound.isEmpty())
    }

    /** The card below the days (scrolled to, clicked at rest), again until the sheet shows. */
    private fun openSuggestions() {
        val card = By.res(WEEK_SUGGEST_CARD)
        for (attempt in 1..TAP_ATTEMPTS) {
            app.findInList(card)
            clickAtRest(listOf(card))
            if (nodeShows(TAP_WAIT_MS) { it.text?.toString() == KEYWORDS_HEADING }) return
        }
        app.waitForNode("the suggestion sheet") { it.text?.toString() == KEYWORDS_HEADING }
    }

    private fun isChosenKeyword(node: AccessibilityNodeInfo): Boolean {
        if (node.text?.toString() != KEYWORD) return false
        val chip = app.clickableOf(node) ?: return false
        return chip.isSelected || chip.isChecked
    }

    // ------------------------------------------------------------------------------ (e) the page reader

    @Test
    fun sharedLinkTheServerCannotOpen_pageIsReadInTheApp_andBecomesADraft() {
        logIn()
        // Shared as http://: the app allows no cleartext, so only PageReading.loadUrl's https:// can
        // reach the page. This is the test of that wiring (the route once passed the link as it
        // was, with everything else green); the server is still told the link as it was shared.
        val link = tandoor.blockedPageUrl.replaceFirst("https://", "http://")
        app.shell(
            "am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT $link " +
                "-n ${TandemApp.PACKAGE}/$MAIN_ACTIVITY",
        )
        app.waitFor(By.text("Import recipe"))
        app.tap("Fetch the recipe")

        // The server refuses the link, and the app reads the page itself in its WebView...
        app.waitFor(By.text("Reading the page…"))
        app.capture("phase4-page-reader")
        app.waitFor(By.text("Found this"), PAGE_TIMEOUT_MS)
        app.waitFor(By.text(FakeTandoor.BLOCKED_PAGE_RECIPE))
        app.capture("phase4-page-read")
        assertTrue("The WebView loaded the page: ${tandoor.requests}", "GET /blockerad-sida" in tandoor.requests)

        // ...and the import endpoint got the link alone first, then the page with its JSON-LD.
        val posts = tandoor.importPosts.map { Json.parseToJsonElement(it).jsonObject }
        assertEquals("Link, then page: ${posts.map { it.keys }}", 2, posts.size)
        assertEquals(link, posts[0].getValue("url").jsonPrimitive.content)
        assertFalse("The first try has no page", "data" in posts[0])
        assertEquals(link, posts[1].getValue("url").jsonPrimitive.content)
        assertTrue("The page's HTML went along", "application/ld+json" in (posts[1]["data"] as JsonPrimitive).content)

        // A draft in the editor, not a saved recipe (plan §8.4).
        app.tapUntil("Open in the editor", By.clazz(EDIT_TEXT))
        app.waitFor(By.text(FakeTandoor.BLOCKED_PAGE_RECIPE))
        app.capture("phase4-page-draft")
        assertFalse("Nothing is saved yet: ${tandoor.requests}", "POST /api/recipe/" in tandoor.requests)
    }

    // ------------------------------------------------------------------------------ AI import in the background

    /**
     * Plan §8.7 "Import klar": the AI takes its time and the user leaves. The app is off the screen
     * long enough for Android to freeze a cached process, so this also shows that the keep-alive
     * worker runs in the minified app: the notification comes while the app is away, not on return.
     */
    @Test
    fun aiImportEndsWithTheAppAway_theNotificationSaysSo_andTheResultWaits() {
        tandoor.aiImportMillis = AI_IMPORT_MS
        logIn()
        app.shell(
            "am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT ${tandoor.textPageUrl} " +
                "-n ${TandemApp.PACKAGE}/$MAIN_ACTIVITY",
        )
        app.waitFor(By.text("Import recipe"))
        app.tap("Fetch the recipe")
        app.waitFor(By.text("No recipe on the page yet"), PAGE_TIMEOUT_MS)
        app.tap("Interpret the text with AI")
        device.waitUntil("the AI import to start") { "POST /api/ai-import/" in tandoor.requests }

        app.device.pressHome()
        device.waitUntil("the import notification", AI_IMPORT_MS + PUSH_TIMEOUT_MS) { importNotification() != null }
        device.saveDumps("phase4-import-done")
        val posted = requireNotNull(importNotification())
        assertEquals("On the planned channel: $posted", IMPORT_DONE_CHANNEL, posted.channel)
        assertEquals("It says it is done: $posted", "Recipe ready to review", posted.title)
        assertEquals("It names the recipe: $posted", FakeTandoor.BLOCKED_PAGE_RECIPE, posted.text)

        // The system takes the process after the notification (komplettering 3): the promise still holds.
        app.killInBackground()
        assertTrue("It outlives the process", importNotification() != null)
        app.device.openNotification()
        app.waitFor(By.text("Recipe ready to review")).click()

        // Back in the app the result is on the import screen, and the notification has said its piece.
        app.waitFor(By.text("Found this"))
        app.waitFor(By.text(FakeTandoor.BLOCKED_PAGE_RECIPE))
        app.capture("phase4-import-done")
        device.waitUntil("the notification to go") { importNotification() == null }
        assertEquals("The AI is not asked twice", 1, tandoor.requests.count { it == "POST /api/ai-import/" })
    }

    private fun importNotification(): DeviceState.Posted? = device.postedNotifications().firstOrNull { it.id == IMPORT_DONE_ID }

    // ------------------------------------------------------------------------------ (f) demo mode

    @Test
    fun demoMode_twoRecipesAndWeekSuggestions_withoutFastPush_neverTalkToAServer() {
        app.launch()
        app.waitFor(By.text(WELCOME_TEXT))
        app.tap("Try demo mode")
        app.waitFor(By.text(DEMO_TONIGHT))

        // Two recipes from the demo's own, no timer needed: the tabs are what is new here.
        app.tapDescription("Cook $DEMO_TONIGHT")
        app.waitFor(By.text(Pattern.compile("Step 1 of \\d+")))
        pickSecondRecipe(DEMO_SECOND)
        app.waitFor(tab(DEMO_TONIGHT))
        app.waitFor(tab(DEMO_SECOND))
        app.capture("phase4-demo-two-recipes")
        app.device.pressBack()
        app.waitFor(By.text(DEMO_TONIGHT))

        // Suggestions are saved in the demo like any meal.
        app.tap("Week")
        app.waitFor(By.res(WEEK_AGENDA))
        app.tapDescription("Next week")
        val card = By.res(WEEK_SUGGEST_CARD)
        for (attempt in 1..TAP_ATTEMPTS) {
            app.findInList(card)
            clickAtRest(listOf(card))
            if (nodeShows(TAP_WAIT_MS, ::isAcceptButton)) break
        }
        app.waitForNode("\"Put n meals in the week\"", predicate = ::isAcceptButton)
        app.capture("phase4-demo-suggestions")
        for (attempt in 1..TAP_ATTEMPTS) {
            app.clickNodeWithText(ACCEPT_ANY)
            if (nodeGone(TAP_WAIT_MS, ::isAcceptButton)) break
        }

        // Fast push says why it is not there.
        app.tap("More")
        app.tapInList("Sync and storage")
        app.findInList(By.text(FAST_PUSH_ROW))
        app.tapUntil(FAST_PUSH_ROW, By.textStartsWith("Demo mode has no server"))
        app.capture("phase4-demo-fast-push")
        assertEquals("Demo mode never talks to a server", emptyList<String>(), tandoor.requests.toList())
        assertTrue("Nothing reached the push service", tandoor.pushService.phones.isEmpty())
    }

    private fun isAcceptButton(node: AccessibilityNodeInfo): Boolean =
        node.text?.toString()?.let { ACCEPT_ANY.matcher(it).matches() } == true

    private companion object {
        const val TAG = "Phase4SmokeTest"
        const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
        const val REPORT_CHARS = 400
        const val MILLIS = 1_000L

        /** (a) fas1 recordings; "Sås" is the pasta's last step. */
        const val SHAKSHUKA_STEPS = 2
        const val SHAKSHUKA_LAST_STEP = "Step 2 of 2"
        const val PASTA_STEPS = 3
        const val PASTA_TIMER_TEXT = "Let it simmer for 50 seconds."

        /** Shakshuka's second timer (FakeTandoor.TIMER_STEP_TEXT) and the pasta's. */
        const val FIRST_TIMER_SECONDS = 55
        const val SECOND_TIMER_SECONDS = 50
        const val ALARM_TIMEOUT_MS = 90_000L
        const val ALARM_CHANNEL = "timer_alarm"
        const val ALARM_TITLE = "Time's up"

        /** (b) core:model TandemNotificationIds.PANTRY_EXPIRY and the channel of plan 8.7. */
        const val PANTRY_EXPIRY_ID = 1301
        const val MEAL_REMINDERS_CHANNEL = "meal_reminders"
        const val EXPIRING_FOOD = "potatis"
        const val NOON = 12
        const val WORKER_RECEIVER = "casa.moller.tandem.smoke.SmokeWorkerReceiver"
        const val WORK_SUCCEEDED = "SUCCEEDED"
        val BROADCAST_DATA = Regex("""data="([^"]*)"""")

        /** (c) core:model TandemNotificationIds.SHOPPING_CHANGES, core:sync AndroidFastPushNotifier. */
        const val SHOPPING_CHANGES_ID = 1201
        const val FAST_PUSH_TEST_ID = 1251
        const val SHOPPING_CHANNEL = "shopping"
        const val FAST_PUSH_ROW = "Fast push (own service, experimental)"
        const val PARTNER_FOOD = "kardemumma"
        const val ENTRIES = "/api/shopping-list-entry/"

        /** Plan §10 Fas 4: "inom 1 minut". */
        const val PUSH_TIMEOUT_MS = 60_000L

        /** (d) seven of the recorded recipes get the keyword, the others not. */
        val WEEKDAY_RECIPES = listOf(1L, 2L, 3L, 5L, 6L, 8L, 11L)
        val WITHOUT_KEYWORD = Regex(": (Kanelbullar|Ärtsoppa|Smörgåstårta|Äppelkaka|Tillfälligt recept)")
        const val SEVEN = 7
        const val KEYWORD = "Vardag"
        const val KEYWORDS_HEADING = "Keywords (a suggestion has every one you choose)"
        const val ACCEPT_SEVEN = "Put 7 meals in the week"

        /** feature:mealplan WEEK_AGENDA_TAG and WEEK_SUGGEST_CARD_TAG (resource ids). */
        const val WEEK_AGENDA = "week:agenda"
        const val WEEK_SUGGEST_CARD = "mealplan:suggest-card"

        /** (f) the demo's recipes (core:data assets/demo/en). */
        const val DEMO_TONIGHT = "Baked salmon with dill potatoes"
        const val DEMO_SECOND = "Friday tacos"
        val ACCEPT_ANY: Pattern = Pattern.compile("Put \\d+ meals? in the week")

        /** (e) */
        const val MAIN_ACTIVITY = "casa.moller.tandem.MainActivity"
        const val PAGE_TIMEOUT_MS = 60_000L
        const val AI_IMPORT_MS = 25_000L
        const val IMPORT_DONE_ID = 1401
        const val IMPORT_DONE_CHANNEL = "import_done"
    }
}
