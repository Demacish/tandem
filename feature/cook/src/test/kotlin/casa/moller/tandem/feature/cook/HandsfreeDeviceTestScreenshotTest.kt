package casa.moller.tandem.feature.cook

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.domain.voice.VoiceCommand
import casa.moller.tandem.core.model.settings.HandsfreeSettings
import casa.moller.tandem.core.testing.accessibility.assertAccessible
import casa.moller.tandem.feature.cook.handsfree.HandsfreeBar
import casa.moller.tandem.feature.cook.handsfree.HandsfreeSheetContent
import casa.moller.tandem.feature.cook.handsfree.HandsfreeUiState
import casa.moller.tandem.feature.cook.handsfree.VoiceStatus
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Device test 2026-11-14 (rost lane): the handsfree bar's new states and the sheet's new choices,
 * shown on their own (not over cook mode, whose pictures other changes move): the English
 * commands offered and listening (B3 a), the download to fetch, running and later (B2), no speech
 * service and the speech service in the sheet (B3 b), the silent volume with "Höj volymen" (B11).
 * Phone light, dark, Swedish and font scale 2, phone in landscape (one line), tablet in both
 * orientations (build/outputs/roborazzi/cook/, prefix `rost_`).
 */
internal object Rost {
    private val voiceOn = HandsfreeSettings(enabled = true, voice = true)

    val offerEnglish = HandsfreeUiState(
        settings = voiceOn,
        voice = VoiceStatus.OFFER_ENGLISH,
        languageTag = "sv",
        voiceLanguageTag = "sv-SE",
    )
    val englishListening = offerEnglish.copy(
        settings = voiceOn.copy(englishCommands = true),
        voice = VoiceStatus.LISTENING,
        voiceLanguageTag = "en-US",
        heard = VoiceCommand.NEXT,
    )
    val downloadable = HandsfreeUiState(
        settings = voiceOn,
        voice = VoiceStatus.DOWNLOADABLE,
        languageTag = "en",
        voiceLanguageTag = "en-US",
    )
    val downloadFailed = downloadable.copy(downloadFailed = true)
    val downloading = downloadable.copy(voice = VoiceStatus.DOWNLOADING)
    val downloadLater = downloadable.copy(voice = VoiceStatus.DOWNLOAD_LATER)
    val noSpeechService = offerEnglish.copy(settings = voiceOn.copy(speechService = true), voice = VoiceStatus.NO_SPEECH_SERVICE)
    val silent = HandsfreeUiState(
        settings = HandsfreeSettings(enabled = true, readSteps = true),
        languageTag = "sv",
        volumeSilent = true,
    )
    val sheet = HandsfreeUiState(
        settings = voiceOn.copy(englishCommands = true, readSteps = true),
        languageTag = "sv",
        sheetOpen = true,
    )
    val sheetService = sheet.copy(settings = voiceOn.copy(speechService = true))

    /** The bar as cook mode shows it under its header. */
    @Composable
    fun Bar(state: HandsfreeUiState, compact: Boolean = false) {
        Surface { Column { HandsfreeBar(state = state, onAction = {}, compact = compact) } }
    }

    @Composable
    fun Sheet(state: HandsfreeUiState) {
        Surface { HandsfreeSheetContent(state = state, onAction = {}) }
    }
}

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "sv-$PHONE")
class RostPhoneScreenshotTest : CookScreenshotTest("phone") {

    @Test
    fun offerEnglish() = capture("rost_offer_english_light_sv") { Rost.Bar(Rost.offerEnglish) }

    @Test
    fun offerEnglishDark() = capture("rost_offer_english_dark_sv", darkTheme = true) { Rost.Bar(Rost.offerEnglish) }

    @Test
    fun offerEnglishFontScale2() = capture("rost_offer_english_font2_sv", fontScale = 2f) { Rost.Bar(Rost.offerEnglish) }

    @Test
    fun englishListening() = capture("rost_english_listening_light_sv") { Rost.Bar(Rost.englishListening) }

    @Test
    fun noSpeechService() = capture("rost_no_speech_service_light_sv") { Rost.Bar(Rost.noSpeechService) }

    @Test
    fun silent() = capture("rost_volume_silent_light_sv") { Rost.Bar(Rost.silent) }

