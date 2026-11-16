package casa.moller.tandem.smoke

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Until
import java.util.regex.Pattern
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Release smoke test of the 0.9.0 "Till hands" integration (Fas 5, integration-09; docs/BYGGE.md
 * "Integration av Fas 5 (0.9.0)") in the R8-minified sideload app, in demo mode (no server, no
 * Miniflux): cheap checks that the lanes rss and las survive R8 and hang together in Mer.
 *
 * - The recipe inbox (#35) opens from Recipes' toolbar with demo mode's built-in posts; a post's
 *   preview and "Import" (tap 2) open the import review with the post's address, which in demo
 *   mode says that a link needs a server.
 * - Mer shows Recipe inbox (demo: "Not in demo mode"), Assistant and Lock the app; on the smoke
 *   emulator, which has no screen lock, the lock page says so instead of turning anything on.
 * - Demo mode never talks to a server.
 *
 * App Functions have their own scenario (Phase5FunktionerSmokeTest); the Miniflux client is proved
 * by its contract test (miniflux-contract.yml) and unit tests.
 */
@RunWith(AndroidJUnit4::class)
class Phase5TillHandsSmokeTest : Phase2SmokeBase() {
    override fun servers(): List<FakeTandoor> = listOf(tandoor)

    @Test
    fun demoInbox_importOpensTheReview_andMerHasTheNewPages() {
        app.launch()
        app.waitFor(By.text(WELCOME_TEXT))
        app.tap("Try demo mode")
        app.waitFor(By.text(DEMO_TONIGHT))

        // Tap 1: the post's preview; tap 2: "Import" opens the import with the post's address.
        app.tap("Recipes")
        app.tapDescriptionMatching(INBOX_BUTTON)
        app.waitFor(By.text(DEMO_POST))
        app.capture("phase5-inbox-demo")
        app.tapUntil(DEMO_POST, By.text(IMPORT))
        app.capture("phase5-inbox-preview")
        app.tapUntil(IMPORT, By.text(IMPORT_DEMO_TITLE))
        app.waitFor(By.textContains(DEMO_POST_PATH))
        app.capture("phase5-inbox-import")
        app.device.pressBack()
        app.waitUntilGone(By.text(IMPORT_DEMO_TITLE))

        // The inbox is a full-screen page (no tabs): back to the tabs, then Mer.
        backToTab("More")
        openMorePage(INBOX_TITLE, By.text(INBOX_NOT_IN_DEMO), "phase5-inbox-settings-demo")
        openMorePage(ASSISTANT_TITLE, By.text(ASSISTANT_SWITCH), "phase5-assistant-page")
        openMorePage(LOCK_TITLE, By.text(NO_SCREEN_LOCK), "phase5-lock-no-screen-lock")

        assertEquals("Demo mode never talks to a server", emptyList<String>(), tandoor.requests.toList())
    }

    /** Goes back (the preview sheet, the inbox) until the tab [tab] shows, and taps it. */
    private fun backToTab(tab: String) {
        val shown = By.clickable(true).hasDescendant(By.text(tab))
        repeat(BACK_ATTEMPTS) {
            if (app.device.wait(Until.hasObject(shown), BACK_WAIT_MS)) {
                app.tap(tab)
                return
            }
            app.device.pressBack()
        }
        app.tap(tab)
    }

    /** Opens Mer's row [title], waits for [shows] on its page, captures it as [capture] and goes back to Mer. */
    private fun openMorePage(title: String, shows: BySelector, capture: String) {
        app.tapInList(title)
        app.waitFor(shows)
        app.capture(capture)
        app.device.pressBack()
        app.waitUntilGone(shows)
    }

    private companion object {
        /** The demo's tonight (core:data assets/demo/en) and its inbox (core:sync DemoInbox, English). */
        const val DEMO_TONIGHT = "Baked salmon with dill potatoes"
        const val DEMO_POST = "Blueberry pancakes"
        const val DEMO_POST_PATH = "example.com/recipes/blueberry-pancakes"

        /** "Recipe inbox" or "Recipe inbox, 3 new" (feature:recipes strings_inbox.xml). */
        val INBOX_BUTTON: Pattern = Pattern.compile("Recipe inbox(, \\d+ new)?")
        const val IMPORT = "Import"
        const val IMPORT_DEMO_TITLE = "Demo mode"
        const val INBOX_TITLE = "Recipe inbox"
        const val INBOX_NOT_IN_DEMO = "Not in demo mode"
        const val ASSISTANT_TITLE = "Assistant"
        const val ASSISTANT_SWITCH = "Let the assistant use Tandem"
        const val LOCK_TITLE = "Lock the app"
        const val NO_SCREEN_LOCK = "The phone has no screen lock"
        const val BACK_ATTEMPTS = 3
        const val BACK_WAIT_MS = 2_000L
    }
}
