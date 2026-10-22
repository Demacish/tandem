package casa.moller.tandem.benchmark

import android.os.SystemClock
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2

/**
 * The journeys every benchmark and the baseline profile share, driven like a user would: by the
 * English texts on screen (the emulator's locale). Nothing here knows app classes, so R8's
 * renaming does not matter. Demo mode only: no server and no network.
 */
internal object DemoJourney {
    /** :app's "minified" build type (applicationIdSuffix ".minified"). */
    const val PACKAGE = "casa.moller.tandem.minified"

    private const val WELCOME_TEXT = "Welcome to Tandem"
    private const val TRY_DEMO = "Try demo mode"
    private const val RECIPES_TAB = "Recipes"

    /** The demo meal plan's dinner, which demo mode moves to today (core:data demo content). */
    const val DEMO_TONIGHT = "Baked salmon with dill potatoes"

    /** Some of the 14 demo recipes (English), whichever the list shows first. */
    private val DEMO_RECIPES = listOf(
        DEMO_TONIGHT,
        "Cardamom buns",
        "Creamy tomato soup",
        "Friday tacos",
        "Halloumi salad with bulgur",
        "Meatballs with cream sauce",
        "Swedish pancakes",
    )

    private const val UI_TIMEOUT_MS = 30_000L
    private const val POLL_MS = 200L
    private const val MAX_LABEL_DEPTH = 4

    /** `pm clear`: no account, no settings; the next start shows the welcome screen. */
    fun clearData(device: UiDevice) {
        device.executeShellCommand("pm clear $PACKAGE")
    }

    /**
     * Starts the app and turns demo mode on if it is not on yet (a fresh install shows the
     * welcome screen). Afterwards every cold start opens Today in demo mode.
     */
    fun MacrobenchmarkScope.ensureDemoMode() {
        pressHome()
        startActivityAndWait()
        // The welcome screen, or the shell (its Recipes tab) on whatever tab was shown last.
        waitForAny(By.text(WELCOME_TEXT), By.text(RECIPES_TAB))
        if (device.hasObject(By.text(WELCOME_TEXT))) {
            tap(TRY_DEMO)
            waitForTodayContent()
        }
    }

    /** Today in demo mode with its first content (the Tonight card). */
    fun MacrobenchmarkScope.waitForTodayContent() {
        waitFor(By.text(DEMO_TONIGHT))
    }

    /** Opens the Recipes tab and waits for the list. */
    fun MacrobenchmarkScope.openRecipeList(): UiObject2 {
        tap(RECIPES_TAB)
        waitForAny(*DEMO_RECIPES.map { By.text(it) }.toTypedArray())
        return recipeList()
    }

    /** The largest scrollable element on screen: the recipe list (or Today's content). */
    fun MacrobenchmarkScope.recipeList(): UiObject2 = retryWhenStale {
        waitFor(By.scrollable(true))
        val list = device.findObjects(By.scrollable(true))
            .maxBy { it.visibleBounds.width().toLong() * it.visibleBounds.height() }
        // Stay clear of the system's edge gestures (back, home) while flinging.
        list.setGestureMargin(device.displayWidth / GESTURE_MARGIN_DIVISOR)
        list
    }

    /**
     * Flings the recipe list to the end and back to the top, waiting for the frames to settle.
     * The list is looked up again for every fling: Compose replaces its node while images load.
     */
    fun MacrobenchmarkScope.flingDownAndUp() {
        repeat(FLINGS) { fling(Direction.DOWN) }
        repeat(FLINGS) { fling(Direction.UP) }
    }

    private fun MacrobenchmarkScope.fling(direction: Direction) {
        retryWhenStale { recipeList().fling(direction) }
        device.waitForIdle()
    }

    /** UiAutomator's node went away between finding and using it: find it again (a few times). */
    private fun <T> retryWhenStale(block: () -> T): T {
        repeat(STALE_RETRIES - 1) {
            try {
                return block()
            } catch (e: StaleObjectException) {
                SystemClock.sleep(POLL_MS)
            }
        }
        return block()
    }

    /** Opens a recipe from the list and goes back (detail screen, image, scaling). */
    fun MacrobenchmarkScope.openAndCloseRecipe() {
        waitForAny(*DEMO_RECIPES.map { By.text(it) }.toTypedArray())
        tap(DEMO_RECIPES.first { device.hasObject(By.text(it)) })
        waitFor(By.desc("More servings"))
        device.pressBack()
        waitFor(By.scrollable(true))
    }

    private fun MacrobenchmarkScope.tap(text: String) {
        val selectors = listOf(
            By.clickable(true).hasDescendant(By.text(text), MAX_LABEL_DEPTH),
            By.clickable(true).text(text),
        )
        waitForAny(*selectors.toTypedArray())
        retryWhenStale {
            // The smallest match: the row itself, not a container around it.
            selectors.flatMap { device.findObjects(it) }
                .minByOrNull { it.visibleBounds.width().toLong() * it.visibleBounds.height() }
                ?.click() ?: throw StaleObjectException()
        }
        device.waitForIdle()
    }

    private fun MacrobenchmarkScope.waitFor(selector: BySelector): UiObject2 = waitForAny(selector)

    private fun MacrobenchmarkScope.waitForAny(vararg selectors: BySelector): UiObject2 {
        val deadline = SystemClock.uptimeMillis() + UI_TIMEOUT_MS
        while (true) {
            selectors.firstNotNullOfOrNull { device.findObject(it) }?.let { return it }
            if (SystemClock.uptimeMillis() > deadline) {
                throw AssertionError("Not on screen within $UI_TIMEOUT_MS ms: ${selectors.toList()}")
            }
            SystemClock.sleep(POLL_MS)
        }
    }

    private const val FLINGS = 3
    private const val STALE_RETRIES = 5
    private const val GESTURE_MARGIN_DIVISOR = 5
}