    @Test
    fun silentDarkFontScale2() = capture("rost_volume_silent_dark_font2_sv", darkTheme = true, fontScale = 2f) { Rost.Bar(Rost.silent) }

    @Test
    fun sheet() = capture("rost_sheet_light_sv") { Rost.Sheet(Rost.sheet) }

    @Test
    fun sheetServiceFontScale2() = capture("rost_sheet_service_font2_sv", fontScale = 2f) { Rost.Sheet(Rost.sheetService) }
}

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = PHONE)
class RostPhoneEnglishScreenshotTest : CookScreenshotTest("phone") {

    @Test
    fun downloadable() = capture("rost_download_light") { Rost.Bar(Rost.downloadable) }

    @Test
    fun downloadFailedDark() = capture("rost_download_failed_dark", darkTheme = true) { Rost.Bar(Rost.downloadFailed) }

    @Test
    fun downloading() = capture("rost_downloading_light") { Rost.Bar(Rost.downloading) }

    @Test
    fun downloadLater() = capture("rost_download_later_light") { Rost.Bar(Rost.downloadLater) }

    @Test
    fun silent() = capture("rost_volume_silent_light") { Rost.Bar(Rost.silent) }

    @Test
    fun sheetService() = capture("rost_sheet_service_light") { Rost.Sheet(Rost.sheetService.copy(languageTag = "en")) }
}

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "sv-$PHONE_LANDSCAPE")
class RostLandscapeScreenshotTest : CookScreenshotTest("phone_landscape") {

    @Test
    fun offerEnglishCompact() = capture("rost_offer_english_compact_sv") { Rost.Bar(Rost.offerEnglish, compact = true) }

    @Test
    fun silentCompact() = capture("rost_volume_silent_compact_sv") { Rost.Bar(Rost.silent, compact = true) }
}

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "sv-$TABLET")
class RostTabletScreenshotTest : CookScreenshotTest("tablet") {

    @Test
    fun offerEnglish() = capture("rost_offer_english_light_sv") { Rost.Bar(Rost.offerEnglish) }

    @Test
    fun silentDark() = capture("rost_volume_silent_dark_sv", darkTheme = true) { Rost.Bar(Rost.silent) }
}

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "sv-$TABLET_PORTRAIT")
class RostTabletPortraitScreenshotTest : CookScreenshotTest("tablet_portrait") {

    @Test
    fun englishListening() = capture("rost_english_listening_light_sv") { Rost.Bar(Rost.englishListening) }

    @Test
    fun sheet() = capture("rost_sheet_light_sv") { Rost.Sheet(Rost.sheet) }
}

/** ATF (core:testing `assertAccessible`) for every new state of the bar and the sheet, light and dark, font scale 2. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "sv-$PHONE")
class RostAccessibilityTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun check(darkTheme: Boolean = false, fontScale: Float? = null, content: @Composable () -> Unit) {
        composeRule.setContent { TestFrame(darkTheme = darkTheme, fontScale = fontScale) { content() } }
        composeRule.waitForIdle()
        composeRule.assertAccessible()
    }

    @Test
    fun offerEnglish() = check { Rost.Bar(Rost.offerEnglish) }

    @Test
    fun offerEnglishDarkFontScale2() = check(darkTheme = true, fontScale = 2f) { Rost.Bar(Rost.offerEnglish) }

    @Test
    fun englishListening() = check { Rost.Bar(Rost.englishListening) }

    @Test
    fun downloadable() = check { Rost.Bar(Rost.downloadFailed) }

    @Test
    fun downloading() = check(darkTheme = true) { Rost.Bar(Rost.downloading) }

    @Test
    fun downloadLater() = check { Rost.Bar(Rost.downloadLater) }

    @Test
    fun noSpeechService() = check(darkTheme = true) { Rost.Bar(Rost.noSpeechService) }

    @Test
    fun silent() = check { Rost.Bar(Rost.silent) }

    @Test
    fun silentDarkFontScale2() = check(darkTheme = true, fontScale = 2f) { Rost.Bar(Rost.silent) }

    @Test
    fun silentCompact() = check { Rost.Bar(Rost.silent, compact = true) }

    @Test
    fun sheet() = check { Rost.Sheet(Rost.sheet) }

    @Test
    fun sheetServiceDarkFontScale2() = check(darkTheme = true, fontScale = 2f) { Rost.Sheet(Rost.sheetService) }
}
