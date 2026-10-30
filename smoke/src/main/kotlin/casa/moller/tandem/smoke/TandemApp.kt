package casa.moller.tandem.smoke

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.io.File
import java.util.regex.Pattern

/**
 * The app under test, driven like a user would: by the texts on screen (English, the emulator's
 * locale). Nothing here knows app classes, so R8's renaming does not matter.
 */
class TandemApp(val device: UiDevice) {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    /** `pm clear`: no account, no settings, no Keystore keys of the app. */
    fun clearData() {
        shell("pm clear $PACKAGE")
    }

    /** Starts the app from its launcher entry, like tapping the icon. */
    fun launch() {
        val component = requireNotNull(instrumentation.context.packageManager.getLaunchIntentForPackage(PACKAGE)?.component) {
            "$PACKAGE is not installed (install :app's sideloadMinified APK first)"
        }
        shell("am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n ${component.flattenToShortString()}")
        waitFor(By.pkg(PACKAGE), LAUNCH_TIMEOUT_MS)
    }

    /**
     * A slow CI emulator sometimes shows "System UI isn't responding" (or the same for another
     * system app) over everything. That is the emulator, not Tandem: tap "Wait" and go on. A
     * dialog about Tandem itself is never dismissed, so an ANR of the app fails the test.
     */
    private fun dismissForeignAnrDialog() {
        val title = device.findObject(By.textEndsWith(" isn’t responding")) ?: device.findObject(By.textEndsWith(" isn't responding"))
        if (title == null || title.text.startsWith("Tandem")) return
        device.findObject(By.text("Wait"))?.click()
        device.waitForIdle()
    }

    /** Process death while in the background: Android keeps the task and the saved state. */
    fun killInBackground() {
        device.pressHome()
        device.waitForIdle()
        val deadline = SystemClock.uptimeMillis() + LAUNCH_TIMEOUT_MS
        while (isRunning()) {
            check(SystemClock.uptimeMillis() < deadline) { "$PACKAGE is still running after am kill" }
            shell("am kill $PACKAGE")
            SystemClock.sleep(POLL_MS)
        }
    }

    /** A cold start without saved state: `am force-stop` ends the process and its activities. */
    fun forceStop() {
        shell("am force-stop $PACKAGE")
    }

    fun isRunning(): Boolean = shell("pidof $PACKAGE").isNotBlank()

