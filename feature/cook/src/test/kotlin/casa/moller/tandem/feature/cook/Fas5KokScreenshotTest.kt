package casa.moller.tandem.feature.cook

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.model.settings.HandsfreeSettings
import casa.moller.tandem.core.testing.data.SampleRecipes
import casa.moller.tandem.feature.cook.handsfree.HandsfreeSheetContent
import casa.moller.tandem.feature.cook.handsfree.HandsfreeUiState
import casa.moller.tandem.feature.cook.handsfree.VoiceStatus
import casa.moller.tandem.feature.cook.kitchen.KitchenLayout
import casa.moller.tandem.feature.cook.kitchen.KitchenSamples
import casa.moller.tandem.feature.cook.kitchen.KitchenScreen
import casa.moller.tandem.feature.cook.kitchen.KitchenUiState
import casa.moller.tandem.feature.cook.navigation.CookKey
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Fas 5 (kok lane, 0.7.0): the new pictures of cook mode and the kitchen display. Handsfree's bar
 * (listening, the volume keys only, no on-device recognition, the microphone missing) and its
 * sheet, the tin size in cook mode, and the kitchen display with meals and timers; phone light,
 * dark, Swedish and font scale 2, phone in landscape, tablet in both orientations
 * (build/outputs/roborazzi/cook/, prefix `fas5_`).
 */
internal object Fas5Kok {
    val listening = HandsfreeUiState(
        settings = HandsfreeSettings(enabled = true, voice = true),
        voice = VoiceStatus.LISTENING,
        languageTag = "sv-SE",
    )
    val keysOnly = HandsfreeUiState(settings = HandsfreeSettings(enabled = true), languageTag = "sv-SE")
    val unavailable = listening.copy(voice = VoiceStatus.UNAVAILABLE)
    val needsMicrophone = listening.copy(voice = VoiceStatus.NEEDS_PERMISSION)
    val sheet = HandsfreeUiState(settings = HandsfreeSettings(enabled = true, voice = true, readSteps = false), sheetOpen = true)

    /** The pasta in a 24 cm tin, baked in a 20 cm one. */
    fun tin(): CookFakes = CookFakes().withTin().also { runBlocking { it.pans.setPanSize(SampleRecipes.MushroomPasta.id, 20) } }

    @Composable
    fun Kitchen(state: KitchenUiState, layout: KitchenLayout? = null) {
        KitchenScreen(state = state, onAction = {}, onClose = {}, onStartCooking = { _, _ -> }, onOpenTimer = {}, layout = layout)
    }
}

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = PHONE)
class Fas5KokPhoneScreenshotTest : CookScreenshotTest("phone") {

    @Test
    fun handsfreeListening() = capture("fas5_handsfree_listening_light") { CookWithFakes(fakes = it, handsfree = Fas5Kok.listening) }

    @Test
    fun handsfreeListeningDark() = capture("fas5_handsfree_listening_dark", darkTheme = true) {
        CookWithFakes(fakes = it, handsfree = Fas5Kok.listening)
    }

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun handsfreeListeningSwedish() = capture("fas5_handsfree_listening_light_sv") {
        CookWithFakes(fakes = it, handsfree = Fas5Kok.listening)
    }

    @Test
    fun handsfreeFontScale2() = capture("fas5_handsfree_listening_font2_light", fontScale = 2f) {
        CookWithFakes(fakes = it, handsfree = Fas5Kok.listening)
    }

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun handsfreeKeysOnlySwedish() = capture("fas5_handsfree_keys_light_sv") { CookWithFakes(fakes = it, handsfree = Fas5Kok.keysOnly) }

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun handsfreeUnavailableSwedish() = capture("fas5_handsfree_unavailable_light_sv") {
        CookWithFakes(fakes = it, handsfree = Fas5Kok.unavailable)
    }

    @Test
    fun handsfreeNeedsMicrophoneDark() = capture("fas5_handsfree_microphone_dark", darkTheme = true) {
        CookWithFakes(fakes = it, handsfree = Fas5Kok.needsMicrophone)
    }

    @Test
    fun handsfreeSheet() = capture("fas5_handsfree_sheet_light") { Surface { HandsfreeSheetContent(state = Fas5Kok.sheet, onAction = {}) } }

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun handsfreeSheetDarkSwedish() = capture("fas5_handsfree_sheet_dark_sv", darkTheme = true) {
        Surface { HandsfreeSheetContent(state = Fas5Kok.sheet, onAction = {}) }
    }

