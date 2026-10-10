package casa.moller.tandem.feature.home

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import com.github.takahirom.roborazzi.DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "sv-w412dp-h915dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TodayRouteTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun placeholderIsTranslated() {
        composeRule.setContent {
            TandemTheme {
                TodayRoute(onOpenRecipe = {}, onOpenRecipes = {}, onOpenWeek = {})
            }
        }
        val title = ApplicationProvider.getApplicationContext<android.content.Context>().getString(R.string.home_title)
        composeRule.onNodeWithText(title).assertExists()
        composeRule.onRoot().captureRoboImage("$DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH/home/phone_today_placeholder_light_sv.png")
    }
}
