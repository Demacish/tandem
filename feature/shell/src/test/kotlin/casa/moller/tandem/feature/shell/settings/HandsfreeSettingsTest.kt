package casa.moller.tandem.feature.shell.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.settings.HandsfreeSettings
import casa.moller.tandem.core.testing.accessibility.assertAccessible
import casa.moller.tandem.feature.shell.PHONE
import casa.moller.tandem.feature.shell.TABLET
import com.github.takahirom.roborazzi.DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Fas 5 (integration): handsfree on, with the voice, on Mer → Kokläge och timers. */
internal val SAMPLE_COOKING_HANDSFREE = SAMPLE_COOKING.copy(handsfree = HandsfreeSettings(enabled = true, voice = true))

private const val HANDSFREE = "Handsfree in cook mode"
private const val VOICE = "Voice commands"
private const val READ = "Read every step aloud"

/**
 * Fas 5 (integration), plan §8.1 #32: cook mode's handsfree on Mer → Kokläge och timers, the same
 * setting as the Handsfree sheet in cook mode: screenshots on a phone (light, dark Swedish, font
 * scale 2) and a tablet, ATF on each.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class HandsfreeSettingsScreenshotTest(private val device: String) {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun handsfree() = page("light") { FixedSettingsPages(cooking = SAMPLE_COOKING_HANDSFREE).Cooking({}, {}, {}) }

    @Test
    @Config(qualifiers = "+sv")
    fun handsfreeDarkSwedish() = page("dark_sv", darkTheme = true, title = "Handsfree i kokläget") {
        FixedSettingsPages(cooking = SAMPLE_COOKING_HANDSFREE).Cooking({}, {}, {})
    }

    @Test
    fun handsfreeOffFontScale2() = page("off_font_scale_2_light", fontScale = 2f) { FixedSettingsPages().Cooking({}, {}, {}) }

    private fun page(
        name: String,
        darkTheme: Boolean = false,
        fontScale: Float = 1f,
        title: String = HANDSFREE,
        content: @Composable () -> Unit,
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale * density.fontScale)) {
                TandemTheme(darkTheme = darkTheme, content = content)
            }
        }
        // The group is below the timers: scroll so the screenshot shows it.
        composeRule.onNodeWithText(title).performScrollTo()
        composeRule.waitForIdle()
        composeRule.assertAccessible()
        composeRule.onRoot().captureRoboImage("$DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH/shell/handsfree_settings_${device}_$name.png")
    }
}

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = PHONE)
class HandsfreeSettingsPhoneScreenshotTest : HandsfreeSettingsScreenshotTest("phone")

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = TABLET)
class HandsfreeSettingsTabletScreenshotTest : HandsfreeSettingsScreenshotTest("tablet")

/** Fas 5 (integration): what the handsfree switches do. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE_TALL)
class HandsfreeSettingsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun show(state: CookingUiState): FixedSettingsPages {
        val pages = FixedSettingsPages(cooking = state)
        composeRule.setContent { TandemTheme { pages.Cooking({}, {}, {}) } }
        return pages
    }

    @Test
    fun offTheVoiceAndReadingWaitForTheMainSwitch() {
        val pages = show(SAMPLE_COOKING)
        composeRule.onNodeWithText(VOICE).performScrollTo()
        composeRule.onNodeWithText(READ).performScrollTo()
        val main = composeRule.onNodeWithText(HANDSFREE).performScrollTo()
        main.assertIsOff()
        main.performClick()
        assertEquals(listOf<SettingsAction>(SettingsAction.SetHandsfree(HandsfreeSettings(enabled = true))), pages.actions)
    }

    @Test
    fun onEachSwitchChangesOnlyItsOwnPart() {
        val pages = show(SAMPLE_COOKING_HANDSFREE)
        composeRule.onNodeWithText(HANDSFREE).performScrollTo().assertIsOn()
        composeRule.onNodeWithText(VOICE).performScrollTo().assertIsOn().assertIsEnabled()
        composeRule.onNodeWithText(READ).performScrollTo().assertIsOff().assertIsEnabled().performClick()
        composeRule.onNodeWithText(VOICE).performScrollTo().performClick()
        assertEquals(
            listOf<SettingsAction>(
                SettingsAction.SetHandsfree(HandsfreeSettings(enabled = true, voice = true, readSteps = true)),
                SettingsAction.SetHandsfree(HandsfreeSettings(enabled = true, voice = false)),
            ),
            pages.actions,
        )
    }

    @Test
    fun offTheOtherSwitchesCannotBeChanged() {
        show(SAMPLE_COOKING)
        composeRule.onNodeWithText(VOICE).performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithText(READ).performScrollTo().assertIsNotEnabled()
    }
}
