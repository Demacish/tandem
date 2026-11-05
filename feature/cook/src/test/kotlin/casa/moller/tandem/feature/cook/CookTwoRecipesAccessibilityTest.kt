package casa.moller.tandem.feature.cook

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.testing.accessibility.assertAccessible
import casa.moller.tandem.feature.cook.session.CookRecipePickerContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * ATF checks (plan 5.6) of two recipes at once (Fas 4): the tabs with both recipes' timers, a
 * ringing timer in the other tab, font scale 2, the tablet, and the picker; light and dark.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE)
class CookTwoRecipesAccessibilityTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun check(
        darkTheme: Boolean = false,
        fontScale: Float? = null,
        fakes: CookFakes = CookFakes(),
        content: @Composable (CookFakes) -> Unit,
    ) {
        composeRule.setContent { TestFrame(darkTheme = darkTheme, fontScale = fontScale, clock = fakes.clock) { content(fakes) } }
        composeRule.waitForIdle()
        composeRule.assertAccessible()
    }

    @Test
    fun two() = check(fakes = TwoRecipes.timers()) { CookSessionWithFakes(fakes = it, sessionKey = TwoRecipes.key) }

    @Test
    fun twoDark() = check(darkTheme = true, fakes = TwoRecipes.timers()) { CookSessionWithFakes(fakes = it, sessionKey = TwoRecipes.key) }

    @Test
    fun twoFontScale2() = check(fontScale = 2f, fakes = TwoRecipes.timers()) {
        CookSessionWithFakes(fakes = it, sessionKey = TwoRecipes.key)
    }

    @Test
    fun ringingInTheOtherTab() = check(fakes = TwoRecipes.ringing()) { CookSessionWithFakes(fakes = it, sessionKey = TwoRecipes.key) }

    @Test
    @Config(qualifiers = TABLET)
    fun twoOnATablet() = check(fakes = TwoRecipes.timers()) { CookSessionWithFakes(fakes = it, sessionKey = TwoRecipes.key) }

    @Test
    fun picker() = check { Surface { CookRecipePickerContent(state = TwoRecipes.picker, onAction = {}) } }

    @Test
    fun pickerDarkFontScale2() = check(darkTheme = true, fontScale = 2f) {
        Surface { CookRecipePickerContent(state = TwoRecipes.picker, onAction = {}) }
    }
}
