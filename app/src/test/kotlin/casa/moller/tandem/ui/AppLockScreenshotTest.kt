package casa.moller.tandem.ui

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.testing.accessibility.assertAccessible
import casa.moller.tandem.lock.AppLockUiState
import com.github.takahirom.roborazzi.DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val PHONE = "w412dp-h915dp-port-xhdpi"
private const val PHONE_LANDSCAPE = "w915dp-h412dp-land-xhdpi"
private const val TABLET = "w1280dp-h800dp-land-hdpi"
private const val TABLET_PORTRAIT = "w800dp-h1280dp-port-hdpi"

/**
 * Fas 5 (las lane, plan §5.5): the lock screen in front of the app (locked, after a failed prompt,
 * without a screen lock) on a phone in both orientations and a tablet in both, light and dark,
 * Swedish and font scale 2. A plain Application keeps Hilt out; the screen is stateless. ATF on
 * every shot.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = PHONE)
class AppLockScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val failed = AppLockUiState(failure = "Too many attempts. Try again later.")
    private val noScreenLock = AppLockUiState(noScreenLock = true)

    @Test
    fun phone() = capture("phone_lock_light")

    @Test
    fun phoneDark() = capture("phone_lock_dark", darkTheme = true)

    @Test
    @Config(application = Application::class, qualifiers = "sv-$PHONE")
    fun phoneSwedish() = capture("phone_lock_light_sv")

    @Test
    fun phoneFontScale2() = capture("phone_lock_font2_light", fontScale = 2f)

    @Test
    fun phoneFailed() = capture("phone_lock_failed_light", state = failed)

    @Test
    @Config(application = Application::class, qualifiers = "sv-$PHONE")
    fun phoneFailedWithoutSystemText() = capture("phone_lock_failed_light_sv", state = AppLockUiState(failed = true))

    @Test
    fun phoneNoScreenLock() = capture("phone_lock_no_screen_lock_light", state = noScreenLock)

    @Test
    fun phoneNoScreenLockDark() = capture("phone_lock_no_screen_lock_dark", darkTheme = true, state = noScreenLock)

    @Test
    @Config(application = Application::class, qualifiers = "sv-$PHONE")
    fun phoneNoScreenLockSwedish() = capture("phone_lock_no_screen_lock_light_sv", state = noScreenLock)

    @Test
    fun phoneNoScreenLockFontScale2() = capture("phone_lock_no_screen_lock_font2_light", state = noScreenLock, fontScale = 2f)

    @Test
    @Config(application = Application::class, qualifiers = PHONE_LANDSCAPE)
    fun phoneLandscape() = capture("phone_landscape_lock_light")

    @Test
    @Config(application = Application::class, qualifiers = TABLET)
    fun tablet() = capture("tablet_lock_light")

    @Test
    @Config(application = Application::class, qualifiers = TABLET)
    fun tabletDark() = capture("tablet_lock_dark", darkTheme = true, state = failed)

    @Test
    @Config(application = Application::class, qualifiers = TABLET_PORTRAIT)
    fun tabletPortrait() = capture("tablet_portrait_lock_light", state = noScreenLock)

    private fun capture(name: String, darkTheme: Boolean = false, state: AppLockUiState = AppLockUiState(), fontScale: Float = 1f) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale * density.fontScale)) {
                TandemTheme(darkTheme = darkTheme) {
                    AppLockScreen(state = state, onAction = {})
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.assertAccessible()
        composeRule.onRoot().captureRoboImage("$DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH/app/$name.png")
    }
}
