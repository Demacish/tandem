package casa.moller.tandem.core.ui.window

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
 * The shared screen-on flag (fix-ui, Phase 2 review; moved here from cook mode's 0.2.0 fix):
 * Navigation 3 keeps the outgoing entry composed during the transition, so the new entry's effect
 * runs before the old one's `onDispose`. The visible screen must keep the screen on whether it is
 * cook mode over store mode, store mode over cook mode, or one cook mode over another.
 */
@RunWith(AndroidJUnit4::class)
class KeepScreenOnTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun theScreenStaysOnWhenOneScreenReplacesAnother() {
        var storeMode by mutableStateOf(true)
        var cookMode by mutableStateOf(false)
        lateinit var view: View
        composeRule.setContent {
            view = LocalView.current
            if (storeMode) KeepScreenOn()
            if (cookMode) KeepScreenOn()
        }
        composeRule.waitForIdle()
        assertTrue(view.keepScreenOn)

        // A timer notification opens cook mode while store mode is still shown, then store mode leaves.
        cookMode = true
        composeRule.waitForIdle()
        storeMode = false
        composeRule.waitForIdle()
        assertTrue("the visible cook mode keeps the screen on", view.keepScreenOn)

        // Back: store mode comes back first, then cook mode leaves.
        storeMode = true
        composeRule.waitForIdle()
        cookMode = false
        composeRule.waitForIdle()
        assertTrue("the visible store mode keeps the screen on", view.keepScreenOn)

        storeMode = false
        composeRule.waitForIdle()
        assertFalse("nothing left that needs it: the screen may turn off", view.keepScreenOn)
    }

    @Test
    fun theReferenceCountNeverGoesBelowZero() {
        lateinit var view: View
        composeRule.setContent {
            view = LocalView.current
            KeepScreenOn()
        }
        composeRule.runOnIdle {
            val holders = ScreenOnHolders.of(view)
            holders.release(view)
            holders.release(view)
            assertFalse(view.keepScreenOn)
            holders.acquire(view)
            assertTrue(view.keepScreenOn)
        }
    }
}
