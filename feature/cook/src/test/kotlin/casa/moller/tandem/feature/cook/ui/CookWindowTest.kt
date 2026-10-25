package casa.moller.tandem.feature.cook.ui

import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cook mode's window flags when one cook entry replaces another (fix-ui, 0.2.0 review): Navigation 3
 * keeps the outgoing entry composed during the transition, so the new entry's effect runs before the
 * old one's `onDispose`. The screen must stay on for the visible cook mode.
 */
@RunWith(AndroidJUnit4::class)
class CookWindowTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun theScreenStaysOnWhenOneCookModeReplacesAnother() {
        var first by mutableStateOf(true)
        var second by mutableStateOf(false)
        lateinit var view: View
        composeRule.setContent {
            view = LocalView.current
            if (first) KeepScreenOn()
            if (second) KeepScreenOn()
        }
        composeRule.waitForIdle()
        assertTrue(view.keepScreenOn)

        // Cook mode Y comes in while X is still shown, then X leaves.
        second = true
        composeRule.waitForIdle()
        first = false
        composeRule.waitForIdle()
        assertTrue("the visible cook mode keeps the screen on", view.keepScreenOn)

        // "Klart!" in Y pops back: X comes back first, then Y leaves.
        first = true
        composeRule.waitForIdle()
        second = false
        composeRule.waitForIdle()
        assertTrue(view.keepScreenOn)

        first = false
        composeRule.waitForIdle()
        assertFalse("no cook mode left: the screen may turn off", view.keepScreenOn)
    }

    @Test
    fun theReferenceCountNeverGoesBelowZero() {
        lateinit var view: View
        composeRule.setContent {
            view = LocalView.current
            KeepScreenOn()
        }
        composeRule.runOnIdle {
            val window = CookWindowState.of(view)
            window.releaseScreenOn(view)
            window.releaseScreenOn(view)
            assertFalse(view.keepScreenOn)
            window.acquireScreenOn(view)
            assertTrue(view.keepScreenOn)
        }
    }
}
