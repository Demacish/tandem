package casa.moller.tandem.core.ui.timer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.timer.CookTimer
import casa.moller.tandem.core.testing.accessibility.assertAccessible
import com.github.takahirom.roborazzi.DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH
import com.github.takahirom.roborazzi.captureRoboImage
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * K2 (device test 2026-11-14, round 3): in the narrow side pane of a phone in landscape a timer's
 * name broke inside a word ("Svamp en · c…") and a ringing card stood one word per line. A row whose
 * texts would get too little of the width puts them above the buttons (a ringing one's Stop first);
 * where there is room the row is as before. Every caller of [TimerBarItem] gets this.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h1200dp-xhdpi")
class TimerBarItemNarrowTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val start = Instant.parse("2000-01-15T18:00:00Z")
    private val now = start + 2.minutes + 21.seconds
    private val running = CookTimer.start(1, "Svampen · ca 5 minuter", 5.minutes, start)
    private val ringing = CookTimer.start(2, "Pastan · 8–10 minuter (10 min)", 2.minutes, start).ring(now)

    private fun show(width: Dp, fontScale: Float = 1f, darkTheme: Boolean = false) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                TandemTheme(darkTheme = darkTheme) {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        Column(Modifier.padding(16.dp).width(width), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            TimerBarItem(timer = ringing, now = now, actions = TimerActions())
                            TimerBarItem(timer = running, now = now, actions = TimerActions())
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun inANarrowPaneTheNamesAreWholeAndStopComesFirst() {
        show(SIDE_PANE)
        assertWordsWhole(running.label, ringing.label, "Time is up!")
        assertStopAboveTheName()
    }

    @Test
    fun atFontScale2TheNamesAreWholeAndStopComesFirst() {
        show(SIDE_PANE, fontScale = 2f)
        assertWordsWhole(running.label, ringing.label, "Time is up!")
        assertStopAboveTheName()
    }

    @Test
    fun withRoomTheButtonsStayBesideTheTexts() {
        show(WIDE)
        val stop = composeRule.onNodeWithContentDescription("Stop the alarm of ${ringing.label}").fetchSemanticsNode().boundsInRoot
        val name = placedText(ringing.label).boundsInRoot
        assertTrue("Stop beside the name", stop.left >= name.right - 1f)
        assertWordsWhole(running.label, ringing.label)
    }

    @Test
    fun narrowScreenshot() {
        show(SIDE_PANE)
        composeRule.onRoot().captureRoboImage("$DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH/ui/timer_bar_item_narrow_light.png")
    }

    @Test
    fun narrowFontScale2DarkScreenshot() {
        show(SIDE_PANE, fontScale = 2f, darkTheme = true)
        composeRule.onRoot().captureRoboImage("$DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH/ui/timer_bar_item_narrow_font2_dark.png")
    }

    @Test
    fun narrowAccessible() {
        show(SIDE_PANE, fontScale = 2f)
        composeRule.assertAccessible()
    }

    private fun assertStopAboveTheName() {
        val stop = composeRule.onNodeWithContentDescription("Stop the alarm of ${ringing.label}").fetchSemanticsNode().boundsInRoot
        val name = placedText(ringing.label).boundsInRoot
        assertTrue("Stop (${stop.bottom}) above the name (${name.top})", stop.bottom <= name.top + 1f)
    }

    private fun assertWordsWhole(vararg texts: String) {
        texts.forEach { text ->
            val layout = placedText(text).textLayout()
            val shown = layout.layoutInput.text.text
            (0 until layout.lineCount - 1).forEach { line ->
                val end = layout.getLineEnd(line)
                val before = shown.getOrNull(end - 1)
                assertTrue(
                    "“$shown” broken inside a word after line $line",
                    before == null || before.isWhitespace() || before in "-–/" || shown.getOrNull(end)?.isWhitespace() == true,
                )
            }
        }
    }

    private fun placedText(text: String): SemanticsNode =
        composeRule.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().single { it.layoutInfo.isPlaced }

    private fun SemanticsNode.textLayout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single()
    }

    private companion object {
        /** About cook mode's side column on a 780 dp wide phone, inside its padding. */
        val SIDE_PANE = 240.dp
        val WIDE = 379.dp
    }
}