    @Test
    fun handsfreeSheetFontScale2() = capture("fas5_handsfree_sheet_font2_light", fontScale = 2f) {
        Surface { HandsfreeSheetContent(state = Fas5Kok.sheet, onAction = {}) }
    }

    @Test
    fun kitchen() = capture("fas5_kitchen") { Fas5Kok.Kitchen(KitchenSamples.withTimers(it.clock)) }

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun kitchenSwedish() = capture("fas5_kitchen_sv") { Fas5Kok.Kitchen(KitchenSamples.withTimers(it.clock, ringing = true)) }

    @Test
    fun kitchenFontScale2() = capture("fas5_kitchen_font2", fontScale = 2f) { Fas5Kok.Kitchen(KitchenSamples.withTimers(it.clock)) }

    @Test
    @Config(qualifiers = "sv-$PHONE")
    fun kitchenEmptySwedish() = capture("fas5_kitchen_empty_sv") { Fas5Kok.Kitchen(KitchenUiState(loading = false)) }
}

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = PHONE_TALL)
class Fas5KokTallPhoneScreenshotTest : CookScreenshotTest("phone_tall") {

    @Test
    fun tinScaled() = capture("fas5_tin_light", fakes = Fas5Kok.tin()) { CookWithFakes(fakes = it) }

    @Test
    @Config(qualifiers = "sv-$PHONE_TALL")
    fun tinScaledDarkSwedish() = capture("fas5_tin_dark_sv", darkTheme = true, fakes = Fas5Kok.tin()) { CookWithFakes(fakes = it) }
}

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = PHONE_LANDSCAPE)
class Fas5KokLandscapeScreenshotTest : CookScreenshotTest("phone_landscape") {

    @Test
    fun handsfree() = capture("fas5_handsfree_listening_light") { CookWithFakes(fakes = it, handsfree = Fas5Kok.listening) }

    @Test
    fun kitchen() = capture("fas5_kitchen") { Fas5Kok.Kitchen(KitchenSamples.withTimers(it.clock)) }
}

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = TABLET)
class Fas5KokTabletScreenshotTest : CookScreenshotTest("tablet") {

    @Test
    fun handsfree() = capture("fas5_handsfree_listening_light", fakes = Fas5Kok.tin()) {
        CookWithFakes(fakes = it, handsfree = Fas5Kok.listening)
    }

    @Test
    @Config(qualifiers = "sv-$TABLET")
    fun handsfreeDarkSwedish() = capture("fas5_handsfree_keys_dark_sv", darkTheme = true) {
        CookWithFakes(key = CookKey(SampleRecipes.MushroomPasta.id, startStep = 1), fakes = it, handsfree = Fas5Kok.keysOnly)
    }

    @Test
    fun kitchen() = capture("fas5_kitchen", fakes = CookFakes()) { Fas5Kok.Kitchen(KitchenSamples.withTimers(it.clock, ringing = true)) }

    @Test
    @Config(qualifiers = "sv-$TABLET")
    fun kitchenSwedish() = capture("fas5_kitchen_sv") { Fas5Kok.Kitchen(KitchenSamples.withTimers(it.clock)) }

    @Test
    fun kitchenFontScale2() = capture("fas5_kitchen_font2", fontScale = 2f) { Fas5Kok.Kitchen(KitchenSamples.withTimers(it.clock)) }

    @Test
    fun kitchenNoTimers() = capture("fas5_kitchen_no_timers") { Fas5Kok.Kitchen(KitchenSamples.meals) }
}

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = TABLET_PORTRAIT)
class Fas5KokTabletPortraitScreenshotTest : CookScreenshotTest("tablet_portrait") {

    @Test
    fun handsfree() = capture("fas5_handsfree_listening_light") { CookWithFakes(fakes = it, handsfree = Fas5Kok.listening) }

    @Test
    fun kitchen() = capture("fas5_kitchen") { Fas5Kok.Kitchen(KitchenSamples.withTimers(it.clock)) }

    @Test
    fun kitchenTwoColumns() = capture("fas5_kitchen_two_columns") {
        Fas5Kok.Kitchen(KitchenSamples.withTimers(it.clock), layout = KitchenLayout.TWO_COLUMNS)
    }
}
