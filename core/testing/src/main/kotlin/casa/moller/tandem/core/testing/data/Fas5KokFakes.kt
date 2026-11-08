package casa.moller.tandem.core.testing.data

import casa.moller.tandem.core.data.recipe.PanSizeRepository
import casa.moller.tandem.core.data.voice.SpeakResult
import casa.moller.tandem.core.data.voice.StepSpeaker
import casa.moller.tandem.core.data.voice.VoiceAvailability
import casa.moller.tandem.core.data.voice.VoiceCommandRecognizer
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
 * microphone would be on), [rounds] counts them and [languages] records their language.
 */
class FakeVoiceCommandRecognizer(
    var permission: Boolean = true,
    var availability: VoiceAvailability = VoiceAvailability.AVAILABLE,
) : VoiceCommandRecognizer {
    private val answers = Channel<VoiceResult>(Channel.UNLIMITED)
    private val open = MutableStateFlow(false)

    /** A round of listening is open (the microphone would be on). */
    val listening: StateFlow<Boolean> = open.asStateFlow()

    var rounds: Int = 0
        private set
    var releases: Int = 0
        private set
    val languages = mutableListOf<String>()
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

    override fun hasPermission(): Boolean = permission

    override suspend fun availability(languageTag: String): VoiceAvailability = availability

    override fun downloadLanguage(languageTag: String) {
        downloads += languageTag
    }

    override suspend fun listenOnce(languageTag: String): VoiceResult {
        rounds++
        languages += languageTag
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
}

/**
 * A [StepSpeaker] that records what it was asked to read. With [holdSpeech] every [speak] waits
 * until [finishSpeaking]; otherwise it returns [result] at once.
 */
class FakeStepSpeaker(var result: SpeakResult = SpeakResult.DONE, var holdSpeech: Boolean = false) : StepSpeaker {
    val spoken = mutableListOf<Pair<String, String>>()
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

    override fun release() {
        releases++
    }
}
