package casa.moller.tandem.feature.cook.handsfree

import androidx.compose.ui.input.key.Key
import androidx.lifecycle.SavedStateHandle
import casa.moller.tandem.core.data.voice.SpeakResult
import casa.moller.tandem.core.data.voice.VoiceAvailability
import casa.moller.tandem.core.data.voice.VoiceDownload
import casa.moller.tandem.core.data.voice.VoiceEngine
import casa.moller.tandem.core.data.voice.VoiceFailure
import casa.moller.tandem.core.domain.voice.VoiceCommand
import casa.moller.tandem.core.model.settings.AppSettings
import casa.moller.tandem.core.model.settings.HandsfreeSettings
import casa.moller.tandem.core.testing.MainDispatcherRule
import casa.moller.tandem.core.testing.data.FakeSettingsRepository
import casa.moller.tandem.core.testing.data.FakeStepSpeaker
import casa.moller.tandem.core.testing.data.FakeVoiceCommandRecognizer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Fas 5 (plan §8.1 "Handsfree", #32): the handsfree control with a fake on-device recognizer and a
 * fake text-to-speech engine: it listens only while visible, one utterance at a time, turns pages
 * for the shown recipe, reads aloud with the microphone closed, asks for the microphone in context,
 * says when the language is missing and backs off after failures. Device test 2026-11-14: the
 * language with a region (B1), "not downloaded" with the download and no retry loop (B2), the
 * English commands and the opt-in speech service (B3), the silent volume (B11).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HandsfreeViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val recognizer = FakeVoiceCommandRecognizer()
    private val speaker = FakeStepSpeaker()
    private val voiceOn = HandsfreeSettings(enabled = true, voice = true)

    private fun TestScope.handsfree(
        chosen: HandsfreeSettings = voiceOn,
        handle: SavedStateHandle = SavedStateHandle(),
    ): Pair<HandsfreeViewModel, MutableList<HandsfreeEffect>> {
        val settings = FakeSettingsRepository(AppSettings(handsfree = chosen))
        val viewModel = HandsfreeViewModel(handle, settings, recognizer, speaker)
        val effects = mutableListOf<HandsfreeEffect>()
        backgroundScope.launch { viewModel.uiState.collect {} }
        backgroundScope.launch { viewModel.effects.collect { effects += it } }
        viewModel.onAction(HandsfreeAction.SetLanguage("sv-SE"))
        return viewModel to effects
    }

    @Test
    fun offByDefaultTheMicrophoneStaysClosed() = runTest(mainDispatcher.dispatcher) {
        val (viewModel, _) = handsfree(chosen = HandsfreeSettings())
        viewModel.onAction(HandsfreeAction.SetVisible(true))
        runCurrent()
        assertFalse(viewModel.uiState.value.enabled)
        assertEquals(VoiceStatus.OFF, viewModel.uiState.value.voice)
        assertEquals(0, recognizer.rounds)

        // Volume keys only: still no microphone.
        val keysOnly = handsfree(chosen = HandsfreeSettings(enabled = true)).first
        keysOnly.onAction(HandsfreeAction.SetVisible(true))
        runCurrent()
        assertTrue(keysOnly.uiState.value.enabled)
        assertEquals(VoiceStatus.OFF, keysOnly.uiState.value.voice)
        assertEquals(0, recognizer.rounds)
    }

    @Test
    fun itListensOnlyWhileCookModeIsVisible() = runTest(mainDispatcher.dispatcher) {
        val (viewModel, _) = handsfree()
        runCurrent()
        assertEquals(0, recognizer.rounds)

        viewModel.onAction(HandsfreeAction.SetVisible(true))
        runCurrent()
        assertTrue(recognizer.listening.value)
        assertEquals(VoiceStatus.LISTENING, viewModel.uiState.value.voice)
        assertEquals(listOf("sv-SE"), recognizer.languages)
        // On the device unless the speech service is chosen (B3).
        assertEquals(listOf(VoiceEngine.ON_DEVICE), recognizer.engines)

        // Paused or the screen off: the round is cancelled at once and the recognizer freed.
        viewModel.onAction(HandsfreeAction.SetVisible(false))
        runCurrent()
        assertFalse(recognizer.listening.value)
        assertTrue(recognizer.releases >= 1)

        viewModel.onAction(HandsfreeAction.SetVisible(true))
        runCurrent()
        assertTrue(recognizer.listening.value)
        assertEquals(2, recognizer.rounds)
    }

    @Test
    fun aCommandTurnsThePageAndListeningRestarts() = runTest(mainDispatcher.dispatcher) {
        val (viewModel, effects) = handsfree()
        viewModel.onAction(HandsfreeAction.SetVisible(true))
        runCurrent()

        recognizer.hear("Nästa steg", "nästa sten")
        runCurrent()
        assertEquals(listOf<HandsfreeEffect>(HandsfreeEffect.Command(VoiceCommand.NEXT)), effects)
        assertEquals(VoiceCommand.NEXT, viewModel.uiState.value.heard)
        // A short pause after words, then the next round.
        assertFalse(recognizer.listening.value)
        advanceTimeBy(HandsfreeViewModel.AFTER_RESULT_MILLIS + 1)
        assertTrue(recognizer.listening.value)
        assertEquals(2, recognizer.rounds)

        recognizer.hear("gå tillbaka")
        advanceTimeBy(HandsfreeViewModel.AFTER_RESULT_MILLIS + 1)
        assertEquals(HandsfreeEffect.Command(VoiceCommand.PREVIOUS), effects.last())

        // Talk is no command; silence restarts quickly.
        recognizer.hear("vi tar nästa vecka")
        advanceTimeBy(HandsfreeViewModel.AFTER_RESULT_MILLIS + 1)
        recognizer.hearNothing()
        advanceTimeBy(HandsfreeViewModel.AFTER_SILENCE_MILLIS + 1)
        assertEquals(2, effects.size)
        assertEquals(5, recognizer.rounds)

        advanceTimeBy(HandsfreeViewModel.HEARD_SHOWN_MILLIS)
        assertNull(viewModel.uiState.value.heard)
    }

    @Test
    fun repeatReadsTheShownStepWithTheMicrophoneClosed() = runTest(mainDispatcher.dispatcher) {
        speaker.holdSpeech = true
        val (viewModel, effects) = handsfree()
        viewModel.onAction(HandsfreeAction.SetVisible(true))
        viewModel.onAction(HandsfreeAction.StepShown("1:0", "Steg 1 av 3. Förbered. Skiva champinjonerna"))
        runCurrent()
        // "Read every step" is off: nothing is read by itself.
        assertTrue(speaker.spoken.isEmpty())

        recognizer.hear("upprepa")
        runCurrent()
        assertEquals(listOf("Steg 1 av 3. Förbered. Skiva champinjonerna" to "sv-SE"), speaker.spoken)
        assertTrue(effects.isEmpty())
        assertEquals(VoiceStatus.SPEAKING, viewModel.uiState.value.voice)
        advanceTimeBy(HandsfreeViewModel.AFTER_RESULT_MILLIS + 1)
        // While the step is read the phone does not listen (it would hear itself).
        assertFalse(recognizer.listening.value)

        speaker.finishSpeaking()
        runCurrent()
        assertTrue(recognizer.listening.value)
        assertEquals(VoiceStatus.LISTENING, viewModel.uiState.value.voice)

        // "Läs upp" does the same.
        speaker.holdSpeech = false
        viewModel.onAction(HandsfreeAction.ReadAloud)
        runCurrent()
        assertEquals(2, speaker.spoken.size)
    }

    @Test
    fun everyNewStepIsReadWhenChosen() = runTest(mainDispatcher.dispatcher) {
        val (viewModel, _) = handsfree(chosen = HandsfreeSettings(enabled = true, readSteps = true))
        viewModel.onAction(HandsfreeAction.StepShown("1:0", "Steg 1 av 3"))
        runCurrent()
        // Not before cook mode is in front.
        assertTrue(speaker.spoken.isEmpty())
        viewModel.onAction(HandsfreeAction.SetVisible(true))
        runCurrent()
        assertEquals(listOf("Steg 1 av 3"), speaker.spoken.map { it.first })

        viewModel.onAction(HandsfreeAction.StepShown("1:0", "Steg 1 av 3"))
        viewModel.onAction(HandsfreeAction.SetVisible(false))
        viewModel.onAction(HandsfreeAction.SetVisible(true))
        runCurrent()
        assertEquals(1, speaker.spoken.size)

        viewModel.onAction(HandsfreeAction.StepShown("1:1", "Steg 2 av 3"))
        runCurrent()
        assertEquals(listOf("Steg 1 av 3", "Steg 2 av 3"), speaker.spoken.map { it.first })
    }

    @Test
    fun theStepIsReadInItsOwnLanguage() = runTest(mainDispatcher.dispatcher) {
        val (viewModel, _) = handsfree(chosen = HandsfreeSettings(enabled = true, readSteps = true))
        viewModel.onAction(HandsfreeAction.SetLanguage("en-GB"))
        viewModel.onAction(HandsfreeAction.SetVisible(true))
        viewModel.onAction(HandsfreeAction.StepShown("1:0", "Step 1 of 2. Koka pastan i saltat vatten och låt den rinna av"))
        runCurrent()
        assertEquals("sv-SE", speaker.spoken.single().second)
        viewModel.onAction(HandsfreeAction.StepShown("1:1", "Step 2 of 2. Boil the pasta in salted water and drain it"))
        runCurrent()
        assertEquals("en-GB", speaker.spoken.last().second)
    }

    @Test
    fun noVoiceToReadWithIsSaid() = runTest(mainDispatcher.dispatcher) {
        speaker.result = SpeakResult.UNAVAILABLE
        val (viewModel, _) = handsfree()
        viewModel.onAction(HandsfreeAction.SetVisible(true))
        viewModel.onAction(HandsfreeAction.StepShown("1:0", "Steg 1 av 3"))
        viewModel.onAction(HandsfreeAction.ReadAloud)
        runCurrent()
        assertTrue(viewModel.uiState.value.speechUnavailable)
    }

    @Test
    fun theMicrophoneIsAskedForInContext() = runTest(mainDispatcher.dispatcher) {
        recognizer.permission = false
        val (viewModel, effects) = handsfree(chosen = HandsfreeSettings(enabled = true))
        viewModel.onAction(HandsfreeAction.SetVisible(true))
        runCurrent()
        assertTrue(effects.isEmpty())

        viewModel.onAction(HandsfreeAction.SetVoice(true))
        runCurrent()
        assertEquals(listOf<HandsfreeEffect>(HandsfreeEffect.RequestMicrophonePermission), effects)
        assertEquals(VoiceStatus.NEEDS_PERMISSION, viewModel.uiState.value.voice)
        assertEquals(0, recognizer.rounds)

        // "Don't allow": "Tillåt" now leads to the app's settings.
        viewModel.onAction(HandsfreeAction.PermissionResult(false))
        runCurrent()
        assertTrue(viewModel.uiState.value.permissionDenied)
        viewModel.onAction(HandsfreeAction.AllowMicrophone)
        runCurrent()
        assertEquals(HandsfreeEffect.OpenAppSettings, effects.last())

        // Allowed in the settings: back in cook mode it listens.
        recognizer.permission = true
        viewModel.onAction(HandsfreeAction.SetVisible(false))
        viewModel.onAction(HandsfreeAction.SetVisible(true))
        runCurrent()
        assertTrue(recognizer.listening.value)
    }

    @Test
    fun aMissingLanguageIsSaidAndCanBeDownloaded() = runTest(mainDispatcher.dispatcher) {
        recognizer.availability = VoiceAvailability.UNAVAILABLE
        val (viewModel, _) = handsfree()
        viewModel.onAction(HandsfreeAction.SetVisible(true))
        runCurrent()
        // Neither Swedish nor English on the device: nothing to offer.
        assertEquals(VoiceStatus.UNAVAILABLE, viewModel.uiState.value.voice)
        assertEquals(0, recognizer.rounds)

        recognizer.availability = VoiceAvailability.DOWNLOADABLE
        recognizer.holdDownload = true
        viewModel.onAction(HandsfreeAction.Retry)
        runCurrent()
        assertEquals(VoiceStatus.DOWNLOADABLE, viewModel.uiState.value.voice)
        viewModel.onAction(HandsfreeAction.DownloadLanguage)
        runCurrent()
        assertEquals(listOf("sv-SE"), recognizer.downloads)
        assertEquals(VoiceStatus.DOWNLOADING, viewModel.uiState.value.voice)

        // Downloaded: it checks again and listens, without "Försök igen".
        recognizer.availability = VoiceAvailability.AVAILABLE
        recognizer.finishDownload(VoiceDownload.DONE)
        runCurrent()
        assertEquals(VoiceStatus.LISTENING, viewModel.uiState.value.voice)

        // The recognizer itself says the language is gone: it stops.
        recognizer.availability = VoiceAvailability.UNAVAILABLE
        recognizer.fail(VoiceFailure.LANGUAGE_UNAVAILABLE)
        runCurrent()
        assertEquals(VoiceStatus.UNAVAILABLE, viewModel.uiState.value.voice)
        assertFalse(recognizer.listening.value)
    }

    @Test
    fun notDownloadedOffersTheDownloadAndStopsListening() = runTest(mainDispatcher.dispatcher) {
        // B2: the device test's recognizer reported empty lists (unknown) and answered error 13.
        recognizer.availability = VoiceAvailability.UNKNOWN
        val (viewModel, _) = handsfree()
        viewModel.onAction(HandsfreeAction.SetVisible(true))
        runCurrent()
        assertTrue(recognizer.listening.value)

        recognizer.fail(VoiceFailure.LANGUAGE_NOT_DOWNLOADED)
        runCurrent()
        assertEquals(VoiceStatus.DOWNLOADABLE, viewModel.uiState.value.voice)
        // No retry loop (each try made the recognizer beep).
        advanceTimeBy(60_000)
        assertEquals(1, recognizer.rounds)
        assertFalse(recognizer.listening.value)

        // A failed download says so and offers "Hämta" again.
        recognizer.downloadResult = VoiceDownload.FAILED
        viewModel.onAction(HandsfreeAction.DownloadLanguage)
        runCurrent()
        assertEquals(VoiceStatus.DOWNLOADABLE, viewModel.uiState.value.voice)
        assertTrue(viewModel.uiState.value.downloadFailed)

        // The phone downloads it later: "Försök igen" checks again.
        recognizer.downloadResult = VoiceDownload.SCHEDULED
        viewModel.onAction(HandsfreeAction.DownloadLanguage)
        runCurrent()
        assertEquals(VoiceStatus.DOWNLOAD_LATER, viewModel.uiState.value.voice)
        assertEquals(listOf("sv-SE", "sv-SE"), recognizer.downloads)
        assertEquals(1, recognizer.rounds)
        viewModel.onAction(HandsfreeAction.Retry)
        runCurrent()
        assertTrue(recognizer.listening.value)
        assertEquals(2, recognizer.rounds)
        assertFalse(viewModel.uiState.value.downloadFailed)
    }

    @Test
    fun aTapOnDownloadBeforeItIsOfferedDoesNotCount() = runTest(mainDispatcher.dispatcher) {
        val (viewModel, _) = handsfree()
        viewModel.onAction(HandsfreeAction.DownloadLanguage)
        recognizer.availability = VoiceAvailability.DOWNLOADABLE
        viewModel.onAction(HandsfreeAction.SetVisible(true))
        runCurrent()
        assertEquals(VoiceStatus.DOWNLOADABLE, viewModel.uiState.value.voice)
        assertTrue(recognizer.downloads.isEmpty())
    }

    @Test
    fun withoutTheLanguageOnTheDeviceTheEnglishCommandsAreOffered() = runTest(mainDispatcher.dispatcher) {
        // B3 (a): Swedish is not on the device (error 12 after "unknown"), English may be.
        recognizer.availability = VoiceAvailability.UNKNOWN
        val (viewModel, effects) = handsfree()
        viewModel.onAction(HandsfreeAction.SetVisible(true))
        runCurrent()
        recognizer.fail(VoiceFailure.LANGUAGE_UNAVAILABLE)
        runCurrent()
        assertEquals(VoiceStatus.OFFER_ENGLISH, viewModel.uiState.value.voice)
        assertEquals("sv-SE", viewModel.uiState.value.voiceLanguage)
        advanceTimeBy(60_000)
        assertEquals(1, recognizer.rounds)
        assertTrue(("en" to VoiceEngine.ON_DEVICE) in recognizer.checks)

        // "Använd engelska": the English commands, on the device, with the words shown in English.
        viewModel.onAction(HandsfreeAction.SetEnglishCommands(true))
        runCurrent()
        assertTrue(recognizer.listening.value)
        assertEquals("en-US", recognizer.languages.last())
        assertEquals(VoiceEngine.ON_DEVICE, recognizer.engines.last())
        assertTrue(viewModel.uiState.value.settings.englishCommands)
        assertTrue(viewModel.uiState.value.commandsInEnglish)
        recognizer.hear("Next")
        runCurrent()
        assertEquals(HandsfreeEffect.Command(VoiceCommand.NEXT), effects.last())
    }

    @Test
    fun noEnglishOfferWithoutEnglishOrInAnEnglishUi() = runTest(mainDispatcher.dispatcher) {
        recognizer.availabilityByLanguage["sv"] = VoiceAvailability.UNAVAILABLE
        recognizer.availabilityByLanguage["en"] = VoiceAvailability.UNAVAILABLE
        val (viewModel, _) = handsfree()
        viewModel.onAction(HandsfreeAction.SetVisible(true))
        runCurrent()
        assertEquals(VoiceStatus.UNAVAILABLE, viewModel.uiState.value.voice)

        // An English UI the phone cannot recognise has nothing else to offer either.
        recognizer.availabilityByLanguage["en"] = VoiceAvailability.UNAVAILABLE
        viewModel.onAction(HandsfreeAction.SetLanguage("en"))
        runCurrent()
        assertEquals(VoiceStatus.UNAVAILABLE, viewModel.uiState.value.voice)
        assertFalse(viewModel.uiState.value.commandsInEnglish)
        assertEquals(0, recognizer.rounds)
    }

    @Test
    fun theSpeechServiceListensInTheAppsLanguageWhenChosen() = runTest(mainDispatcher.dispatcher) {
        // B3 (b): opt-in; the on-device recognizer has no Swedish, the speech service is asked instead.
        recognizer.availabilityByLanguage["sv"] = VoiceAvailability.UNAVAILABLE
        recognizer.speechServiceAvailability = VoiceAvailability.UNKNOWN
        val (viewModel, effects) = handsfree(chosen = voiceOn.copy(speechService = true))
        viewModel.onAction(HandsfreeAction.SetVisible(true))
        runCurrent()
        assertTrue(recognizer.listening.value)
        assertEquals(listOf("sv-SE"), recognizer.languages)
        assertEquals(listOf(VoiceEngine.SPEECH_SERVICE), recognizer.engines)
        assertFalse(viewModel.uiState.value.commandsInEnglish)
        // The same matching as on the device.
        recognizer.hear("nästa steg")
        runCurrent()
        assertEquals(HandsfreeEffect.Command(VoiceCommand.NEXT), effects.single())

        // Turned off again: back on the device, which has no Swedish but English.
        viewModel.onAction(HandsfreeAction.SetSpeechService(false))
        runCurrent()
        assertFalse(recognizer.listening.value)
        assertEquals(VoiceStatus.OFFER_ENGLISH, viewModel.uiState.value.voice)
    }

    @Test
    fun noSpeechServiceIsSaid() = runTest(mainDispatcher.dispatcher) {
        recognizer.speechServiceAvailability = VoiceAvailability.NO_SERVICE
        val (viewModel, _) = handsfree(chosen = voiceOn.copy(speechService = true))
        viewModel.onAction(HandsfreeAction.SetVisible(true))
        runCurrent()
        assertEquals(VoiceStatus.NO_SPEECH_SERVICE, viewModel.uiState.value.voice)
        assertEquals(0, recognizer.rounds)
    }

    @Test
    fun theRecognizerGetsTheUiLanguageAndListensWithARegion() = runTest(mainDispatcher.dispatcher) {
        // B1: "en" goes to the recognizer as it is (it adds the phone's region); never a bare "en".
        val (viewModel, _) = handsfree()
        viewModel.onAction(HandsfreeAction.SetLanguage("en"))
        viewModel.onAction(HandsfreeAction.SetVisible(true))
        runCurrent()
        assertEquals("en" to VoiceEngine.ON_DEVICE, recognizer.checks.last())
        assertEquals(listOf("en-US"), recognizer.languages)
        assertEquals("en-US", viewModel.uiState.value.voiceLanguageTag)
    }

    @Test
    fun aSilentVolumeIsSaidAndCanBeRaised() = runTest(mainDispatcher.dispatcher) {
        // B11: only while reading aloud is wanted and cook mode is in front.
        speaker.silent.value = true
        val (viewModel, _) = handsfree(chosen = HandsfreeSettings(enabled = true))
        viewModel.onAction(HandsfreeAction.SetVisible(true))
        runCurrent()
        assertFalse(viewModel.uiState.value.volumeSilent)

        // "Läs upp" asks for it once: from then on the hint shows.
        viewModel.onAction(HandsfreeAction.StepShown("1:0", "Steg 1 av 3"))
        viewModel.onAction(HandsfreeAction.ReadAloud)
        runCurrent()
        assertTrue(viewModel.uiState.value.volumeSilent)

        viewModel.onAction(HandsfreeAction.RaiseVolume)
        runCurrent()
        assertEquals(1, speaker.raises)
        assertFalse(viewModel.uiState.value.volumeSilent)

        // Read every step, volume down to 0 again: the hint is back; not while cook mode is hidden.
        val reading = handsfree(chosen = HandsfreeSettings(enabled = true, readSteps = true)).first
        reading.onAction(HandsfreeAction.SetVisible(true))
        speaker.silent.value = true
        runCurrent()
        assertTrue(reading.uiState.value.volumeSilent)
        reading.onAction(HandsfreeAction.SetVisible(false))
        runCurrent()
        assertFalse(reading.uiState.value.volumeSilent)
    }

    @Test
    fun failuresBackOffAndStopUntilTryAgain() = runTest(mainDispatcher.dispatcher) {
        val (viewModel, _) = handsfree()
        viewModel.onAction(HandsfreeAction.SetVisible(true))
        runCurrent()

        recognizer.fail(VoiceFailure.BUSY)
        runCurrent()
        assertEquals(VoiceStatus.RETRYING, viewModel.uiState.value.voice)
        advanceTimeBy(HandsfreeViewModel.backOffMillis(1) - 1)
        assertEquals(1, recognizer.rounds)
        advanceTimeBy(2)
        assertEquals(2, recognizer.rounds)

        repeat(HandsfreeViewModel.MAX_FAILURES - 1) { failure ->
            recognizer.fail(VoiceFailure.OTHER)
            advanceTimeBy(HandsfreeViewModel.backOffMillis(failure + 2) + 1)
        }
        assertEquals(VoiceStatus.FAILED, viewModel.uiState.value.voice)
        assertFalse(recognizer.listening.value)
        val rounds = recognizer.rounds

        viewModel.onAction(HandsfreeAction.Retry)
        runCurrent()
        assertTrue(recognizer.listening.value)
        assertEquals(rounds + 1, recognizer.rounds)
    }

    @Test
    fun backOffGrowsAndIsCapped() {
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L, 15_000L), (1..6).map { HandsfreeViewModel.backOffMillis(it) })
        assertEquals("sv-SE", HandsfreeViewModel.speechLanguage("sv"))
        assertEquals("en-GB", HandsfreeViewModel.speechLanguage("en-GB"))
        // B1: reading aloud gets a region too.
        assertEquals("en-US", HandsfreeViewModel.speechLanguage("en"))
        assertEquals("de-DE", HandsfreeViewModel.speechLanguage("de"))
    }

    @Test
    fun theChoiceAndTheSheetSurvive() = runTest(mainDispatcher.dispatcher) {
        val handle = SavedStateHandle()
        val (viewModel, _) = handsfree(chosen = HandsfreeSettings(), handle = handle)
        viewModel.onAction(HandsfreeAction.OpenSheet)
        viewModel.onAction(HandsfreeAction.SetEnabled(true))
        runCurrent()
        assertTrue(viewModel.uiState.value.sheetOpen)
        assertTrue(viewModel.uiState.value.enabled)
        val again = handsfree(chosen = HandsfreeSettings(enabled = true), handle = handle).first
        runCurrent()
        assertTrue(again.uiState.value.sheetOpen)
        again.onAction(HandsfreeAction.CloseSheet)
        runCurrent()
        assertFalse(again.uiState.value.sheetOpen)
    }

    @Test
    fun volumeKeysTurnPagesOnlyWhenActive() {
        assertEquals(VolumeKey.NEXT, volumeKey(Key.VolumeDown, down = true, repeatCount = 0, active = true))
        assertEquals(VolumeKey.PREVIOUS, volumeKey(Key.VolumeUp, down = true, repeatCount = 0, active = true))
        // The release and a held key are eaten (no volume change, no extra pages).
        assertEquals(VolumeKey.SWALLOW, volumeKey(Key.VolumeDown, down = false, repeatCount = 0, active = true))
        assertEquals(VolumeKey.SWALLOW, volumeKey(Key.VolumeUp, down = true, repeatCount = 3, active = true))
        // Handsfree off or an alarm ringing: the keys change the volume.
        assertEquals(VolumeKey.PASS, volumeKey(Key.VolumeDown, down = true, repeatCount = 0, active = false))
        assertEquals(VolumeKey.PASS, volumeKey(Key.VolumeUp, down = false, repeatCount = 0, active = false))
        // Other keys are cook mode's keyboard handling.
        assertEquals(VolumeKey.PASS, volumeKey(Key.DirectionRight, down = true, repeatCount = 0, active = true))
    }
}
