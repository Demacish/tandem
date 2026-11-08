package casa.moller.tandem.feature.cook.handsfree

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import casa.moller.tandem.core.data.settings.SettingsRepository
import casa.moller.tandem.core.data.voice.SpeakResult
import casa.moller.tandem.core.data.voice.StepSpeaker
import casa.moller.tandem.core.data.voice.VoiceAvailability
import casa.moller.tandem.core.data.voice.VoiceCommandRecognizer
import casa.moller.tandem.core.data.voice.VoiceFailure
import casa.moller.tandem.core.data.voice.VoiceResult
import casa.moller.tandem.core.domain.timer.TimerDetector
import casa.moller.tandem.core.domain.voice.VoiceCommand
import casa.moller.tandem.core.domain.voice.VoiceCommands
import casa.moller.tandem.core.model.settings.HandsfreeSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Fas 5 (kok lane, plan §8.1 "Handsfree", #32, §10 Fas 5): cook mode's handsfree control, one per
 * cook mode (both recipes of a two-recipe session share it; the commands go to the shown one).
 *
 * - **Volume keys** are the screen's job (`CookScreen`, [HandsfreeUiState.enabled]); this
 *   ViewModel keeps the choice ([SettingsRepository.setHandsfree]).
 * - **Voice:** while cook mode is visible ([HandsfreeAction.SetVisible], the route's resume/pause)
 *   and the voice is on, it checks the microphone permission and the phone's ON-DEVICE recognition
 *   for the UI's language, then listens one utterance at a time ([VoiceCommandRecognizer.listenOnce])
 *   and restarts after every result: [AFTER_RESULT_MILLIS] after words, [AFTER_SILENCE_MILLIS]
 *   after silence, and with back-off ([backOffMillis]) after a failure; [MAX_FAILURES] failures in a
 *   row stop it ([VoiceStatus.FAILED], "Försök igen"). Pausing, the screen going off, leaving and
 *   reading aloud cancel the round at once, so the microphone is never open in the background and
 *   the phone never hears its own voice.
 * - **Reading aloud** ([StepSpeaker]): the shown page on "upprepa" or "Läs upp", and every new page
 *   when chosen, in the language of the step's text (core:domain `TimerDetector.guessLanguage`),
 *   else the UI's.
 */
@HiltViewModel
class HandsfreeViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val settingsRepository: SettingsRepository,
    private val recognizer: VoiceCommandRecognizer,
    private val speaker: StepSpeaker,
) : ViewModel() {

    private val effectChannel = Channel<HandsfreeEffect>(Channel.BUFFERED)

    /** One-off events: commands for the shown recipe, the microphone dialog, the app's settings. */
    val effects: Flow<HandsfreeEffect> = effectChannel.receiveAsFlow()

    /** Null until the stored choice is read, so nothing starts listening on the defaults for a frame. */
    private val settings: StateFlow<HandsfreeSettings?> = settingsRepository.settings
        .map { it.handsfree }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val visible = MutableStateFlow(false)
    private val language = MutableStateFlow(speechLanguage(Locale.getDefault().toLanguageTag()))
    private val speaking = MutableStateFlow(false)
    private val problem = MutableStateFlow<VoiceStatus?>(null)
    private val listening = MutableStateFlow(false)
    private val retrying = MutableStateFlow(false)
    private val heard = MutableStateFlow<VoiceCommand?>(null)
    private val speechUnavailable = MutableStateFlow(false)

    /** Bumped by "Försök igen" and a permission granted: the voice is checked and started again. */
    private val restarts = MutableStateFlow(0)

    private var shown: ShownPage? = null
    private var spokenKey: String? = null
    private var speakJob: Job? = null
    private var heardJob: Job? = null
    private val detector = TimerDetector()

    private val voiceFlags: Flow<VoiceFlags> = combine(speaking, problem, listening, retrying) { speaking, problem, listening, retrying ->
        VoiceFlags(speaking, problem, listening, retrying)
    }

    val uiState: StateFlow<HandsfreeUiState> = combine(
        settings,
        voiceFlags,
        combine(language, heard, speechUnavailable) { language, heard, unavailable -> Triple(language, heard, unavailable) },
        savedStateHandle.getStateFlow(KEY_SHEET, false),
        savedStateHandle.getStateFlow(KEY_DENIED, false),
    ) { settings, flags, (language, heard, unavailable), sheet, denied ->
        val chosen = settings ?: HandsfreeSettings()
        HandsfreeUiState(
            settings = chosen,
            voice = flags.status(chosen.listens),
            languageTag = language,
            heard = heard,
            speechUnavailable = unavailable,
            permissionDenied = denied,
            sheetOpen = sheet,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), HandsfreeUiState())

    init {
        viewModelScope.launch {
            combine(
                settings.filterNotNull().map { it.listens }.distinctUntilChanged(),
                visible,
                language,
                restarts,
            ) { listens, visible, language, _ -> Session(active = listens && visible, languageTag = language) }
                .collectLatest(::runVoice)
        }
    }

    fun onAction(action: HandsfreeAction) {
        when (action) {
            HandsfreeAction.OpenSheet -> savedStateHandle[KEY_SHEET] = true
            HandsfreeAction.CloseSheet -> savedStateHandle[KEY_SHEET] = false
            is HandsfreeAction.SetEnabled -> update { it.copy(enabled = action.enabled) }
            is HandsfreeAction.SetVoice -> setVoice(action.enabled)
            is HandsfreeAction.SetReadSteps -> setReadSteps(action.enabled)
            is HandsfreeAction.PermissionResult -> onPermissionResult(action.granted)
            HandsfreeAction.AllowMicrophone -> allowMicrophone()
            HandsfreeAction.DownloadLanguage -> recognizer.downloadLanguage(language.value)
            HandsfreeAction.Retry -> restarts.value++
            is HandsfreeAction.SetVisible -> setVisible(action.visible)
            is HandsfreeAction.SetLanguage -> language.value = speechLanguage(action.languageTag)
            is HandsfreeAction.StepShown -> onStepShown(ShownPage(action.key, action.speech))
            HandsfreeAction.ReadAloud -> shown?.let { readAloud(it) }
        }
    }

    private fun update(transform: (HandsfreeSettings) -> HandsfreeSettings) {
        val current = settings.value ?: HandsfreeSettings()
        viewModelScope.launch { settingsRepository.setHandsfree(transform(current)) }
    }

    private fun setVoice(enabled: Boolean) {
        update { it.copy(voice = enabled) }
        // Plan §8.1: the microphone is asked for in context, when the user turns the voice on.
        if (enabled && !recognizer.hasPermission()) allowMicrophone()
    }

    private fun setReadSteps(enabled: Boolean) {
        update { it.copy(readSteps = enabled) }
        // Turning it on does not read the page shown now; the next page is read.
        if (enabled) spokenKey = shown?.key
    }

    private fun allowMicrophone() {
        val effect = if (savedStateHandle.get<Boolean>(KEY_DENIED) == true) {
            HandsfreeEffect.OpenAppSettings
        } else {
            HandsfreeEffect.RequestMicrophonePermission
        }
        effectChannel.trySend(effect)
    }

    private fun onPermissionResult(granted: Boolean) {
        savedStateHandle[KEY_DENIED] = !granted
        restarts.value++
    }

    private fun setVisible(visible: Boolean) {
        this.visible.value = visible
        if (!visible) {
            speakJob?.cancel()
            return
        }
        // Back in front (or the first frame): the page shown is read if it was not yet.
        shown?.let(::readIfChosen)
    }

    private fun onStepShown(page: ShownPage) {
        shown = page
        readIfChosen(page)
    }

    private fun readIfChosen(page: ShownPage) {
        val chosen = settings.value ?: return
        if (!chosen.enabled || !chosen.readSteps || !visible.value || page.key == spokenKey) return
        readAloud(page)
    }

    private fun readAloud(page: ShownPage) {
        if (!visible.value) return
        spokenKey = page.key
        val previous = speakJob
        speakJob = viewModelScope.launch {
            // One text at a time, and `speaking` never drops between two (the microphone would open).
            previous?.cancelAndJoin()
            speaking.value = true
            try {
                val result = speaker.speak(page.speech, readingLanguage(page.speech))
                speechUnavailable.value = result == SpeakResult.UNAVAILABLE
            } finally {
                speaking.value = false
            }
        }
    }

    /** The voice for [session]: checks, then rounds of listening until something stops it. */
    private suspend fun runVoice(session: Session) {
        listening.value = false
        retrying.value = false
        if (!session.active) {
            problem.value = null
            recognizer.release()
            return
        }
        if (!recognizer.hasPermission()) {
            problem.value = VoiceStatus.NEEDS_PERMISSION
            return
        }
        problem.value = VoiceStatus.STARTING
        when (recognizer.availability(session.languageTag)) {
            VoiceAvailability.AVAILABLE -> {
                problem.value = null
                // Reading aloud closes the microphone; it opens again when the step was read.
                speaking.collectLatest { now -> if (!now && problem.value == null) listenRounds(session.languageTag) }
            }

            VoiceAvailability.DOWNLOADABLE -> problem.value = VoiceStatus.DOWNLOADABLE

            VoiceAvailability.UNAVAILABLE -> problem.value = VoiceStatus.UNAVAILABLE
        }
    }

    private suspend fun listenRounds(languageTag: String) {
        var failures = 0
        try {
            while (true) {
                listening.value = true
                val result = recognizer.listenOnce(languageTag)
                listening.value = false
                when (result) {
                    is VoiceResult.Heard -> {
                        failures = 0
                        retrying.value = false
                        VoiceCommands.parse(result.alternatives)?.let(::onCommand)
                        delay(AFTER_RESULT_MILLIS)
                    }

                    VoiceResult.NothingHeard -> {
                        failures = 0
                        retrying.value = false
                        delay(AFTER_SILENCE_MILLIS)
                    }

                    is VoiceResult.Failed -> when (result.reason) {
                        VoiceFailure.NO_PERMISSION -> {
                            problem.value = VoiceStatus.NEEDS_PERMISSION
                            return
                        }

                        VoiceFailure.LANGUAGE_UNAVAILABLE -> {
                            problem.value = VoiceStatus.UNAVAILABLE
                            return
                        }

                        VoiceFailure.BUSY, VoiceFailure.AUDIO, VoiceFailure.OTHER -> {
                            failures++
                            if (failures >= MAX_FAILURES) {
                                retrying.value = false
                                problem.value = VoiceStatus.FAILED
                                return
                            }
                            retrying.value = true
                            delay(backOffMillis(failures))
                        }
                    }
                }
            }
        } finally {
            listening.value = false
        }
    }

    private fun onCommand(command: VoiceCommand) {
        heard.value = command
        heardJob?.cancel()
        heardJob = viewModelScope.launch {
            delay(HEARD_SHOWN_MILLIS)
            heard.value = null
        }
        when (command) {
            VoiceCommand.REPEAT -> shown?.let(::readAloud)
            VoiceCommand.NEXT, VoiceCommand.PREVIOUS -> effectChannel.trySend(HandsfreeEffect.Command(command))
        }
    }

    /** The step's own language when its words tell ("sv", "en"), else the UI's. */
    private fun readingLanguage(text: String): String {
        val ui = language.value
        val guessed = detector.guessLanguage(text) ?: return ui
        return if (Locale.forLanguageTag(ui).language == guessed) ui else speechLanguage(guessed)
    }

    override fun onCleared() {
        recognizer.release()
        speaker.release()
    }

    private data class Session(val active: Boolean, val languageTag: String)

    private data class ShownPage(val key: String, val speech: String)

    private data class VoiceFlags(val speaking: Boolean, val problem: VoiceStatus?, val listening: Boolean, val retrying: Boolean) {
        fun status(listens: Boolean): VoiceStatus = when {
            speaking -> VoiceStatus.SPEAKING
            !listens -> VoiceStatus.OFF
            problem != null -> problem
            listening -> VoiceStatus.LISTENING
            retrying -> VoiceStatus.RETRYING
            else -> VoiceStatus.STARTING
        }
    }

    internal companion object {
        const val KEY_SHEET = "handsfree_sheet_open"
        const val KEY_DENIED = "handsfree_microphone_denied"

        /** After words (a command or talk): a short pause, so one "nästa" never turns two pages. */
        const val AFTER_RESULT_MILLIS = 400L

        /** After silence: listen again almost at once. */
        const val AFTER_SILENCE_MILLIS = 150L

        /** Failures in a row before the voice gives up until "Försök igen". */
        const val MAX_FAILURES = 5

        /** How long "Hörde ”nästa”" stays. */
        const val HEARD_SHOWN_MILLIS = 2_000L

        private const val STOP_TIMEOUT_MILLIS = 5_000L
        private const val FIRST_BACK_OFF_MILLIS = 1_000L
        private const val MAX_BACK_OFF_MILLIS = 15_000L

        /** 1 s, 2 s, 4 s, 8 s, then at most 15 s. */
        fun backOffMillis(failures: Int): Long =
            (FIRST_BACK_OFF_MILLIS shl (failures - 1).coerceIn(0, MAX_SHIFT)).coerceAtMost(MAX_BACK_OFF_MILLIS)

        private const val MAX_SHIFT = 4

        /** A tag the speech services understand: "sv" → "sv-SE" (Swedish has one region); others as they are. */
        fun speechLanguage(tag: String): String {
            val locale = Locale.forLanguageTag(tag)
            return when {
                locale.language.isEmpty() -> "en-US"
                locale.language == "sv" && locale.country.isEmpty() -> "sv-SE"
                else -> locale.toLanguageTag()
            }
        }
    }
}
