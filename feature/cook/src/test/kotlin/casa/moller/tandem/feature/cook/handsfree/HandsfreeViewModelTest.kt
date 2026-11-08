package casa.moller.tandem.feature.cook.handsfree

import androidx.compose.ui.input.key.Key
import androidx.lifecycle.SavedStateHandle
import casa.moller.tandem.core.data.voice.SpeakResult
import casa.moller.tandem.core.data.voice.VoiceAvailability
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
 * says when the language is missing and backs off after failures.
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
        assertEquals(VoiceStatus.UNAVAILABLE, viewModel.uiState.value.voice)
        assertEquals(0, recognizer.rounds)

        recognizer.availability = VoiceAvailability.DOWNLOADABLE
        viewModel.onAction(HandsfreeAction.Retry)
        runCurrent()
        assertEquals(VoiceStatus.DOWNLOADABLE, viewModel.uiState.value.voice)
        viewModel.onAction(HandsfreeAction.DownloadLanguage)
        assertEquals(listOf("sv-SE"), recognizer.downloads)

        recognizer.availability = VoiceAvailability.AVAILABLE
        viewModel.onAction(HandsfreeAction.Retry)
        runCurrent()
        assertEquals(VoiceStatus.LISTENING, viewModel.uiState.value.voice)

        // The recognizer itself says the language is gone: it stops.
        recognizer.fail(VoiceFailure.LANGUAGE_UNAVAILABLE)
        runCurrent()
        assertEquals(VoiceStatus.UNAVAILABLE, viewModel.uiState.value.voice)
        assertFalse(recognizer.listening.value)
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
