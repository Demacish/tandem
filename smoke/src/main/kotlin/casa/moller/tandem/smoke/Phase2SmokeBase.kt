package casa.moller.tandem.smoke

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.io.File
import java.util.regex.Pattern
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * What the Phase 2 release smoke tests share ([Phase2SmokeTest], [Phase2AccountsSmokeTest]): the
 * rules (the network always comes back, [FakeTandoor], the failure report), the password login,
 * and the steps on the shopping list (rows, checks, the add and edit sheets, offline and online),
 * driven through accessibility actions where a tap could miss (docs/BYGGE.md "Stabilitet").
 */
abstract class Phase2SmokeBase {
    protected val instrumentation = InstrumentationRegistry.getInstrumentation()
    protected val app = TandemApp(UiDevice.getInstance(instrumentation))
    private var networkTurnedOff = false
    private val density = instrumentation.context.resources.displayMetrics.density

    /** The network always comes back, also after a failure. The outermost rule (see Phase1SmokeTest). */
    @get:Rule(order = 0)
    val network = object : TestWatcher() {
        override fun finished(description: Description) {
            if (networkTurnedOff) app.setNetworkEnabled(true)
        }
    }

    @get:Rule(order = 1)
    val tandoor = FakeTandoor(instrumentation.context.assets)

    /** On failure: screenshot, window hierarchy, the server's requests, 404s and state ([extraReport] at the end). */
    @get:Rule(order = 2)
    val artifacts = object : TestWatcher() {
        override fun failed(e: Throwable, description: Description) {
            val name = "FAILED-${description.methodName}"
            app.capture(name)
            runCatching {
                File(TandemApp.outputDir(), "$name-requests.txt").writeText(
                    (
                        tandoor.requests + listOf("--- while offline:") + tandoor.requestsWhileOffline +
                            listOf("--- 404:") + tandoor.notFound +
                            listOf("--- shopping list:") + tandoor.shopping.all().map { it.toString() } +
                            listOf("--- list recipes:") + tandoor.shopping.listRecipes().map { it.toString() } +
                            listOf("--- meal plan:") + tandoor.mealPlan.all().map { it.toString() } +
                            listOf("--- meal plan creates:") + tandoor.mealPlan.creates +
                            extraReport() + listOf(app.networkReport())
                        ).joinToString("\n"),
                )
            }
        }
    }

    /** Every fake Tandoor of the test: all of them go offline with the emulator. */
    protected abstract fun servers(): List<FakeTandoor>

    /** More lines for the failure report (another server's requests and list). */
    protected open fun extraReport(): List<String> = emptyList()

    @Before
    fun startFresh() {
        app.clearData()
    }

    /** The custom accessibility action labelled [label] on [node] or one of its ancestors (a merged card). */
    protected fun customAction(node: AccessibilityNodeInfo, label: String): Pair<AccessibilityNodeInfo, Int>? {
        var current: AccessibilityNodeInfo? = node
        repeat(MAX_ANCESTORS) {
            val candidate = current ?: return null
            candidate.actionList.firstOrNull { it.label?.toString() == label }?.let { return candidate to it.id }
            current = candidate.parent
        }
        return null
    }

