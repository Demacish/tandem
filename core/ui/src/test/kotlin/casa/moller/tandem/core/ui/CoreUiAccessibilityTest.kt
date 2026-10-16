package casa.moller.tandem.core.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import casa.moller.tandem.core.testing.accessibility.assertAccessible
import casa.moller.tandem.core.ui.sample.DemoSample
import casa.moller.tandem.core.ui.sample.EmptyStateSample
import casa.moller.tandem.core.ui.sample.ErrorStateSample
import casa.moller.tandem.core.ui.sample.IngredientsSample
import casa.moller.tandem.core.ui.sample.LoadingStateSample
import casa.moller.tandem.core.ui.sample.RecipeCardsSample
import casa.moller.tandem.core.ui.sample.RecipeListItemsSample
import casa.moller.tandem.core.ui.sample.RecipeMetaSample
import casa.moller.tandem.core.ui.sample.SafeModeInfoSample
import casa.moller.tandem.core.ui.sample.ServerTooOldSample
import casa.moller.tandem.core.ui.sample.StatusBannersSample
import casa.moller.tandem.core.ui.sample.StepCardSample
import casa.moller.tandem.core.ui.sample.UiSampleSurface
import com.google.android.apps.common.testing.accessibility.framework.integrations.espresso.AccessibilityViewCheckException
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * ATF accessibility checks (core:testing `assertAccessible`, plan 5.6) of the shared components the
 * Phase 1a screens are built from: recipe cards and rows, the meta row, ingredient rows (with a
 * conversion and a struck row), step cards, the server status banners and notices, and the empty,
 * error and loading states. Light and dark, because contrast is measured on the rendered pixels.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h1600dp-xhdpi")
class CoreUiAccessibilityTest(private val theme: String) {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun recipeCards() = check { RecipeCardsSample() }

    @Test
    fun recipeListItems() = check { RecipeListItemsSample() }

    @Test
    fun recipeMeta() = check { RecipeMetaSample() }

    @Test
    fun ingredients() = check { IngredientsSample() }

    @Test
    fun stepCard() = check { StepCardSample() }

    @Test
    fun statusBanners() = check { StatusBannersSample() }

    @Test
    fun serverTooOld() = check { ServerTooOldSample() }

    @Test
    fun safeModeInfo() = check(fillScreen = true) { SafeModeInfoSample() }

    @Test
    fun emptyState() = check { EmptyStateSample() }

    @Test
    fun errorState() = check { ErrorStateSample() }

    @Test
    fun loadingState() = check { LoadingStateSample() }

    @Test
    fun demo() = check { DemoSample() }

    /**
     * The gate itself: Roborazzi skips ATF silently (only a log line) on API < 34 or with legacy
     * graphics, so a tiny unlabelled button must FAIL here, or every other check proves nothing.
     */
    @Test
    fun theChecksRunAndFail() {
        composeRule.setContent {
            UiSampleSurface(darkTheme = theme == DARK) { Box(Modifier.size(16.dp).clickable {}) }
        }
        assertThrows(AccessibilityViewCheckException::class.java) { composeRule.assertAccessible() }
    }

    private fun check(fillScreen: Boolean = false, sample: @Composable () -> Unit) {
        composeRule.setContent { UiSampleSurface(darkTheme = theme == DARK, fillScreen = fillScreen) { sample() } }
        composeRule.assertAccessible()
    }

    companion object {
        private const val LIGHT = "light"
        private const val DARK = "dark"

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun themes(): List<Array<Any>> = listOf(arrayOf(LIGHT), arrayOf(DARK))
    }
}
