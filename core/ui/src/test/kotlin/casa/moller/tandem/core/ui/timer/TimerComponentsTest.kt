package casa.moller.tandem.core.ui.timer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.timer.CookTimer
import casa.moller.tandem.core.testing.accessibility.assertAccessible
import com.github.takahirom.roborazzi.DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH
import com.github.takahirom.roborazzi.captureRoboImage
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The timer components (TimerStartButton, TimerBar, TimerCard) in every state, light and dark:
 * screenshots (build/outputs/roborazzi/ui/timer_*), ATF checks and the buttons' actions.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h1200dp-xhdpi")
class TimerComponentsTest(private val theme: String) {

    @get:Rule
    val composeRule = createComposeRule()

    private val start = Instant.parse("2000-01-15T18:00:00Z")
    private val now = start + 2.minutes + 21.seconds

    private val running = CookTimer.start(1, "Pastan · 10 min", 10.minutes, start)
    private val paused = CookTimer.start(2, "Svampen · ca 5 minuter", 5.minutes, start).pause(start + 1.minutes)
    private val ringing = CookTimer.start(3, "Ugnen", 2.minutes, start).ring(now)
    private val finished = CookTimer.start(4, "Riset", 1.minutes, start).ring(now).stopRinging()
    private val all = listOf(running, paused, ringing, finished)

    private fun show(content: @Composable () -> Unit) {
        composeRule.setContent {
            TandemTheme(darkTheme = theme == "dark") {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) { content() }
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Composable
    private fun Everything(actions: TimerActions = TimerActions()) {
        TimerStartButton(durationText = timerDurationText(25.minutes), detail = "ca 20–25 minuter (25 min)", onClick = {})
        TimerStartButton(durationText = timerDurationText(90.seconds), onClick = {})
        TimerBar(timers = all, now = now, actions = actions, maxHeight = 600.dp)
        // Other timers than the bar's: the same timer twice would be the same TalkBack labels twice.
        TimerCard(timer = running.copy(id = 5, label = "Såsen"), now = now, actions = actions, overline = "Steg 2")
        TimerCard(timer = ringing.copy(id = 6, label = "Kycklingen"), now = now, actions = actions)
    }

    @Test
    fun screenshot() {
        show { Everything() }
        composeRule.onRoot().captureRoboImage("$DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH/ui/timer_components_$theme.png")
    }

    @Test
    fun accessible() {
        show { Everything() }
        composeRule.assertAccessible()
    }

    @Test
    fun theButtonsReportTheTimer() {
        val calls = mutableListOf<String>()
        val actions = TimerActions(
            onPause = { calls += "pause $it" },
            onResume = { calls += "resume $it" },
            onAddMinute = { calls += "plus $it" },
            onCancel = { calls += "cancel $it" },
            onStopRinging = { calls += "stop $it" },
        )
        show { TimerBar(timers = all, now = now, actions = actions, maxHeight = 600.dp) }

        composeRule.onNodeWithText("7:39").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Pause Pastan · 10 min").performClick()
        composeRule.onNodeWithContentDescription("Resume Svampen · ca 5 minuter").performClick()
        composeRule.onNodeWithContentDescription("Stop the alarm of Ugnen").performClick()
        composeRule.onNodeWithContentDescription("Add one minute to Ugnen").performClick()
        composeRule.onNodeWithContentDescription("Remove Riset").performClick()

        assertEquals(listOf("pause 1", "resume 2", "stop 3", "plus 3", "cancel 4"), calls)
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun themes(): List<Array<Any>> = listOf(arrayOf("light"), arrayOf("dark"))
    }
}
