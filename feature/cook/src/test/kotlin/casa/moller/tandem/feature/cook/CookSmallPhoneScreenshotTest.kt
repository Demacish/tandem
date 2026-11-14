package casa.moller.tandem.feature.cook

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.model.settings.HandsfreeSettings
import casa.moller.tandem.core.model.timer.TimerCapabilities
import casa.moller.tandem.core.testing.accessibility.assertAccessible
import casa.moller.tandem.core.testing.data.SampleRecipes
import casa.moller.tandem.feature.cook.handsfree.HandsfreeUiState
import casa.moller.tandem.feature.cook.navigation.CookKey
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Device test 2026-11-14 (B6, B10): the pictures of cook mode on the tested 360 × 780 dp phone,
 * prefix `devicetest_`. The page buttons in Swedish with and without handsfree and at font scale 2
 * ("Föregående" whole, or only the arrow), the ringing timer above the folded warning (light,
 * dark, Swedish, font scale 2, two recipes with handsfree), a finished timer after the running
 * ones, and the same ringing state on the phone in landscape and the tablet in both orientations.
 */
internal object DeviceTest {
    val keysOnly = HandsfreeUiState(settings = HandsfreeSettings(enabled = true), languageTag = "sv-SE")
    val pasta = SampleRecipes.MushroomPasta

    /** The mushrooms ring, the pasta runs, and the alarm channel is muted (a warning). */
    fun ringingWithWarning(): CookFakes =
        CookFakes().apply { timers.setCapabilities(TimerCapabilities.AllGranted.copy(alarmChannelEnabled = false)) }.withRingingTimer()

    /** Two recipes, the pasta's timer ringing, the channel muted. */
    fun twoRingingWithWarning(): CookFakes =
        TwoRecipes.ringing().apply { timers.setCapabilities(TimerCapabilities.AllGranted.copy(alarmChannelEnabled = false)) }

    /** An older timer that finished, then two running. */
    fun finishedAndRunning(): CookFakes = CookFakes().apply {
        startTimer(0, "Svampen · ca 5 minuter", 5.minutes)
        timers.advanceTimeBy(5.minutes)
        runBlocking { timers.stopRinging(timers.timers.value.single().id) }
        startTimer(1, "Pastan · 8–10 minuter (10 min)", 10.minutes)
        clock.advanceBy(1.seconds)
        startTimer(1, "Pastavattnet · 2 min", 2.minutes)
        clock.advanceBy(30.seconds)
    }

    @Composable
    fun Step2(fakes: CookFakes, handsfree: HandsfreeUiState? = keysOnly) {
        CookWithFakes(key = CookKey(pasta.id, startStep = 1), fakes = fakes, handsfree = handsfree)
    }
}

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = PHONE_SMALL)
class CookSmallPhoneScreenshotTest : CookScreenshotTest("small") {

    @Test
    @Config(qualifiers = "sv-$PHONE_SMALL")
    fun buttonsWithHandsfreeSwedish() = capture("devicetest_buttons_handsfree_light_sv") { DeviceTest.Step2(it) }

    @Test
    @Config(qualifiers = "sv-$PHONE_SMALL")
    fun buttonsSwedish() = capture("devicetest_buttons_light_sv") { DeviceTest.Step2(it, handsfree = null) }

    @Test
    @Config(qualifiers = "sv-$PHONE_SMALL")
    fun buttonsWithHandsfreeFontScale2Swedish() = capture("devicetest_buttons_handsfree_font2_light_sv", fontScale = 2f) {
        DeviceTest.Step2(it)
    }

    @Test
    fun buttonsWithHandsfreeDark() = capture("devicetest_buttons_handsfree_dark", darkTheme = true) { DeviceTest.Step2(it) }

    @Test
    @Config(qualifiers = "sv-$PHONE_SMALL")
    fun ringingWithWarningSwedish() = capture("devicetest_ringing_warning_light_sv", fakes = DeviceTest.ringingWithWarning()) {
        CookWithFakes(fakes = it, handsfree = DeviceTest.keysOnly)
    }

    @Test
    fun ringingWithWarningDark() = capture("devicetest_ringing_warning_dark", darkTheme = true, fakes = DeviceTest.ringingWithWarning()) {
        CookWithFakes(fakes = it)
    }

    @Test
    @Config(qualifiers = "sv-$PHONE_SMALL")
    fun ringingWithWarningFontScale2Swedish() = capture(
        "devicetest_ringing_warning_font2_light_sv",
        fontScale = 2f,
        fakes = DeviceTest.ringingWithWarning(),
    ) { CookWithFakes(fakes = it, handsfree = DeviceTest.keysOnly) }

