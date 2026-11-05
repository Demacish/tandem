package casa.moller.tandem.feature.home

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.home.HomeLayout
import casa.moller.tandem.core.model.home.HomeSectionType
import casa.moller.tandem.core.testing.accessibility.assertAccessible
import casa.moller.tandem.feature.home.ui.TodayCustomizeContent
import com.github.takahirom.roborazzi.DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val CUSTOMIZE_PHONE = "w412dp-h915dp-port-xhdpi"
private const val CUSTOMIZE_PHONE_LANDSCAPE = "w915dp-h412dp-land-xhdpi"
private const val CUSTOMIZE_TABLET = "w1280dp-h800dp-land-mdpi"
private const val CUSTOMIZE_TABLET_PORTRAIT = "w800dp-h1280dp-port-mdpi"

/**
 * Fas 3's leftovers ("Hemsektioner som användaren konfigurerar"): the "Anpassa Idag" sheet's
 * content (reference images under `src/test/screenshots/home/`, ATF checks): the default layout,
 * a changed one (New first, Not cooked lately hidden), dark, font scale 2, landscape and tablets.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = CUSTOMIZE_PHONE)
class TodayCustomizeScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val changed = HomeLayout()
        .move(HomeSectionType.NEW, -2)
        .withVisible(HomeSectionType.NOT_COOKED_LATELY, visible = false)

    @Test
    @Config(qualifiers = "sv-$CUSTOMIZE_PHONE")
    fun phone() = capture("phone_today_customize_light_sv", HomeLayout())

    @Test
    @Config(qualifiers = "sv-$CUSTOMIZE_PHONE")
    fun phoneChanged() = capture("phone_today_customize_changed_light_sv", changed)

    @Test
    fun phoneDark() = capture("phone_today_customize_dark", changed, darkTheme = true)

    @Test
    @Config(qualifiers = "sv-$CUSTOMIZE_PHONE")
    fun phoneFontScale2() = capture("phone_today_customize_font2_light_sv", changed, fontScale = 2f)

    @Test
    @Config(qualifiers = "sv-$CUSTOMIZE_PHONE_LANDSCAPE")
    fun phoneLandscape() = capture("phone_landscape_today_customize_light_sv", changed)

    @Test
    @Config(qualifiers = "sv-$CUSTOMIZE_TABLET")
    fun tablet() = capture("tablet_today_customize_light_sv", changed)

    @Test
    @Config(qualifiers = "sv-$CUSTOMIZE_TABLET_PORTRAIT")
    fun tabletPortrait() = capture("tablet_portrait_today_customize_dark_sv", changed, darkTheme = true)

    @Test
    fun accessible() = check(changed)

    @Test
    fun accessibleDark() = check(changed, darkTheme = true)

    @Test
    @Config(qualifiers = "sv-$CUSTOMIZE_PHONE")
    fun accessibleFontScale2() = check(changed, fontScale = 2f)

    private fun content(layout: HomeLayout, darkTheme: Boolean, fontScale: Float?) {
        composeRule.setContent {
            WithFontScale(fontScale) {
                TandemTheme(darkTheme = darkTheme) {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxSize()) {
                        TodayCustomizeContent(layout = layout, onAction = {})
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun capture(name: String, layout: HomeLayout, darkTheme: Boolean = false, fontScale: Float? = null) {
        content(layout, darkTheme, fontScale)
        composeRule.onRoot().captureRoboImage("$DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH/home/$name.png")
    }

    private fun check(layout: HomeLayout, darkTheme: Boolean = false, fontScale: Float? = null) {
        content(layout, darkTheme, fontScale)
        composeRule.assertAccessible()
    }

    @Composable
    private fun WithFontScale(fontScale: Float?, content: @Composable () -> Unit) {
        if (fontScale == null) {
            content()
        } else {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale), content = content)
        }
    }
}
