package casa.moller.tandem.feature.cook

import androidx.compose.material3.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.testing.data.SampleRecipes
import casa.moller.tandem.feature.cook.navigation.CookKey
import casa.moller.tandem.feature.cook.session.CookPickerState
import casa.moller.tandem.feature.cook.session.CookRecipePickerContent
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Fas 4 (two recipes at once, plan 8.1): cook mode with the pasta and the buns as tabs, their
 * timers shared (each labelled with its recipe), a ringing timer in the other tab, and the picker
 * "Laga ett recept till". Phone light, dark, Swedish and font scale 2, phone in landscape (also at
 * font scale 2) and split screen, where the tabs sit in the header, and the tablet in both
 * orientations (tabs on every width, see docs/BYGGE.md "Två recept samtidigt").
 */
internal object TwoRecipes {
    val pasta = SampleRecipes.MushroomPasta
    val buns = SampleRecipes.CinnamonBuns

    /** The buns shown at their second step, the pasta (other tab) at its second. */
    val key = CookKey(buns.id, startStep = 1, alongsideRecipeId = pasta.id, alongsideStep = 1)

    /** A pasta timer counting down and a buns timer counting down. */
    fun timers(): CookFakes = CookFakes().apply {
        startTimerOf(pasta.id, pasta.name, 1, "Pastan · 8–10 minuter (10 min)", 10.minutes)
        startTimerOf(buns.id, buns.name, 1, "Jäsning · 1 timme", 60.minutes)
        clock.advanceBy(2.minutes + 21.seconds)
    }

    /** The pasta's timer rings while the buns are shown. */
    fun ringing(): CookFakes = CookFakes().apply {
        startTimerOf(pasta.id, pasta.name, 0, "Svampen · ca 5 minuter", 5.minutes)
        startTimerOf(buns.id, buns.name, 1, "Jäsning · 1 timme", 60.minutes)
        timers.advanceTimeBy(5.minutes)
    }

    /** The picker over the stored sample recipes, the pasta (open) left out. */
    val picker = CookPickerState(
        open = true,
        loading = false,
        results = SampleRecipes.overviews.filter { it.id != pasta.id },
    )
}

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = PHONE)
class CookTwoRecipesPhoneScreenshotTest : CookScreenshotTest("phone") {

    @Test
    fun two() = capture("two_light", fakes = TwoRecipes.timers()) { CookSessionWithFakes(fakes = it, sessionKey = TwoRecipes.key) }

    @Test
    fun twoDark() = capture("two_dark", darkTheme = true, fakes = TwoRecipes.timers()) {
        CookSessionWithFakes(fakes = it, sessionKey = TwoRecipes.key)
    }

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun twoSwedish() = capture("two_light_sv", fakes = TwoRecipes.timers()) {
        CookSessionWithFakes(fakes = it, sessionKey = TwoRecipes.key)
    }

    @Test
    fun twoFontScale2() = capture("two_font2_light", fontScale = 2f, fakes = TwoRecipes.timers()) {
        CookSessionWithFakes(fakes = it, sessionKey = TwoRecipes.key)
    }

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun ringingInTheOtherTabDarkSwedish() = capture("two_ringing_dark_sv", darkTheme = true, fakes = TwoRecipes.ringing()) {
        CookSessionWithFakes(fakes = it, sessionKey = TwoRecipes.key)
    }

    @Test
    fun picker() = capture("picker_light") { Surface { CookRecipePickerContent(state = TwoRecipes.picker, onAction = {}) } }

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun pickerSwedish() = capture("picker_light_sv") { Surface { CookRecipePickerContent(state = TwoRecipes.picker, onAction = {}) } }

    @Test
    fun pickerDark() = capture("picker_dark", darkTheme = true) {
        Surface { CookRecipePickerContent(state = TwoRecipes.picker, onAction = {}) }
    }

    @Test
    fun pickerFontScale2() = capture("picker_font2_light", fontScale = 2f) {
        Surface { CookRecipePickerContent(state = TwoRecipes.picker, onAction = {}) }
    }

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun pickerNoMatchesSwedish() = capture("picker_no_matches_light_sv") {
        Surface { CookRecipePickerContent(state = TwoRecipes.picker.copy(query = "ölkorv", results = emptyList()), onAction = {}) }
    }
}

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = PHONE_LANDSCAPE)
class CookTwoRecipesLandscapeScreenshotTest : CookScreenshotTest("phone_landscape") {

    @Test
    fun two() = capture("two_light", fakes = TwoRecipes.timers()) { CookSessionWithFakes(fakes = it, sessionKey = TwoRecipes.key) }

    @Test
    fun twoFontScale2() = capture("two_font2_light", fontScale = 2f, fakes = TwoRecipes.timers()) {
        CookSessionWithFakes(fakes = it, sessionKey = TwoRecipes.key)
    }
}

/** Split screen: the short window with both recipes' timers, the worst case for the step's room. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = PHONE_SHORT)
class CookTwoRecipesShortPhoneScreenshotTest : CookScreenshotTest("phone_short") {

    @Test
    fun two() = capture("two_light", fakes = TwoRecipes.timers()) { CookSessionWithFakes(fakes = it, sessionKey = TwoRecipes.key) }
}

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = TABLET)
class CookTwoRecipesTabletScreenshotTest : CookScreenshotTest("tablet") {

    @Test
    fun two() = capture("two_light", fakes = TwoRecipes.timers()) { CookSessionWithFakes(fakes = it, sessionKey = TwoRecipes.key) }

    @Test
    @Config(qualifiers = "sv-$TABLET")
    fun ringingDarkSwedish() = capture("two_ringing_dark_sv", darkTheme = true, fakes = TwoRecipes.ringing()) {
        CookSessionWithFakes(fakes = it, sessionKey = TwoRecipes.key)
    }

    @Test
    fun twoFontScale2() = capture("two_font2_light", fontScale = 2f, fakes = TwoRecipes.timers()) {
        CookSessionWithFakes(fakes = it, sessionKey = TwoRecipes.key)
    }
}

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = TABLET_PORTRAIT)
class CookTwoRecipesTabletPortraitScreenshotTest : CookScreenshotTest("tablet_portrait") {

    @Test
    fun two() = capture("two_light", fakes = TwoRecipes.timers()) { CookSessionWithFakes(fakes = it, sessionKey = TwoRecipes.key) }

    @Test
    fun picker() = capture("picker_light") { Surface { CookRecipePickerContent(state = TwoRecipes.picker, onAction = {}) } }
}