    @Test
    @Config(qualifiers = "sv-$PHONE_SMALL")
    fun twoRecipesRingingWithHandsfreeSwedish() = capture(
        "devicetest_two_ringing_warning_handsfree_light_sv",
        fakes = DeviceTest.twoRingingWithWarning(),
    ) { CookSessionWithFakes(fakes = it, sessionKey = TwoRecipes.key, handsfree = DeviceTest.keysOnly) }

    @Test
    fun twoRecipesRingingWithHandsfreeFontScale2() = capture(
        "devicetest_two_ringing_warning_handsfree_font2_light",
        fontScale = 2f,
        fakes = DeviceTest.twoRingingWithWarning(),
    ) { CookSessionWithFakes(fakes = it, sessionKey = TwoRecipes.key, handsfree = DeviceTest.keysOnly) }

    @Test
    @Config(qualifiers = "sv-$PHONE_SMALL")
    fun finishedAfterRunningSwedish() = capture("devicetest_finished_last_light_sv", fakes = DeviceTest.finishedAndRunning()) {
        DeviceTest.Step2(it, handsfree = null)
    }
}

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = PHONE_SMALL_LANDSCAPE)
class CookSmallLandscapeScreenshotTest : CookScreenshotTest("small_landscape") {

    @Test
    @Config(qualifiers = "sv-$PHONE_SMALL_LANDSCAPE")
    fun ringingWithWarningSwedish() = capture("devicetest_ringing_warning_light_sv", fakes = DeviceTest.ringingWithWarning()) {
        CookWithFakes(fakes = it, handsfree = DeviceTest.keysOnly)
    }

    @Test
    @Config(qualifiers = "sv-$PHONE_SMALL_LANDSCAPE")
    fun buttonsWithHandsfreeFontScale2Swedish() = capture("devicetest_buttons_handsfree_font2_light_sv", fontScale = 2f) {
        DeviceTest.Step2(it)
    }
}

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = TABLET)
class CookDeviceTestTabletScreenshotTest : CookScreenshotTest("tablet") {

    @Test
    @Config(qualifiers = "sv-$TABLET")
    fun ringingWithWarningSwedish() = capture("devicetest_ringing_warning_light_sv", fakes = DeviceTest.ringingWithWarning()) {
        CookWithFakes(fakes = it, handsfree = DeviceTest.keysOnly)
    }
}

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = TABLET_PORTRAIT)
class CookDeviceTestTabletPortraitScreenshotTest : CookScreenshotTest("tablet_portrait") {

    @Test
    fun ringingWithWarningDark() = capture("devicetest_ringing_warning_dark", darkTheme = true, fakes = DeviceTest.ringingWithWarning()) {
        CookWithFakes(fakes = it, handsfree = DeviceTest.keysOnly)
    }
}

/** ATF (plan 5.6) for the same states: the folded warning, the arrow-only button, the small phone. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE_SMALL)
class CookSmallPhoneAccessibilityTest {

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
    @Config(qualifiers = "sv-$PHONE_SMALL")
    fun buttonsWithHandsfreeSwedish() = check { DeviceTest.Step2(it) }

    @Test
    @Config(qualifiers = "sv-$PHONE_SMALL")
    fun buttonsWithHandsfreeFontScale2Swedish() = check(fontScale = 2f) { DeviceTest.Step2(it) }

    @Test
    fun ringingWithWarning() = check(fakes = DeviceTest.ringingWithWarning()) { CookWithFakes(fakes = it, handsfree = DeviceTest.keysOnly) }

    @Test
    fun ringingWithWarningDark() = check(darkTheme = true, fakes = DeviceTest.ringingWithWarning()) { CookWithFakes(fakes = it) }

    @Test
    fun ringingWithWarningFontScale2() = check(fontScale = 2f, fakes = DeviceTest.ringingWithWarning()) {
        CookWithFakes(fakes = it, handsfree = DeviceTest.keysOnly)
    }

    @Test
    @Config(qualifiers = "sv-$PHONE_SMALL")
    fun twoRecipesRingingWithHandsfreeSwedish() = check(fakes = DeviceTest.twoRingingWithWarning()) {
        CookSessionWithFakes(fakes = it, sessionKey = TwoRecipes.key, handsfree = DeviceTest.keysOnly)
    }

    @Test
    fun finishedAfterRunning() = check(fakes = DeviceTest.finishedAndRunning()) { DeviceTest.Step2(it, handsfree = null) }

    @Test
    @Config(qualifiers = PHONE_SMALL_LANDSCAPE)
    fun landscapeRingingWithWarning() = check(fakes = DeviceTest.ringingWithWarning()) {
        CookWithFakes(fakes = it, handsfree = DeviceTest.keysOnly)
    }

    @Test
    @Config(qualifiers = TABLET)
    fun tabletRingingWithWarningDark() = check(darkTheme = true, fakes = DeviceTest.ringingWithWarning()) {
        CookWithFakes(fakes = it, handsfree = DeviceTest.keysOnly)
    }
}
