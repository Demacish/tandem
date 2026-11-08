package casa.moller.tandem.smoke

import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import java.util.regex.Pattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Release smoke test for Fas 5, lane kok (0.7.0 "I köket", docs/BYGGE.md "Fas 5 – läge"), in the
 * R8-minified sideload app, all in demo mode (no server, no microphone):
 *
 * - [kitchenDisplay_fromTodayAndMore_cookOnTopAndBack]: the kitchen display opens from Idag and
 *   from Mer, shows tonight's meal, "Cook" opens cook mode on top of it and back returns to it.
 * - [handsfree_volumeKeysTurnTheSteps_onlyWhileOn]: handsfree turned on in cook mode's sheet; the
 *   real volume keys (KEYCODE_VOLUME_DOWN/UP, injected like a key press) turn the step, and turned
 *   off they no longer do.
 * - [tinSize_smallerTinScalesTheAmounts_inTheRecipeAndInCookMode]: the demo's chocolate cake (24 cm)
 *   in a 20 cm tin: "Scaled for a 20 cm tin (× 0.69)", 100 g butter becomes 69 g, and cook mode
 *   opened from the recipe uses the same tin.
 */
@RunWith(AndroidJUnit4::class)
class Phase5KokSmokeTest : Phase2SmokeBase() {

    override fun servers(): List<FakeTandoor> = listOf(tandoor)

    private fun startDemo() {
        app.launch()
        app.waitFor(By.text(WELCOME_TEXT))
        app.tap("Try demo mode")
        app.waitFor(By.text(DEMO_TONIGHT))
    }

    @Test
    fun kitchenDisplay_fromTodayAndMore_cookOnTopAndBack() {
        startDemo()
        app.tapDescription(KITCHEN)
        app.waitFor(By.desc(LEAVE_KITCHEN))
        app.waitFor(By.text("Screen stays on"))
        app.waitFor(By.text(DEMO_TONIGHT))
        app.capture("phase5-kitchen")

        app.tapDescription("Cook $DEMO_TONIGHT")
        app.waitFor(By.text(Pattern.compile("Step 1 of \\d+")))
        app.capture("phase5-kitchen-cook")
        app.device.pressBack()
        app.waitFor(By.desc(LEAVE_KITCHEN))
        app.tapDescription(LEAVE_KITCHEN)
        app.waitUntilGone(By.desc(LEAVE_KITCHEN))
        app.waitFor(By.text(DEMO_TONIGHT))

        app.tap("More")
        app.tapInList(KITCHEN)
        app.waitFor(By.desc(LEAVE_KITCHEN))
        app.device.pressBack()
        app.waitUntilGone(By.desc(LEAVE_KITCHEN))
        assertEquals("Demo mode never talks to a server", emptyList<String>(), tandoor.requests.toList())
    }

    @Test
    fun handsfree_volumeKeysTurnTheSteps_onlyWhileOn() {
        startDemo()
        app.tapDescription("Cook $DEMO_TONIGHT")
        app.waitFor(By.text(Pattern.compile("Step 1 of \\d+")))

        setHandsfree(on = true)
        app.waitFor(By.text("Volume keys turn the steps"))
        app.capture("phase5-handsfree-on")
        app.device.pressKeyCode(KeyEvent.KEYCODE_VOLUME_DOWN)
        app.waitFor(By.text(Pattern.compile("Step 2 of \\d+")))
        app.device.pressKeyCode(KeyEvent.KEYCODE_VOLUME_DOWN)
        app.waitFor(By.text(Pattern.compile("Step 3 of \\d+")))
        app.device.pressKeyCode(KeyEvent.KEYCODE_VOLUME_UP)
        app.waitFor(By.text(Pattern.compile("Step 2 of \\d+")))
        app.capture("phase5-handsfree-step2")

        // Off: the keys change the volume again, the step stays.
        setHandsfree(on = false)
        app.waitUntilGone(By.text("Volume keys turn the steps"))
        app.device.pressKeyCode(KeyEvent.KEYCODE_VOLUME_DOWN)
        app.device.waitForIdle()
        app.waitFor(By.text(Pattern.compile("Step 2 of \\d+")))
        assertTrue("Still on step 2", !app.device.hasObject(By.text(Pattern.compile("Step 3 of \\d+"))))
        assertEquals("Demo mode never talks to a server", emptyList<String>(), tandoor.requests.toList())
    }

    @Test
    fun tinSize_smallerTinScalesTheAmounts_inTheRecipeAndInCookMode() {
        startDemo()
        app.tap("Recipes")
        app.tapInList(DEMO_CAKE)
        app.scrollTo(By.desc("Smaller tin"))
        app.waitFor(By.text("24 cm"))
        for (size in 23 downTo 20) {
            app.tapDescription("Smaller tin")
            app.waitFor(By.text("$size cm"))
        }
        app.waitFor(By.text("Scaled for a 20 cm tin (× 0.69)"))
        app.scrollTo(By.descStartsWith("69 g butter"))
        app.capture("phase5-tin-recipe")

        app.scrollBackTo(By.text("Start cooking"))
        app.tap("Start cooking")
        app.waitFor(By.text(Pattern.compile("Step 1 of \\d+")))
        app.scrollTo(By.text("Scaled for a 20 cm tin (× 0.69)"))
        app.capture("phase5-tin-cook")
        assertEquals("Demo mode never talks to a server", emptyList<String>(), tandoor.requests.toList())
    }

    /** Opens the handsfree sheet, sets its main switch and closes it with "Done". */
    private fun setHandsfree(on: Boolean) {
        app.tapDescription("Handsfree")
        val switch = app.waitFor(By.res(HANDSFREE_SWITCH))
        if (switch.isChecked != on) switch.click()
        app.waitFor(By.res(HANDSFREE_SWITCH).checked(on))
        app.tap("Done")
        app.waitUntilGone(By.res(HANDSFREE_SWITCH))
    }

    private companion object {
        const val KITCHEN = "Kitchen display"
        const val LEAVE_KITCHEN = "Leave the kitchen display"

        /** feature:cook COOK_HANDSFREE_SWITCH_TAG (resource id). */
        const val HANDSFREE_SWITCH = "cook:handsfree-switch"

        /** The demo's recipes (core:data assets/demo/en); the cake is baked in a 24 cm tin, 100 g butter. */
        const val DEMO_TONIGHT = "Baked salmon with dill potatoes"
        const val DEMO_CAKE = "Sticky chocolate cake"
    }
}
