package casa.moller.tandem.smoke

import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Until
import java.util.regex.Pattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Release smoke test of the integration of Fas 5's first release (0.7.0 "I köket", docs/BYGGE.md
 * "Fas 5 – läge"): the three lanes together in the R8-minified sideload app.
 *
 * - [handsfreeFromSettings_kitchenDisplayShowsTheTimersOfTwoRecipes]: in demo mode, handsfree is
 *   switched on under Mer → Kokläge och timers (the setting cook mode's sheet uses); cook mode
 *   opened with "Cook" on the kitchen display turns its steps with the real volume keys; a timer in
 *   each of two recipes in the same cook mode, and back on the kitchen display both timers show
 *   with their recipe. No request.
 * - [storeModeAndGrid_mergedRowsCheckEveryEntryInOneBulk]: two "mjölk" entries are ONE row in store
 *   mode opened while the list shows the grid; checking it there reaches the server as ONE bulk
 *   check of both entries, and the list comes back as the grid.
 */
@RunWith(AndroidJUnit4::class)
class Phase5IntegrationSmokeTest : Phase2SmokeBase() {
    override fun servers(): List<FakeTandoor> = listOf(tandoor)

    /** After [startFresh]: notifications are allowed, so a timer asks nothing (Phase1bSmokeTest covers asking). */
    @Before
    fun allowNotifications() {
        app.shell("pm grant ${TandemApp.PACKAGE} $POST_NOTIFICATIONS")
    }

    @Test
    fun handsfreeFromSettings_kitchenDisplayShowsTheTimersOfTwoRecipes() {
        app.launch()
        app.waitFor(By.text(WELCOME_TEXT))
        app.tap("Try demo mode")
        app.waitFor(By.text(DEMO_TONIGHT))

        // Handsfree from the settings page, not from cook mode's sheet.
        app.tap("More")
        app.tapInList(COOKING_SETTINGS)
        switchOn(HANDSFREE_SETTING)
        app.capture("phase5-integration-handsfree-setting")
        app.device.pressBack()
        app.tap("Today")
        app.waitFor(By.text(DEMO_TONIGHT))

        // Kitchen display → "Cook": handsfree is on, the volume keys turn the steps.
        app.tapDescription(KITCHEN)
        app.waitFor(By.desc(LEAVE_KITCHEN))
        app.tapDescription("Cook $DEMO_TONIGHT")
        app.waitFor(By.text(Pattern.compile("Step 1 of \\d+")))
        app.waitFor(By.text(VOLUME_KEYS_ON))
        app.device.pressKeyCode(KeyEvent.KEYCODE_VOLUME_DOWN)
        app.waitFor(By.text(Pattern.compile("Step 2 of \\d+")))
        app.device.pressKeyCode(KeyEvent.KEYCODE_VOLUME_UP)
        app.waitFor(By.text(Pattern.compile("Step 1 of \\d+")))

        // A timer in each of two recipes in the same cook mode.
        startTimer(TONIGHT_TIMER)
        // With one recipe the timer card names only the step; the recipe shows once there are two.
        app.waitFor(By.desc(Pattern.compile(".* left")))
        pickSecondRecipe(DEMO_SECOND)
        app.waitFor(tab(DEMO_SECOND))
        startTimer(SECOND_TIMER)
        app.waitFor(timerOf(DEMO_TONIGHT))
        app.waitFor(timerOf(DEMO_SECOND))
        app.capture("phase5-integration-two-timers")

        // Back on the kitchen display: both timers, each with its recipe.
        app.device.pressBack()
        app.waitFor(By.desc(LEAVE_KITCHEN))
        app.scrollTo(timerOf(DEMO_TONIGHT))
        app.scrollTo(timerOf(DEMO_SECOND))
        app.capture("phase5-integration-kitchen-timers")
        app.tapDescription(LEAVE_KITCHEN)
        app.waitUntilGone(By.desc(LEAVE_KITCHEN))
        assertEquals("Demo mode never talks to a server", emptyList<String>(), tandoor.requests.toList())
    }

    @Test
    fun storeModeAndGrid_mergedRowsCheckEveryEntryInOneBulk() {
        val list = tandoor.shopping
        val milk = listOf(list.seed("mjölk", 2.0, "l"), list.seed("mjölk", 1.0, "l"))
        val cream = listOf(list.seed("grädde", 3.0, "dl"), list.seed("grädde", 2.0, "dl"))
        list.seed("bröd")

        logIn()
        app.tap("Shopping")
        app.waitFor(By.desc(MERGED_MILK), FIRST_SYNC_TIMEOUT_MS)
        openMenuItem(SHOW_AS_GRID)
        app.waitFor(By.res("shopping:tile:${cream.first()}"))

        // Store mode from the grid: rows, merged as on the list.
        openMenuItem(STORE_MODE)
        app.waitFor(By.text(Pattern.compile("\\d+ of \\d+ in the cart")))
        app.waitFor(By.desc(MERGED_MILK))
        app.capture("phase5-integration-store-mode-merged")
        check("mjölk")
        waitForServer("both milk entries checked") { unchecked(milk) }
        waitUntilNothingWaits()
        assertEquals("One bulk call for the merged row: ${tandoor.requests}", 1, bulks())
        assertEquals("No entry patched one by one: ${tandoor.requests}", emptyList<String>(), patches(milk + cream))
        assertEquals("The server keeps both entries", 2, list.all().count { it.food == "mjölk" })

        // Back to the list, still as the grid.
        app.device.pressBack()
        app.waitFor(By.res("shopping:tile:${cream.first()}"))
        assertTrue("The cream entries are untouched", unchecked(cream) != null)
        app.capture("phase5-integration-grid-after-store")
    }

    /** The list's menu ("More options") and its item [text]; again while the menu did not open. */
    private fun openMenuItem(text: String) {
        for (attempt in 1..TAP_ATTEMPTS) {
            app.waitFor(By.desc(MORE_OPTIONS)).click()
            if (app.device.wait(Until.hasObject(By.text(text)), TAP_WAIT_MS) == true) break
        }
        app.tap(text)
    }

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

    /** Starts the shown step's [length] timer ("Adjust the time", "Start …"). */
    private fun startTimer(length: String) {
        app.tapDescriptionMatching(Pattern.compile("Start (a )?${Pattern.quote(length)}.*"))
        app.waitFor(By.text("Adjust the time"))
        app.tapTextMatching(Pattern.compile("Start .+"))
        app.waitUntilGone(By.text("Adjust the time"))
    }

    /** "Cook another recipe" (+) and [name] in its picker, as in Phase4SmokeTest. */
    private fun pickSecondRecipe(name: String) {
        app.tapDescription("Cook another recipe")
        val search = app.waitForNode("the picker's search", predicate = ::isAddField)
        assertTrue("The search takes text", app.setNodeText(search, name))
        val isRow = { node: AccessibilityNodeInfo -> node.text?.toString() == name && !isAddField(node) && app.clickableOf(node) != null }
        app.waitForNode("the picker's \"$name\"", predicate = isRow)
        for (attempt in 1..TAP_ATTEMPTS) {
            app.findNode(isRow)?.let(app::clickableOf)?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (app.device.wait(Until.hasObject(tab(name)), TAP_WAIT_MS) == true) break
        }
    }

    private fun tab(name: String): BySelector = By.desc(Pattern.compile("${Pattern.quote(name)}, (Step \\d+ of \\d+|Done!).*"))

    private fun timerOf(recipe: String): BySelector = By.desc(Pattern.compile("${Pattern.quote(recipe)}: .* left"))

    private fun unchecked(ids: List<Long>): String? =
        tandoor.shopping.all().filter { it.id in ids && !it.checked }.map { it.id }.takeIf { it.isNotEmpty() }?.let { "unchecked: $it" }

    private fun bulks(): Int = tandoor.requests.count { it == "POST ${ENTRIES}bulk/" }

    private fun patches(ids: List<Long>): List<String> = tandoor.requests.filter { call -> ids.any { call == "PATCH $ENTRIES$it/" } }

    private companion object {
        const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"

        /** feature:shell `settings_cooking` and `settings_handsfree_enabled`. */
        const val COOKING_SETTINGS = "Cook mode and timers"
        const val HANDSFREE_SETTING = "Handsfree in cook mode"

        /** feature:cook's handsfree bar and kitchen display. */
        const val VOLUME_KEYS_ON = "Volume keys turn the steps"
        const val KITCHEN = "Kitchen display"
        const val LEAVE_KITCHEN = "Leave the kitchen display"

        /** The demo's recipes (core:data assets/demo/en): the salmon's first step boils 20 minutes, the tacos' simmer 5. */
        const val DEMO_TONIGHT = "Baked salmon with dill potatoes"
        const val DEMO_SECOND = "Friday tacos"
        const val TONIGHT_TIMER = "20 min"
        const val SECOND_TIMER = "5 min"

        /** feature:shopping: the merged row's description, `shopping_more`, `shopping_view_grid`, `shopping_store_mode_title`. */
        val MERGED_MILK: Pattern = Pattern.compile("3 l mjölk, .*2 rows on the list.*")
        const val MORE_OPTIONS = "More options"
        const val SHOW_AS_GRID = "Show as grid"
        const val STORE_MODE = "Store mode"
        const val ENTRIES = "/api/shopping-list-entry/"
    }
}
