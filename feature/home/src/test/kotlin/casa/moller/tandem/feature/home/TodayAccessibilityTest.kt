package casa.moller.tandem.feature.home

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.mealplan.UpcomingMeals
import casa.moller.tandem.core.testing.accessibility.assertAccessible
import casa.moller.tandem.core.testing.data.SampleRecipes
import casa.moller.tandem.core.ui.AppStatus
import casa.moller.tandem.core.ui.navigation.AppChrome
import casa.moller.tandem.core.ui.navigation.LocalAppChrome
import casa.moller.tandem.feature.home.ui.TodayScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val PHONE_TALL = "w412dp-h1800dp-port-xhdpi"
private const val TABLET = "w1280dp-h800dp-land-mdpi"

/**
 * ATF accessibility checks (core:testing `assertAccessible`, plan 5.6 and Fas 1 "Klart när") of the
 * Today tab: tonight, the other meals and every carousel (a tall phone, so all of them are on
 * screen), nothing planned, offline, demo, loading, font scale 2 and the tablet's two columns.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE_TALL)
class TodayAccessibilityTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val loaded = sampleHomeState()

    @Test
    fun today() = check(loaded)

    @Test
    fun todayDark() = check(loaded, darkTheme = true)

    @Test
    fun todayFontScale2() = check(loaded, fontScale = 2f)

    @Test
    fun nothingPlanned() = check(sampleHomeState(meals = UpcomingMeals(today = SampleRecipes.Today)).copy(sections = emptyList()))

    @Test
    fun offlineDark() = check(sampleHomeState(offline = true), darkTheme = true, chrome = AppChrome(status = AppStatus.Offline))

    @Test
    fun demo() = check(loaded, chrome = AppChrome(status = AppStatus.Demo, showDemoBanner = true))

    @Test
    fun loading() = check(HomeUiState())

    @Test
    @Config(qualifiers = TABLET)
    fun tablet() = check(loaded)

    @Test
    @Config(qualifiers = TABLET)
    fun tabletDark() = check(loaded, darkTheme = true)

    private fun check(state: HomeUiState, darkTheme: Boolean = false, fontScale: Float = 1f, chrome: AppChrome = AppChrome()) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale * density.fontScale)) {
                TandemTheme(darkTheme = darkTheme) {
                    CompositionLocalProvider(LocalAppChrome provides chrome) {
                        TodayScreen(state = state, onAction = {})
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.assertAccessible()
    }
}
