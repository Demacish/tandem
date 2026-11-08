package casa.moller.tandem.feature.cook

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.testing.accessibility.assertAccessible
import casa.moller.tandem.feature.cook.handsfree.HandsfreeSheetContent
import casa.moller.tandem.feature.cook.kitchen.KitchenSamples
import casa.moller.tandem.feature.cook.kitchen.KitchenUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * ATF (core:testing `assertAccessible`, plan 5.6) of everything Fas 5's kok lane added: handsfree's
 * bar in every state and its sheet, the tin size in cook mode, and the kitchen display with meals,
 * timers and empty; light and dark, font scale 2.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE)
class Fas5KokAccessibilityTest {

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
    fun handsfreeListening() = check { CookWithFakes(fakes = it, handsfree = Fas5Kok.listening) }

    @Test
    fun handsfreeListeningDark() = check(darkTheme = true) { CookWithFakes(fakes = it, handsfree = Fas5Kok.listening) }

    @Test
    fun handsfreeFontScale2() = check(fontScale = 2f) { CookWithFakes(fakes = it, handsfree = Fas5Kok.listening) }

    @Test
    fun handsfreeKeysOnly() = check { CookWithFakes(fakes = it, handsfree = Fas5Kok.keysOnly) }

    @Test
    fun handsfreeUnavailableDark() = check(darkTheme = true) { CookWithFakes(fakes = it, handsfree = Fas5Kok.unavailable) }

    @Test
    fun handsfreeNeedsMicrophone() = check { CookWithFakes(fakes = it, handsfree = Fas5Kok.needsMicrophone) }

    @Test
    fun handsfreeSheet() = check { Surface { HandsfreeSheetContent(state = Fas5Kok.sheet, onAction = {}) } }

    @Test
    fun handsfreeSheetDarkFontScale2() = check(darkTheme = true, fontScale = 2f) {
        Surface { HandsfreeSheetContent(state = Fas5Kok.sheet, onAction = {}) }
    }

    @Test
    @Config(qualifiers = PHONE_TALL)
    fun tin() = check(fakes = Fas5Kok.tin()) { CookWithFakes(fakes = it) }

    @Test
    @Config(qualifiers = PHONE_TALL)
    fun tinDark() = check(darkTheme = true, fakes = Fas5Kok.tin()) { CookWithFakes(fakes = it) }

    @Test
    fun kitchen() = check { Fas5Kok.Kitchen(KitchenSamples.withTimers(it.clock, ringing = true)) }

    @Test
    fun kitchenFontScale2() = check(fontScale = 2f) { Fas5Kok.Kitchen(KitchenSamples.withTimers(it.clock)) }

    @Test
    fun kitchenEmpty() = check { Fas5Kok.Kitchen(KitchenUiState(loading = false)) }

    @Test
    @Config(qualifiers = TABLET)
    fun kitchenTablet() = check { Fas5Kok.Kitchen(KitchenSamples.withTimers(it.clock)) }

    @Test
    @Config(qualifiers = TABLET)
    fun handsfreeTablet() = check(fakes = Fas5Kok.tin()) { CookWithFakes(fakes = it, handsfree = Fas5Kok.listening) }
}