    /** Waits until [selector] is on screen. */
    fun waitFor(selector: BySelector, timeoutMs: Long = UI_TIMEOUT_MS): UiObject2 {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            dismissForeignAnrDialog()
            device.findObject(selector)?.let { return it }
            if (SystemClock.uptimeMillis() > deadline) {
                throw AssertionError("Not on screen within $timeoutMs ms: $selector (app running: ${isRunning()})")
            }
            SystemClock.sleep(POLL_MS)
        }
    }

    /** Finds [selector], scrolling the screen's content up if it is below the fold. */
    fun scrollTo(selector: BySelector): UiObject2 = scrollToAny(listOf(selector))

    /**
     * Finds the smallest element that matches any of [selectors] (so a clickable row wins over a
     * clickable container around it), scrolling the content up if nothing matches yet.
     */
    private fun scrollToAny(selectors: List<BySelector>): UiObject2 {
        val deadline = SystemClock.uptimeMillis() + UI_TIMEOUT_MS
        var scrolls = 0
        while (true) {
            dismissForeignAnrDialog()
            selectors.flatMap { device.findObjects(it) }
                .minByOrNull { it.visibleBounds.width().toLong() * it.visibleBounds.height() }
                ?.let { return if (scrolls > 0) settled(selectors, it) else it }
            if (SystemClock.uptimeMillis() > deadline) {
                throw AssertionError("Not on screen within $UI_TIMEOUT_MS ms: $selectors (app running: ${isRunning()})")
            }
            if (scrolls < MAX_SCROLLS) {
                scrolls++
                val x = device.displayWidth / 2
                device.swipe(x, device.displayHeight * 2 / 3, x, device.displayHeight / 3, SWIPE_STEPS)
                device.waitForIdle()
            } else {
                SystemClock.sleep(POLL_MS)
            }
        }
    }

    /**
     * Phase 2 (konto lane): a swipe can leave the content flinging after `waitForIdle`, so an
     * element found mid-fling would be clicked where it WAS (Mer grew by a row and the logout
     * button then appeared while the list still moved). Waits until the element's bounds stop
     * changing (at most [SETTLE_TIMEOUT_MS]) and returns it found again.
     */
    private fun settled(selectors: List<BySelector>, found: UiObject2): UiObject2 {
        var current = found
        var bounds = current.visibleBounds
        val deadline = SystemClock.uptimeMillis() + SETTLE_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(POLL_MS)
            val again = selectors.flatMap { device.findObjects(it) }
                .minByOrNull { it.visibleBounds.width().toLong() * it.visibleBounds.height() } ?: return current
            val now = again.visibleBounds
            current = again
            if (now == bounds) return current
            bounds = now
        }
        return current
    }

    /**
     * Compose reports a button's label as a child text node of the clickable node (TalkBack
     * merges them), so "the button with [text]" is a clickable node with that text below it, or
     * a clickable node that carries the text itself.
     */
    fun clickableWithText(text: String): List<BySelector> = listOf(
        By.clickable(true).hasDescendant(By.text(text), MAX_LABEL_DEPTH),
        By.clickable(true).text(text),
    )

    /** Waits for a clickable element labelled [text] (scrolling if needed) and clicks it. */
    fun tap(text: String) {
        scrollToAny(clickableWithText(text)).click()
    }

    /**
     * Taps [text] until [expected] shows (at most [attempts] taps). On the CI emulator a tap now
     * and then lands while a screen still settles and is lost (seen on "About Tandem" in More,
     * 0.2.0 integration); the tap is only repeated while the button is still there.
     */
    fun tapUntil(text: String, expected: BySelector, attempts: Int = TAP_ATTEMPTS): UiObject2 {
        tap(text)
        repeat(attempts - 1) {
            device.wait(Until.findObject(expected), TAP_SETTLE_MS)?.let { return it }
            clickableWithText(text).firstNotNullOfOrNull { device.findObject(it) }?.click()
        }
        return waitFor(expected)
    }

    /** Waits until a clickable element labelled [text] is on screen, without scrolling. */
    fun waitForClickable(text: String, timeoutMs: Long = UI_TIMEOUT_MS) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (clickableWithText(text).none { device.hasObject(it) }) {
            dismissForeignAnrDialog()
            if (SystemClock.uptimeMillis() > deadline) {
                throw AssertionError("No clickable \"$text\" within $timeoutMs ms (app running: ${isRunning()})")
            }
            SystemClock.sleep(POLL_MS)
        }
    }

    /**
     * Icon buttons have no text: Compose puts the icon's content description on a child of the
     * clickable node (or on the node itself), like a button's label.
     */
    fun clickableWithDescription(description: String): List<BySelector> = listOf(
        By.clickable(true).hasDescendant(By.desc(description), MAX_LABEL_DEPTH),
        By.clickable(true).desc(description),
    )

    /** Waits for a clickable element described as [description] (scrolling if needed) and clicks it. */
    fun tapDescription(description: String) {
        scrollToAny(clickableWithDescription(description)).click()
    }

    /** Phase 1b: waits for a clickable element whose description matches [description] and clicks it. */
    fun tapDescriptionMatching(description: Pattern) {
        scrollToAny(
            listOf(
                By.clickable(true).hasDescendant(By.desc(description), MAX_LABEL_DEPTH),
                By.clickable(true).desc(description),
            ),
        ).click()
    }

    /** Waits for a clickable element whose text matches [text] (e.g. "Start 5 min" in "Adjust the time") and clicks it. */
    fun tapTextMatching(text: Pattern) {
        scrollToAny(
            listOf(
                By.clickable(true).hasDescendant(By.text(text), MAX_LABEL_DEPTH),
                By.clickable(true).text(text),
            ),
        ).click()
    }

    /** Waits until nothing matches [selector] any more. */
    fun waitUntilGone(selector: BySelector, timeoutMs: Long = UI_TIMEOUT_MS) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (device.hasObject(selector)) {
            if (SystemClock.uptimeMillis() > deadline) throw AssertionError("Still on screen after $timeoutMs ms: $selector")
            SystemClock.sleep(POLL_MS)
        }
    }

    /**
     * Finds [selector] in a list whose order the test does not fix: scrolls down first, then back
     * up, until it shows.
     */
    fun findInList(selector: BySelector): UiObject2 {
        repeat(LIST_SCROLLS + 1) {
            device.findObject(selector)?.let { return it }
            swipeContent(up = true)
        }
        repeat(LIST_SCROLLS * 2) {
            device.findObject(selector)?.let { return it }
            swipeContent(up = false)
        }
        return device.findObject(selector) ?: throw AssertionError("Not in the list: $selector (app running: ${isRunning()})")
    }

    /**
     * Finds the clickable row labelled [text] in a list that may be scrolled ANYWHERE (both
     * directions, like [findInList]), waits until it stops moving and clicks it. [tap] only
     * scrolls towards the end of the list, so a row above the viewport (Mer's "At home" when an
     * earlier test left Mer scrolled down) would be pushed further away (Fas S review, fix-bygg).
     */
    fun tapInList(text: String) {
        val selectors = clickableWithText(text)
        val found = findInList(selectors.first())
        settled(selectors, found).click()
    }

    /** Clicks the clickable row labelled [text] if it is on screen now, without scrolling; false if not. */
    fun tapIfShown(text: String): Boolean {
        val found = clickableWithText(text).firstNotNullOfOrNull { device.findObject(it) } ?: return false
        found.click()
        return true
    }

    /** Scrolls the content back towards its top until [selector] shows (e.g. a list's search field). */
    fun scrollBackTo(selector: BySelector): UiObject2 {
        repeat(MAX_SCROLLS * 2) {
            device.findObject(selector)?.let { return it }
            swipeContent(up = false)
        }
        return device.findObject(selector) ?: throw AssertionError("Not found above: $selector (app running: ${isRunning()})")
    }

    private fun swipeContent(up: Boolean) {
        val x = device.displayWidth / 2
        val low = device.displayHeight * 2 / 3
        val high = device.displayHeight / 3
        if (up) device.swipe(x, low, x, high, SWIPE_STEPS) else device.swipe(x, high, x, low, SWIPE_STEPS)
        device.waitForIdle()
    }

    /**
     * Turns the emulator's networks off (airplane mode, and Wi-Fi and mobile data if a network
     * survives it) or back on, and waits until the device agrees. Both work from the
     * instrumentation's shell, which runs as the `shell` user (like `adb shell`).
     *
     * Loopback is never affected: the fake server on localhost stays reachable, so the test also
     * sets [FakeTandoor.offline]. What this changes is what the app sees: no default network, so
     * its sync pauses (`SyncPause.OFFLINE`), it shows "Offline" and reads only what it stored.
     */
    fun setNetworkEnabled(enabled: Boolean) {
        if (enabled) {
            shell("cmd connectivity airplane-mode disable")
            shell("svc wifi enable")
            shell("svc data enable")
            if (!waitForNetwork(connected = true, NETWORK_TIMEOUT_MS)) {
                throw AssertionError("The emulator has no network again after $NETWORK_TIMEOUT_MS ms: ${networkReport()}")
            }
        } else {
            shell("cmd connectivity airplane-mode enable")
            if (!waitForStableOffline(NETWORK_TIMEOUT_MS / 2)) {
                shell("svc wifi disable")
                shell("svc data disable")
                if (!waitForStableOffline(NETWORK_TIMEOUT_MS / 2)) {
                    throw AssertionError("Could not turn the emulator's network off: ${networkReport()}")
                }
            }
        }
    }

    /**
     * Waits until the device has had no default network for [OFFLINE_SETTLE_MS] in a row.
     * Airplane mode takes Wi-Fi down first; the emulator's mobile data then becomes the default
     * network for a few hundred milliseconds before the radio is off too (seen in CI logcat), so
     * the first "no network" is only the gap between the two.
     */
    private fun waitForStableOffline(timeoutMs: Long): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var offlineSince: Long? = null
        while (true) {
            val now = SystemClock.uptimeMillis()
            offlineSince = if (hasNetwork()) null else offlineSince ?: now
            if (offlineSince != null && now - offlineSince >= OFFLINE_SETTLE_MS) return true
            if (now > deadline) return false
            SystemClock.sleep(POLL_MS)
        }
    }

    /** True when the default network has internet: what the app's ConnectivityMonitor checks. */
    fun hasNetwork(): Boolean {
        val manager = instrumentation.context.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun waitForNetwork(connected: Boolean, timeoutMs: Long): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (hasNetwork() != connected) {
            if (SystemClock.uptimeMillis() > deadline) return false
            SystemClock.sleep(POLL_MS)
        }
        return true
    }

    /** Airplane mode and the default network, for a failure message. */
    fun networkReport(): String {
        val airplane = shell("settings get global airplane_mode_on").trim()
        val network = shell("dumpsys connectivity").lineSequence().firstOrNull { "Active default network" in it }?.trim()
        return "airplane_mode_on=$airplane, $network, hasNetwork=${hasNetwork()}"
    }

    /** Types into the [index]-th text field on screen (0 = topmost). */
    fun type(index: Int, text: String) {
        val fields = waitForTextFields(index + 1)
        fields[index].text = text
    }

    private fun waitForTextFields(count: Int): List<UiObject2> {
        val deadline = SystemClock.uptimeMillis() + UI_TIMEOUT_MS
        while (true) {
            val fields = device.findObjects(By.clazz("android.widget.EditText")).sortedBy { it.visibleBounds.top }
            if (fields.size >= count) return fields
            if (SystemClock.uptimeMillis() > deadline) throw AssertionError("Expected $count text fields, found ${fields.size}")
            SystemClock.sleep(POLL_MS)
        }
    }

    /**
     * Phase 2 (integration lane): the app's accessibility nodes straight from the accessibility
     * service, the focused window (a bottom sheet is a window of its own) first. Unlike
     * UiAutomator's [UiDevice.findObject] this includes the nodes the screen does not show:
     * Compose reports a button scrolled out of a sheet, or pushed below the keyboard, with
     * `isVisibleToUser = false`, and UiAutomator leaves such nodes out. Acting on them through
     * their accessibility actions (as TalkBack does) does not depend on where the keyboard, a
     * sheet animation or a fling has them on screen: the cause of the flaky "Add n items" in
     * the Phase 2 smoke test.
     */
    fun appNodes(): List<AccessibilityNodeInfo> {
        val automation = instrumentation.uiAutomation
        val windows = runCatching { automation.windows }.getOrDefault(emptyList()).mapNotNull { it.root }
        val roots = (listOfNotNull(automation.rootInActiveWindow) + windows)
            .filter { it.packageName?.toString() == PACKAGE }
            .distinctBy { it.windowId }
        // Window by window, so every node of the focused window comes before the ones behind it.
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        for (root in roots) {
            val queue = ArrayDeque(listOf(root))
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                nodes += node
                for (index in 0 until node.childCount) node.getChild(index)?.let(queue::addLast)
            }
        }
        return nodes
    }

    /** The first of [appNodes] that matches [predicate], or null. */
    fun findNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? =
        runCatching { appNodes().firstOrNull(predicate) }.getOrNull()

    /** Waits for [findNode] ([what] names it in the failure). */
    fun waitForNode(what: String, timeoutMs: Long = UI_TIMEOUT_MS, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            findNode(predicate)?.let { return it }
            if (SystemClock.uptimeMillis() > deadline) {
                throw AssertionError("No node $what within $timeoutMs ms (app running: ${isRunning()})")
            }
            SystemClock.sleep(POLL_MS)
        }
    }

    /** The node itself when it is clickable, else its nearest clickable ancestor (a button around its label). */
    fun clickableOf(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        repeat(MAX_LABEL_DEPTH + 1) {
            val candidate = current ?: return null
            if (candidate.isClickable) return candidate
            current = candidate.parent
        }
        return null
    }

    /**
     * Clicks the enabled button labelled [text] through its accessibility action (see
     * [appNodes]); false when there is none (yet).
     */
    fun clickNodeWithText(text: String): Boolean = clickNodeWithText { it == text }

    /** [clickNodeWithText] for a label that matches [text] entirely. */
    fun clickNodeWithText(text: Pattern): Boolean = clickNodeWithText { text.matcher(it).matches() }

    private fun clickNodeWithText(label: (String) -> Boolean): Boolean {
        val button = findNode { node -> node.text?.toString()?.let(label) == true && clickableOf(node)?.isEnabled == true }
            ?.let(::clickableOf) ?: return false
        return button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /** Sets [text] on [field] through ACTION_SET_TEXT (what [UiObject2.setText] does too). */
    fun setNodeText(field: AccessibilityNodeInfo, text: String): Boolean {
        val arguments = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    /**
     * The keyboard's action key ("Done") on [field], as an accessibility action (Compose maps
     * it to the field's KeyboardActions): no on-screen keyboard involved. False when the field
     * has no IME action.
     */
    fun imeEnter(field: AccessibilityNodeInfo): Boolean {
        val action = AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER
        return field.actionList.any { it.id == action.id } && field.performAction(action.id)
    }

    /** Screenshot and window hierarchy, kept by the CI job (smoke-results artifact). */
    fun capture(name: String) {
        val dir = outputDir()
        runCatching { device.takeScreenshot(File(dir, "$name.png")) }
        runCatching { device.dumpWindowHierarchy(File(dir, "$name.xml")) }
    }

    fun shell(command: String): String = device.executeShellCommand(command)

    companion object {
        /** applicationId of :app's minified build type. */
        const val PACKAGE = "casa.moller.tandem.minified"
        const val UI_TIMEOUT_MS = 20_000L
        private const val NETWORK_TIMEOUT_MS = 60_000L
        private const val OFFLINE_SETTLE_MS = 3_000L
        private const val LAUNCH_TIMEOUT_MS = 30_000L
        private const val POLL_MS = 250L

        /** How long a found element may keep moving after a swipe (a fling) before it is clicked anyway. */
        private const val SETTLE_TIMEOUT_MS = 3_000L
        private const val MAX_SCROLLS = 6

        /**
         * Fas S (integration): a long list needs more than [MAX_SCROLLS] swipes of a third of the
         * screen. Handla's 29 rows sit below the offline card, "Delad med" and the "Packa upp" card
         * (0.4.0), so a row near the end was out of reach once (main, Phase2SmokeTest).
         */
        private const val LIST_SCROLLS = 12
        private const val SWIPE_STEPS = 20
        private const val MAX_LABEL_DEPTH = 4
        private const val TAP_ATTEMPTS = 3
        private const val TAP_SETTLE_MS = 5_000L

        /** `/sdcard/Android/media/casa.moller.tandem.smoke/smoke`, pulled by scripts/ci/smoke-run.sh. */
        fun outputDir(): File {
            val context = InstrumentationRegistry.getInstrumentation().context

            @Suppress("DEPRECATION")
            val media = context.externalMediaDirs.first()
            // Fas S: `.nomedia` keeps these screenshots out of the gallery the photo picker shows.
            return File(media, "smoke").apply {
                mkdirs()
                File(this, ".nomedia").takeIf { !it.exists() }?.let { runCatching { it.createNewFile() } }
            }
        }
    }
}
