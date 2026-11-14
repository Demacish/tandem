package casa.moller.tandem.core.testing.data

import casa.moller.tandem.core.data.recipe.PanSizeRepository
import casa.moller.tandem.core.data.voice.SpeakResult
import casa.moller.tandem.core.data.voice.StepSpeaker
import casa.moller.tandem.core.data.voice.VoiceAvailability
import casa.moller.tandem.core.data.voice.VoiceCheck
import casa.moller.tandem.core.data.voice.VoiceCommandRecognizer
import casa.moller.tandem.core.data.voice.VoiceDownload
import casa.moller.tandem.core.data.voice.VoiceEngine
import casa.moller.tandem.core.data.voice.VoiceFailure
import casa.moller.tandem.core.data.voice.VoiceResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

// Fas 5 (kok lane): fakes for handsfree (plan §8.1, #32) and the tin size (plan §8.1 "Pannstorlek").

/** In-memory [PanSizeRepository] (one source). */
class FakePanSizeRepository : PanSizeRepository {
    val chosen = MutableStateFlow<Map<Long, Int>>(emptyMap())

    override fun observePanSize(recipeId: Long): Flow<Int?> = chosen.map { it[recipeId] }.distinctUntilChanged()

    override suspend fun setPanSize(recipeId: Long, diameter: Int?) {
        chosen.update { if (diameter == null || diameter <= 0) it - recipeId else it + (recipeId to diameter) }
    }
}

/**
 * A [VoiceCommandRecognizer] driven by the test: every [listenOnce] waits until the test answers
 * with [hear], [hearNothing] or [fail]. [listening] says whether a round is open now (the
 * microphone would be on), [rounds] counts them, [languages] records their language and [engines]
 * their engine. [availability] answers [check] per language ("sv", "en": the language part of the
 * tag), [availabilityFor] for every other language; [check] returns the tag with a region the way
 * the real recognizer does ("sv" → "sv-SE", "en" → "en-US"). [downloads] records the downloads,
 * which wait for [finishDownload] when [holdDownload] is on and answer [downloadResult] otherwise.
 */
class FakeVoiceCommandRecognizer : VoiceCommandRecognizer {
    /** RECORD_AUDIO is granted. */
    var permission: Boolean = true

    /** What [check] answers for languages not in [availabilityByLanguage]. */
    var availability: VoiceAvailability = VoiceAvailability.AVAILABLE

    /** What [check] answers per language part ("sv", "en"); wins over [availability]. */
    val availabilityByLanguage = mutableMapOf<String, VoiceAvailability>()

    /** What [check] answers with the speech service; null = [availability]. */
    var speechServiceAvailability: VoiceAvailability? = null

    /** What a download ends with. */
    var downloadResult: VoiceDownload = VoiceDownload.DONE

    /** Downloads wait for [finishDownload]. */
    var holdDownload: Boolean = false

    private val answers = Channel<VoiceResult>(Channel.UNLIMITED)
    private val open = MutableStateFlow(false)
    private var pendingDownload: CompletableDeferred<VoiceDownload>? = null

    /** A round of listening is open (the microphone would be on). */
    val listening: StateFlow<Boolean> = open.asStateFlow()

    var rounds: Int = 0
        private set
    var releases: Int = 0
        private set
    val languages = mutableListOf<String>()
    val engines = mutableListOf<VoiceEngine>()
    val checks = mutableListOf<Pair<String, VoiceEngine>>()
    val downloads = mutableListOf<String>()

    /** The next (or the open) round hears [alternatives]. */
    fun hear(vararg alternatives: String) {
        answers.trySend(VoiceResult.Heard(alternatives.toList()))
    }

    /** The next round ends in silence. */
    fun hearNothing() {
        answers.trySend(VoiceResult.NothingHeard)
    }

    /** The next round fails with [reason]. */
    fun fail(reason: VoiceFailure) {
        answers.trySend(VoiceResult.Failed(reason))
    }

    /** Ends the held download with [result]. */
    fun finishDownload(result: VoiceDownload = downloadResult) {
        pendingDownload?.complete(result)
    }

    override fun hasPermission(): Boolean = permission

    override suspend fun check(languageTag: String, engine: VoiceEngine): VoiceCheck {
        checks += languageTag to engine
        val language = languageTag.substringBefore('-').substringBefore('_').lowercase()
        val answer = when (engine) {
            VoiceEngine.SPEECH_SERVICE -> speechServiceAvailability ?: availabilityByLanguage[language] ?: availability
            VoiceEngine.ON_DEVICE -> availabilityByLanguage[language] ?: availability
        }
        return VoiceCheck(answer, fullTag(languageTag))
    }

    override suspend fun downloadLanguage(languageTag: String, engine: VoiceEngine): VoiceDownload {
        downloads += languageTag
        if (!holdDownload) return downloadResult
        val done = CompletableDeferred<VoiceDownload>().also { pendingDownload = it }
        try {
            return done.await()
        } finally {
            pendingDownload = null
        }
    }

    override suspend fun listenOnce(languageTag: String, engine: VoiceEngine): VoiceResult {
        rounds++
        languages += languageTag
        engines += engine
        open.value = true
        try {
            return answers.receive()
        } finally {
            open.value = false
        }
    }

    override fun release() {
        releases++
    }

    private fun fullTag(tag: String): String = when {
        tag.contains('-') -> tag
        tag == "sv" -> "sv-SE"
        tag == "en" -> "en-US"
        else -> "$tag-${tag.uppercase()}"
    }
}

/**
 * A [StepSpeaker] that records what it was asked to read. With [holdSpeech] every [speak] waits
 * until [finishSpeaking]; otherwise it returns [result] at once.
 */
class FakeStepSpeaker(var result: SpeakResult = SpeakResult.DONE, var holdSpeech: Boolean = false) : StepSpeaker {
    val spoken = mutableListOf<Pair<String, String>>()

    /** The reading-aloud volume is 0 (B11); [raiseVolume] lifts it. */
    val silent = MutableStateFlow(false)

    /** How often [raiseVolume] was called. */
    var raises: Int = 0
        private set
    private var pending: CompletableDeferred<SpeakResult>? = null
    private val speakingNow = MutableStateFlow(false)

    /** A text is being read now (the recognizer must not listen meanwhile). */
    val speaking: StateFlow<Boolean> = speakingNow.asStateFlow()

    var releases: Int = 0
        private set

    /** Ends the held [speak]. */
    fun finishSpeaking() {
        pending?.complete(result)
    }

    override suspend fun speak(text: String, languageTag: String): SpeakResult {
        spoken += text to languageTag
        if (!holdSpeech) return result
        val done = CompletableDeferred<SpeakResult>().also { pending = it }
        speakingNow.value = true
        try {
            return done.await()
        } finally {
            speakingNow.value = false
            pending = null
        }
    }

    override fun observeSilent(): Flow<Boolean> = silent

    override fun raiseVolume() {
        raises++
        silent.value = false
    }

    override fun release() {
        releases++
    }
}
