package casa.moller.tandem.core.data.voice

/**
 * Fas 5 (kok lane, plan §8.1 "Handsfree", #32): what cook mode's handsfree control needs from the
 * device, implemented in core:timer (`voice/`) with Android's speech services and bound by Hilt.
 * Features see only these interfaces, so the ViewModel is tested with fakes and no microphone.
 *
 * Privacy (plan §8.1, §4.5): the recognizer is ALWAYS the on-device one
 * (`SpeechRecognizer.createOnDeviceSpeechRecognizer`); audio never goes to a network recognizer.
 * Without on-device recognition for the language the answer is [VoiceAvailability.UNAVAILABLE]
 * and cook mode offers the volume keys instead.
 */
enum class VoiceAvailability {
    /** On-device recognition listens in the language now. */
    AVAILABLE,

    /** The phone can download the language for on-device recognition ([VoiceCommandRecognizer.downloadLanguage]). */
    DOWNLOADABLE,

    /** No on-device recognition for the language on this phone. */
    UNAVAILABLE,
}

/** Why one round of listening ended without words. */
enum class VoiceFailure {
    /** The recognizer is busy (another app, or the previous round not finished): try again a little later. */
    BUSY,

    /** RECORD_AUDIO is not granted (any more). */
    NO_PERMISSION,

    /** The on-device recognizer does not have the language (any more). */
    LANGUAGE_UNAVAILABLE,

    /** The microphone could not be read. */
    AUDIO,

    /** Anything else; try again after a pause. */
    OTHER,
}

/** The end of one round of listening ([VoiceCommandRecognizer.listenOnce]). */
sealed interface VoiceResult {
    /** Words were recognized, best guess first. */
    data class Heard(val alternatives: List<String>) : VoiceResult

    /** Silence or nothing the recognizer could match: listen again right away. */
    data object NothingHeard : VoiceResult

    /** It failed for [reason]. */
    data class Failed(val reason: VoiceFailure) : VoiceResult
}

/**
 * The phone's on-device speech recognizer, one utterance at a time. Main-safe.
 *
 * - [listenOnce] opens the microphone, waits for one utterance and closes it again; cancelling the
 *   calling coroutine stops listening at once (cook mode pauses, the screen goes off). No
 *   foreground service: it only runs while cook mode is visible.
 * - [release] frees the recognizer (cook mode left); a later [listenOnce] creates a new one.
 */
interface VoiceCommandRecognizer {
    /** RECORD_AUDIO is granted (asked in context when the user turns the voice on, never at start). */
    fun hasPermission(): Boolean

    /** Whether on-device recognition can listen in [languageTag] ("sv-SE", "en-GB"). */
    suspend fun availability(languageTag: String): VoiceAvailability

    /** Asks the system to download [languageTag] for on-device recognition (its own UI and progress). */
    fun downloadLanguage(languageTag: String)

    /** Listens for one utterance in [languageTag]. */
    suspend fun listenOnce(languageTag: String): VoiceResult

    /** Frees the recognizer. */
    fun release()
}

/** How reading aloud ended ([StepSpeaker.speak]). */
enum class SpeakResult {
    /** The text was read to the end. */
    DONE,

    /** The phone has no text-to-speech voice for the language (or no engine at all). */
    UNAVAILABLE,

    /** Reading failed. */
    FAILED,
}

/**
 * Text to speech for cook mode ("upprepa", and every new step when chosen). Main-safe.
 *
 * - [speak] reads [text] in [languageTag] and returns when it was read (cancelling stops it). It
 *   asks for transient audio focus that lets music duck, and gives it back afterwards.
 * - [release] shuts the engine down (cook mode left).
 */
interface StepSpeaker {
    suspend fun speak(text: String, languageTag: String): SpeakResult

    fun release()
}
