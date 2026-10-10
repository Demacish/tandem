package casa.moller.tandem.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.error.TlsErrorKind
import casa.moller.tandem.core.ui.sample.AdaptiveContentCardSample
import casa.moller.tandem.core.ui.sample.DemoSample
import casa.moller.tandem.core.ui.sample.EmptyStateSample
import casa.moller.tandem.core.ui.sample.ErrorStateSample
import casa.moller.tandem.core.ui.sample.LoadingStateSample
import casa.moller.tandem.core.ui.sample.UI_SAMPLE_ROOT_TAG
import casa.moller.tandem.core.ui.sample.UiSampleSurface
import com.github.takahirom.roborazzi.DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Records screenshots of the app-level components in light and dark at phone width, plus
 * AdaptiveContentCard on a phone and a tablet. Phase 0 only records (see
 * tandem.android.roborazzi); CI uploads build/outputs/roborazzi.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h1600dp-xhdpi")
class CoreUiScreenshotTest(private val theme: String) {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun demoBannerAndStatusChips() = capture("demo_banner_status_chips") { DemoSample() }

    @Test
    fun emptyState() = capture("empty_state") { EmptyStateSample() }

    @Test
    fun errorState() = capture("error_state") { ErrorStateSample() }

    @Test
    fun errorStateWithDetails() {
        set { ErrorStateSample() }
        val showDetails = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getString(R.string.ui_error_show_details)
        composeRule.onNodeWithText(showDetails).performClick()
        composeRule.waitForIdle()
        save("error_state_details")
    }

    @Test
    fun errorStateFromTandemError() = capture("error_state_untrusted_certificate") {
        ErrorState(error = TandemError.TlsError(TlsErrorKind.UNTRUSTED_CERTIFICATE), onRetry = {})
    }

    @Test
    fun loadingState() = capture("loading_state") { LoadingStateSample() }

    @Test
    fun crashReportCard() = capture("crash_report_card") {
        CrashReportCard(onShow = {}, onCopy = {}, onShare = {}, onDelete = {})
    }

    @Test
    @Config(qualifiers = RobolectricDeviceQualifiers.Pixel7)
    fun adaptiveContentCardPhone() = capture("adaptive_content_card_phone", fillScreen = true) {
        AdaptiveContentCardSample()
    }

    @Test
    @Config(qualifiers = RobolectricDeviceQualifiers.MediumTablet)
    fun adaptiveContentCardTablet() = capture("adaptive_content_card_tablet", fillScreen = true) {
        AdaptiveContentCardSample()
    }

    private fun capture(name: String, fillScreen: Boolean = false, sample: @Composable () -> Unit) {
        set(fillScreen, sample)
        save(name)
    }

    private fun set(fillScreen: Boolean = false, sample: @Composable () -> Unit) {
        composeRule.setContent {
            UiSampleSurface(darkTheme = theme == DARK, fillScreen = fillScreen) { sample() }
        }
    }

    private fun save(name: String) {
        composeRule.onNodeWithTag(UI_SAMPLE_ROOT_TAG)
            .captureRoboImage("$DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH/ui/${name}_$theme.png")
    }

    companion object {
        private const val LIGHT = "light"
        private const val DARK = "dark"

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun themes(): List<Array<Any>> = listOf(arrayOf(LIGHT), arrayOf(DARK))
    }
}
