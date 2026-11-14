package casa.moller.tandem.feature.cook.handsfree

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import casa.moller.tandem.core.data.settings.SettingsRepository
import casa.moller.tandem.core.data.voice.SpeakResult
import casa.moller.tandem.core.data.voice.StepSpeaker
import casa.moller.tandem.core.data.voice.VoiceAvailability
import casa.moller.tandem.core.data.voice.VoiceCommandRecognizer
import casa.moller.tandem.core.data.voice.VoiceDownload
import casa.moller.tandem.core.data.voice.VoiceEngine
import casa.moller.tandem.core.data.voice.VoiceFailure
import casa.moller.tandem.core.data.voice.VoiceResult
import casa.moller.tandem.core.domain.timer.TimerDetector
import casa.moller.tandem.core.domain.voice.VoiceCommand
import casa.moller.tandem.core.domain.voice.VoiceCommands
import casa.moller.tandem.core.domain.voice.VoiceLocales
import casa.moller.tandem.core.model.settings.HandsfreeSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
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
 *
 * Device test 2026-11-14 (rost lane):
 * - B1: the recognizer gets the UI's language as it is ("en") and answers with the full tag it
 *   listens in ("en-US", core:domain `VoiceLocales`); reading aloud gets a region too.
 * - B2: "not supported" and "not downloaded" are told apart; after either the voice stops (no
 *   retry loop, so the recognizer's error beep is not repeated). "Not downloaded" offers "Hämta"
 *   ([HandsfreeAction.DownloadLanguage]), waits for the download and listens again.
 * - B3 (owner's decision "Båda vägarna"): on-device only by default; when the phone cannot
 *   recognise the UI's language on the device, it offers the English commands
 *   ([VoiceStatus.OFFER_ENGLISH], [HandsfreeSettings.englishCommands]), listened for on the device.
 *   The opt-in [HandsfreeSettings.speechService] listens in the UI's language with the phone's
 *   regular speech service ([VoiceEngine.SPEECH_SERVICE]). Matching is the same on both paths.
 * - B11: with reading aloud wanted and its volume at 0, [HandsfreeUiState.volumeSilent] shows a
 *   hint with "Höj volymen" ([HandsfreeAction.RaiseVolume]), because the volume keys turn the steps.
 */
@OptIn(ExperimentalCoroutinesApi::class)
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

    /** The UI's language as it is ("sv", "en", "en-GB"): the recognizer adds the region (B1). */
    private val language = MutableStateFlow(Locale.getDefault().toLanguageTag())
    private val speaking = MutableStateFlow(false)
    private val problem = MutableStateFlow<VoiceStatus?>(null)
    private val listening = MutableStateFlow(false)
    private val retrying = MutableStateFlow(false)
    private val heard = MutableStateFlow<VoiceCommand?>(null)
    private val speechUnavailable = MutableStateFlow(false)

    /** The full tag the voice listens in or checked last ("sv-SE", "en-US"); empty while off. */
    private val voiceTag = MutableStateFlow("")
    private val downloadFailed = MutableStateFlow(false)

    /** "Hämta" pressed while the voice waits for it ([VoiceStatus.DOWNLOADABLE]). */
    private val downloadRequests = Channel<Unit>(Channel.CONFLATED)

    /** A step was read aloud on request in this cook mode: the volume hint matters then too (B11). */
    private val readRequested = MutableStateFlow(false)

    /** B11: the reading-aloud volume is 0 while reading aloud is wanted and cook mode is in front. */
    private val volumeSilent: Flow<Boolean> = combine(settings, visible, readRequested) { chosen, visible, requested ->
        visible && chosen != null && chosen.enabled && (chosen.readSteps || requested)
    }.distinctUntilChanged().flatMapLatest { watch -> if (watch) speaker.observeSilent() else flowOf(false) }

    /** Bumped by "Försök igen" and a permission granted: the voice is checked and started again. */
    private val restarts = MutableStateFlow(0)

    private var shown: ShownPage? = null
    private var spokenKey: String? = null
    private var speakJob: Job? = null
    private var heardJob: Job? = null
    private val detector = TimerDetector()

    private val voiceFlags: Flow<VoiceFlags> = combine(speaking, problem, listening, retrying, downloadFailed, ::VoiceFlags)

    private val extras: Flow<Extras> = combine(language, heard, speechUnavailable, voiceTag, volumeSilent, ::Extras)

    val uiState: StateFlow<HandsfreeUiState> = combine(
        settings,
        voiceFlags,
        extras,
        savedStateHandle.getStateFlow(KEY_SHEET, false),
        savedStateHandle.getStateFlow(KEY_DENIED, false),
    ) { settings, flags, extras, sheet, denied ->
        val chosen = settings ?: HandsfreeSettings()
        HandsfreeUiState(
            settings = chosen,
            voice = flags.status(chosen.listens),
            languageTag = extras.language,
            voiceLanguageTag = extras.voiceTag,
            heard = extras.heard,
            speechUnavailable = extras.speechUnavailable,
            permissionDenied = denied,
            sheetOpen = sheet,
            downloadFailed = flags.downloadFailed,
            volumeSilent = extras.volumeSilent,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), HandsfreeUiState())

    init {
        viewModelScope.launch {
            combine(
                settings.filterNotNull().distinctUntilChanged { old, new ->
                    old.listens == new.listens && old.speechService == new.speechService && old.englishCommands == new.englishCommands
                },
                visible,
                language,
                restarts,
            ) { chosen, visible, language, _ ->
                Session(
                    active = chosen.listens && visible,
                    languageTag = language,
                    speechService = chosen.speechService,
                    englishCommands = chosen.englishCommands,
                )
            }.collectLatest(::runVoice)
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
            HandsfreeAction.DownloadLanguage -> downloadRequests.trySend(Unit)
            HandsfreeAction.Retry -> restarts.value++
            is HandsfreeAction.SetVisible -> setVisible(action.visible)
            is HandsfreeAction.SetLanguage -> language.value = action.languageTag
            is HandsfreeAction.StepShown -> onStepShown(ShownPage(action.key, action.speech))
            HandsfreeAction.ReadAloud -> readOnRequest()
            is HandsfreeAction.SetSpeechService -> update { it.copy(speechService = action.enabled) }
            is HandsfreeAction.SetEnglishCommands -> update { it.copy(englishCommands = action.enabled) }
            HandsfreeAction.RaiseVolume -> speaker.raiseVolume()
        }
    }

    private fun readOnRequest() {
        readRequested.value = true
        shown?.let(::readAloud)
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
        val wanted = chosen.enabled && chosen.readSteps
        if (wanted && visible.value && page.key != spokenKey) readAloud(page)
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

    /**
     * The voice for [session]: checks, then rounds of listening until something stops it. A
     * missing language is downloaded on "Hämta" and checked again; a language the phone cannot
     * recognise on the device leads to the English commands' offer (B3).
     */
    private suspend fun runVoice(session: Session) {
        listening.value = false
        retrying.value = false
        downloadFailed.value = false
        if (!session.active) {
            problem.value = null
            voiceTag.value = ""
            recognizer.release()
            return
        }
        if (!recognizer.hasPermission()) {
            problem.value = VoiceStatus.NEEDS_PERMISSION
            return
        }
        val engine = if (session.speechService) VoiceEngine.SPEECH_SERVICE else VoiceEngine.ON_DEVICE
        val uiEnglish = VoiceLocales.isEnglish(session.languageTag)
        val english = engine == VoiceEngine.ON_DEVICE && session.englishCommands && !uiEnglish
        val wanted = if (english) VoiceLocales.ENGLISH else session.languageTag
        while (true) {
            problem.value = VoiceStatus.STARTING
            val check = recognizer.check(wanted, engine)
            voiceTag.value = check.languageTag
            val end = when (check.availability) {
                VoiceAvailability.AVAILABLE, VoiceAvailability.UNKNOWN -> listenWhileQuiet(check.languageTag, engine)
                VoiceAvailability.DOWNLOADABLE -> VoiceEnd.NOT_DOWNLOADED
                VoiceAvailability.UNAVAILABLE -> VoiceEnd.UNSUPPORTED
                VoiceAvailability.NO_SERVICE -> VoiceEnd.NO_SERVICE
            }
            if (end != VoiceEnd.NOT_DOWNLOADED) {
                val offer = end == VoiceEnd.UNSUPPORTED && engine == VoiceEngine.ON_DEVICE && !english && !uiEnglish
                problem.value = if (offer) offerEnglish() else statusAfter(end, engine)
                return
            }
            // Downloaded now: check and listen again. Otherwise the status the download left.
            if (!downloaded(check.languageTag, engine)) return
        }
    }

    private fun statusAfter(end: VoiceEnd, engine: VoiceEngine): VoiceStatus = when (end) {
        VoiceEnd.NOT_DOWNLOADED -> VoiceStatus.DOWNLOADABLE
        VoiceEnd.UNSUPPORTED -> VoiceStatus.UNAVAILABLE
        VoiceEnd.NO_SERVICE -> if (engine == VoiceEngine.SPEECH_SERVICE) VoiceStatus.NO_SPEECH_SERVICE else VoiceStatus.UNAVAILABLE
        VoiceEnd.NO_PERMISSION -> VoiceStatus.NEEDS_PERMISSION
        VoiceEnd.GAVE_UP -> VoiceStatus.FAILED
    }

    /** B3 (a): the English commands are offered when the on-device recognizer may have English. */
    private suspend fun offerEnglish(): VoiceStatus {
        val english = recognizer.check(VoiceLocales.ENGLISH, VoiceEngine.ON_DEVICE).availability
        return if (english == VoiceAvailability.UNAVAILABLE || english == VoiceAvailability.NO_SERVICE) {
            VoiceStatus.UNAVAILABLE
        } else {
            VoiceStatus.OFFER_ENGLISH
        }
    }

    /**
     * B2: waits for "Hämta", then for the download. True when the language is on the phone now;
     * false when the phone downloads it later ([VoiceStatus.DOWNLOAD_LATER], "Försök igen"). A
     * failed download offers "Hämta" again and says it failed.
     */
    private suspend fun downloaded(languageTag: String, engine: VoiceEngine): Boolean {
        // A tap from an earlier state does not count.
        downloadRequests.tryReceive()
        while (true) {
            problem.value = VoiceStatus.DOWNLOADABLE
            downloadRequests.receive()
            problem.value = VoiceStatus.DOWNLOADING
            downloadFailed.value = false
            when (recognizer.downloadLanguage(languageTag, engine)) {
                VoiceDownload.DONE -> return true

                VoiceDownload.SCHEDULED -> {
                    problem.value = VoiceStatus.DOWNLOAD_LATER
                    return false
                }

                VoiceDownload.FAILED -> downloadFailed.value = true
            }
        }
    }

    /** Rounds of listening; reading aloud closes the microphone, which opens again when the step was read. */
    private suspend fun listenWhileQuiet(languageTag: String, engine: VoiceEngine): VoiceEnd {
        problem.value = null
        return speaking.transformLatest { now -> if (!now) emit(listenRounds(languageTag, engine)) }.first()
    }

    /** Listens until a definite end ([VoiceEnd]); cancelled when a step is read aloud or the voice stops. */
    private suspend fun listenRounds(languageTag: String, engine: VoiceEngine): VoiceEnd {
        var failures = 0
        try {
            while (true) {
                listening.value = true
                val result = recognizer.listenOnce(languageTag, engine)
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
                        // Definite answers: stop at once (B2), no retry loop and no repeated error beep.
                        VoiceFailure.NO_PERMISSION -> return VoiceEnd.NO_PERMISSION

                        VoiceFailure.LANGUAGE_UNAVAILABLE -> return VoiceEnd.UNSUPPORTED

                        VoiceFailure.LANGUAGE_NOT_DOWNLOADED -> return VoiceEnd.NOT_DOWNLOADED

                        VoiceFailure.BUSY, VoiceFailure.AUDIO, VoiceFailure.OTHER -> {
                            failures++
                            if (failures >= MAX_FAILURES) {
                                retrying.value = false
                                return VoiceEnd.GAVE_UP
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
            VoiceCommand.REPEAT -> readOnRequest()
            VoiceCommand.NEXT, VoiceCommand.PREVIOUS -> effectChannel.trySend(HandsfreeEffect.Command(command))
        }
    }

    /** The step's own language when its words tell ("sv", "en"), else the UI's; always with a region. */
    private fun readingLanguage(text: String): String {
        val ui = speechLanguage(language.value)
        val guessed = detector.guessLanguage(text) ?: return ui
        return if (Locale.forLanguageTag(ui).language == guessed) ui else speechLanguage(guessed)
    }

    override fun onCleared() {
        recognizer.release()
        speaker.release()
    }

    private data class Session(val active: Boolean, val languageTag: String, val speechService: Boolean, val englishCommands: Boolean)

    /** How listening in one language ended for good. */
    private enum class VoiceEnd { NOT_DOWNLOADED, UNSUPPORTED, NO_SERVICE, NO_PERMISSION, GAVE_UP }

    private data class ShownPage(val key: String, val speech: String)

    private data class Extras(
        val language: String,
        val heard: VoiceCommand?,
        val speechUnavailable: Boolean,
        val voiceTag: String,
        val volumeSilent: Boolean,
    )

    private data class VoiceFlags(
        val speaking: Boolean,
        val problem: VoiceStatus?,
        val listening: Boolean,
        val retrying: Boolean,
        val downloadFailed: Boolean,
    ) {
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

        /** A tag the speech services understand, always with a region (B1): "sv" → "sv-SE", "en" → "en-US". */
        fun speechLanguage(tag: String): String = VoiceLocales.resolve(tag)
    }
}
