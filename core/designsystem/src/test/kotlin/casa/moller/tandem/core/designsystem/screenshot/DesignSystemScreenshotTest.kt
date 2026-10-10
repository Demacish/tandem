package casa.moller.tandem.core.designsystem.screenshot

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import casa.moller.tandem.core.designsystem.sample.ButtonsSample
import casa.moller.tandem.core.designsystem.sample.CardsSample
import casa.moller.tandem.core.designsystem.sample.IconsSample
import casa.moller.tandem.core.designsystem.sample.LoadingSample
import casa.moller.tandem.core.designsystem.sample.SAMPLE_ROOT_TAG
import casa.moller.tandem.core.designsystem.sample.SampleSurface
import casa.moller.tandem.core.designsystem.sample.TextFieldsSample
import casa.moller.tandem.core.designsystem.sample.TopAppBarSample
import casa.moller.tandem.core.designsystem.sample.TypographySample
import com.github.takahirom.roborazzi.DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Records screenshots of the design system components in light and dark at phone width
 * (411 dp). Phase 0 only records (see tandem.android.roborazzi); CI uploads the PNGs from
 * build/outputs/roborazzi. The window is extra tall so no sample is cut off.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h1600dp-xhdpi")
class DesignSystemScreenshotTest(private val theme: String) {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun typography() = capture("typography") { TypographySample() }

    @Test
    fun buttons() = capture("buttons") { ButtonsSample() }

    @Test
    fun topAppBar() = capture("top_app_bar") { TopAppBarSample() }

    @Test
    fun textFields() = capture("text_fields") { TextFieldsSample() }

    @Test
    fun cards() = capture("cards_and_chips") { CardsSample() }

    @Test
    fun icons() = capture("icons") { IconsSample() }

    @Test
    fun loading() = capture("loading") { LoadingSample() }

    private fun capture(name: String, sample: @Composable () -> Unit) {
        composeRule.setContent {
            SampleSurface(darkTheme = theme == DARK) { sample() }
        }
        composeRule.onNodeWithTag(SAMPLE_ROOT_TAG)
            .captureRoboImage("$DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH/designsystem/${name}_$theme.png")
    }

    companion object {
        private const val LIGHT = "light"
        private const val DARK = "dark"

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun themes(): List<Array<Any>> = listOf(arrayOf(LIGHT), arrayOf(DARK))
    }
}