    /** True once no app node matches [predicate] any more, false after [timeoutMs]. */
    protected fun nodeGone(timeoutMs: Long, predicate: (AccessibilityNodeInfo) -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (app.findNode(predicate) == null) return true
            SystemClock.sleep(SETTLE_POLL_MS)
        }
        return false
    }

    /** True as soon as an app node matches [predicate] (also one below a sheet's fold), false after [timeoutMs]. */
    protected fun nodeShows(timeoutMs: Long, predicate: (AccessibilityNodeInfo) -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (app.findNode(predicate) != null) return true
            SystemClock.sleep(SETTLE_POLL_MS)
        }
        return false
    }

    /** Password login and the real first sync, like [Phase1SmokeTest]. */
    protected fun logIn() {
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

    /**
     * A list row by its food: one TalkBack item described "<amount> <food>, <aisle>[, ...]"
     * (feature:shopping ShoppingEntryRow). "mjöl" does not match "2 l mjölk, Mejeri".
     */
    protected fun row(food: String): BySelector = By.desc(Pattern.compile("(?:[^,]* )?${Pattern.quote(food)}, .*"))

    /**
     * The row of [food], scrolled into the upper part of the screen and at rest: a snackbar
     * ("Ångra", 10 s) and the "+ Vara" button sit at the bottom, and a tap there would hit them;
     * a tap while the list still flings after a swipe lands where the row WAS (seen in CI: a
     * check that never happened).
     */
    protected fun rowOnScreen(food: String): UiObject2 {
        val limit = app.device.displayHeight * UPPER_PART
        repeat(MAX_NUDGES) {
            val found = settled(row(food), app.findInList(row(food)))
            // Stale: the list moved between finding and reading the row (e.g. Handla's
            // "Packa upp" card appearing above it after a check, Fas S); find it again.
            val bounds = runCatching { found.visibleBounds }.getOrNull()
            if (bounds == null) {
                app.device.waitForIdle()
                return@repeat
            }
            if (bounds.bottom <= limit && bounds.height() >= MIN_ROW_DP * density) return found
            val x = app.device.displayWidth / 2
            val upper = (app.device.displayHeight * UPPER_PART).toInt()
            val nudged = (app.device.displayHeight * NUDGE_TO).toInt()
            // Content up when the row is low, down when it is cut at the top.
            if (bounds.bottom > limit) {
                app.device.swipe(x, upper, x, nudged, SWIPE_STEPS)
            } else {
                app.device.swipe(x, nudged, x, upper, SWIPE_STEPS)
            }
            app.device.waitForIdle()
        }
        return settled(row(food), app.findInList(row(food)))
    }

    /** Clicks the first match of [selectors] once it lies still; retried while nodes go stale. */
    protected fun clickAtRest(selectors: List<BySelector>) {
        val deadline = SystemClock.uptimeMillis() + TandemApp.UI_TIMEOUT_MS
        while (true) {
            val target = selectors.firstNotNullOfOrNull { selector ->
                app.device.findObject(selector)?.let { settledOrNull(selector, it) }
            }
            if (target != null && runCatching { target.click() }.isSuccess) return
            if (SystemClock.uptimeMillis() > deadline) throw AssertionError("Nothing to click at rest: $selectors")
            SystemClock.sleep(SETTLE_POLL_MS)
        }
    }

    /** [settled], or null when [selector] no longer matches. */
    protected fun settledOrNull(selector: BySelector, found: UiObject2): UiObject2? =
        if (app.device.hasObject(selector)) settled(selector, found) else null

    /** [found] again once its bounds stop changing (at most [SETTLE_MS]). */
    protected fun settled(selector: BySelector, found: UiObject2): UiObject2 {
        var current = found
        var bounds = runCatching { current.visibleBounds }.getOrNull()
        val deadline = SystemClock.uptimeMillis() + SETTLE_MS
        while (SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(SETTLE_POLL_MS)
            val again = app.device.findObject(selector) ?: return current
            val now = runCatching { again.visibleBounds }.getOrNull() ?: continue
            current = again
            if (now == bounds) return current
            bounds = now
        }
        return current
    }

    /**
     * Taps [food]'s row (check); a checked row leaves the list for the folded "Checked". The row
     * is a checkbox node: when a tap was lost (the row still there and unchecked), it is tapped
     * again, never when it took effect.
     */
    protected fun check(food: String) {
        repeat(TAP_ATTEMPTS) {
            // A row gone stale before the tap took no tap: wait as for a lost tap, then retry.
            runCatching { rowOnScreen(food).click() }.onFailure { error ->
                if (error !is StaleObjectException) throw error
                app.device.waitForIdle()
            }
            val deadline = SystemClock.uptimeMillis() + TAP_WAIT_MS
            while (SystemClock.uptimeMillis() < deadline) {
                val current = app.device.findObject(row(food)) ?: return
                // Stale: the row left the list between finding and reading it (it was checked).
                val checked = runCatching { current.isChecked }.getOrElse { return }
                if (checked) {
                    app.waitUntilGone(row(food))
                    return
                }
                SystemClock.sleep(SETTLE_POLL_MS)
            }
        }
        app.waitUntilGone(row(food))
    }

    /**
     * "Check all" in [aisle] (above the rows, so the list is scrolled back up first), at rest and
     * again while its rows are still there: checking an aisle twice changes nothing.
     */
    protected fun checkWholeAisle(aisle: String, foods: List<String>) {
        val button = By.clickable(true).hasDescendant(By.desc("Check all in $aisle"))
        for (attempt in 1..TAP_ATTEMPTS) {
            app.scrollBackTo(By.desc("Check all in $aisle"))
            settled(button, app.waitFor(button)).click()
            if (app.device.wait(Until.gone(row(foods.first())), TAP_WAIT_MS) == true) break
        }
        foods.forEach { app.waitUntilGone(row(it)) }
    }

    /** Long press on [food]'s row (the edit sheet, again when the press was lost) and [button] there. */
    protected fun editRow(food: String, button: String) {
        openEdit(food)
        // Again while the sheet is still open: a click that took effect closes it.
        // Through the button's accessibility action: the sheet may still slide in, and the keyboard
        // or the sheet's own scroll may hide the button (see addItems).
        for (attempt in 1..TAP_ATTEMPTS) {
            app.waitForNode("\"$button\"") { it.text?.toString() == button && app.clickableOf(it)?.isEnabled == true }
            app.clickNodeWithText(button)
            if (app.device.wait(Until.gone(By.text(EDIT_TITLE)), TAP_WAIT_MS) == true) break
        }
        app.waitUntilGone(By.text(EDIT_TITLE))
    }

    protected fun openEdit(food: String) {
        repeat(TAP_ATTEMPTS) {
            rowOnScreen(food).longClick()
            if (app.device.wait(Until.hasObject(By.text(EDIT_TITLE)), TAP_WAIT_MS) == true) return
        }
        app.waitFor(By.text(EDIT_TITLE))
    }

    /**
     * "Add item…" above the list, the free text, the field's "Done" action, and the sheet closed
     * again. Driven through accessibility actions on the sheet's nodes ([TandemApp.appNodes]), not
     * taps: the on-screen keyboard (shown or not, before or after the text is set) covered "Add n
     * items" when it stood below the preview and the suggestions (since 0.3.0 it stands right
     * below the field), and a tap meant for the keyboard's "Done" or the hidden button could land
     * on the sheet's scrim, which closes the sheet without adding (the field then vanished too,
     * so earlier versions of this test took it for "added"). Added
     * means: the sheet is still open and its field was emptied, which the ViewModel does only
     * after a successful add. A sheet that closed anyway keeps the text (SavedStateHandle), so it
     * is opened again and the text submitted again; an add that already happened empties the text,
     * and a second "Done" on an empty field adds nothing.
     */
    protected fun addItems(text: String, count: Int) {
        val button = if (count == 1) "Add 1 item" else "Add $count items"
        openAddSheet()
        val deadline = SystemClock.uptimeMillis() + TandemApp.UI_TIMEOUT_MS
        var submitted = false
        while (true) {
            if (SystemClock.uptimeMillis() > deadline) {
                app.capture("phase2-add-not-added")
                throw AssertionError("\"$text\" was never added (\"$button\")")
            }
            if (!app.device.hasObject(By.text(ADD_TITLE))) openAddSheet()
            val field = app.findNode(::isAddField)
            val current = field?.text?.toString().orEmpty()
            when {
                field == null -> SystemClock.sleep(SETTLE_POLL_MS)

                submitted && current.isEmpty() -> break

                current != text -> {
                    // Not set yet (or the field was opened again): set it, then wait for the preview.
                    app.setNodeText(field, text)
                    submitted = false
                }

                // The field holds the text, which SubmitAdd reads: "Done" as the keyboard would send
                // it, else "Add n items" (right below the field, so above the keyboard).
                else -> {
                    submitted = app.imeEnter(field) || app.clickNodeWithText(button)
                    if (submitted) waitForEmptyAddField() else SystemClock.sleep(SETTLE_POLL_MS)
                }
            }
        }
        app.capture("phase2-add-added")
        closeAddSheet()
    }

    /** The add sheet's field (the only text field while the sheet is open). */
    protected fun isAddField(node: AccessibilityNodeInfo): Boolean = node.className?.toString() == EDIT_TEXT && node.isEditable

    protected fun waitForEmptyAddField() {
        val deadline = SystemClock.uptimeMillis() + TAP_WAIT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            val field = app.findNode(::isAddField) ?: return
            if (field.text.isNullOrEmpty()) return
            SystemClock.sleep(SETTLE_POLL_MS)
        }
    }

    /** "Add item…" above the list (at rest), again until the sheet shows. */
    protected fun openAddSheet() {
        val opener = By.clickable(true).hasDescendant(By.text("Add item…"))
        repeat(TAP_ATTEMPTS) {
            app.scrollBackTo(By.text("Add item…"))
            clickAtRest(listOf(opener, By.text("Add item…")))
            if (app.device.wait(Until.hasObject(By.text(ADD_TITLE)), TAP_WAIT_MS) == true) return
        }
        app.waitFor(By.text(ADD_TITLE))
    }

    /** Back until the sheet is gone (the first Back may only close the keyboard). */
    protected fun closeAddSheet() {
        repeat(CLOSE_ATTEMPTS) {
            if (!app.device.hasObject(By.text(ADD_TITLE))) return
            app.device.pressBack()
            app.device.wait(Until.gone(By.text(ADD_TITLE)), TAP_WAIT_MS / 2)
        }
        app.waitUntilGone(By.text(ADD_TITLE))
    }

    protected fun goOffline() {
        servers().forEach { it.offline = true }
        networkTurnedOff = true
        app.setNetworkEnabled(false)
        assertTrue("The emulator must be offline: ${app.networkReport()}", !app.hasNetwork())
    }

    protected fun goOnline() {
        servers().forEach { it.offline = false }
        app.setNetworkEnabled(true)
        networkTurnedOff = false
    }

    /** Until the top bar's "n waiting" (and every row's "waiting to sync") is gone: the outbox is empty. */
    protected fun waitUntilNothingWaits() {
        app.waitUntilGone(By.desc(WAITING), DRAIN_TIMEOUT_MS)
    }

    /** Polls [mismatch] (null = done) for up to [DRAIN_TIMEOUT_MS]. */
    protected fun waitForServer(what: String, mismatch: () -> String?) {
        val deadline = SystemClock.uptimeMillis() + DRAIN_TIMEOUT_MS
        var last = mismatch()
        while (last != null) {
            if (SystemClock.uptimeMillis() > deadline) throw AssertionError("Timed out waiting for $what: $last")
            SystemClock.sleep(POLL_MS)
            last = mismatch()
        }
    }

    /** [TandemApp.findInList] again and again until [selector] shows (data that arrives with a sync). */
    protected fun waitForInList(selector: BySelector, timeoutMs: Long): UiObject2 {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            val found = runCatching { app.findInList(selector) }
            found.getOrNull()?.let { return it }
            if (SystemClock.uptimeMillis() > deadline) throw found.exceptionOrNull() ?: AssertionError("Not in the list: $selector")
            SystemClock.sleep(POLL_MS)
        }
    }

    companion object {
        const val WELCOME_TEXT = "Welcome to Tandem"
        const val EDIT_TITLE = "Edit item"
        const val ADD_TITLE = "Add to the list"

        /** The top bar's chip ("3 changes waiting to sync") and a pending row's "..., waiting to sync". */
        val WAITING: Pattern = Pattern.compile(".*waiting to sync")

        /** fas1 recordings. */
        const val PASTA_ID = 2L
        const val PASTA = "Krämig svamppasta"

        /** "Add n items to the shopping list" (Handla för veckan and the recipe's sheet). */
        val ADD_TO_LIST: Pattern = Pattern.compile("Add \\d+ items? to the shopping list")
        const val MAX_ANCESTORS = 4

        const val FIRST_SYNC_TIMEOUT_MS = 90_000L
        const val DRAIN_TIMEOUT_MS = 120_000L
        const val POLL_MS = 500L
        const val UPPER_PART = 0.6
        const val NUDGE_TO = 0.35
        const val MIN_ROW_DP = 40
        const val MAX_NUDGES = 4
        const val SWIPE_STEPS = 20
        const val CLOSE_ATTEMPTS = 3
        const val TAP_ATTEMPTS = 3
        const val EDIT_TEXT = "android.widget.EditText"
        const val TAP_WAIT_MS = 4_000L
        const val SETTLE_MS = 3_000L
        const val SETTLE_POLL_MS = 250L
    }
}
