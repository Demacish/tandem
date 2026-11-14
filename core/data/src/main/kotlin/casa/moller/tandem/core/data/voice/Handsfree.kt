package casa.moller.tandem.core.data.voice

import kotlinx.coroutines.flow.Flow

/**
 * Fas 5 (kok lane, plan §8.1 "Handsfree", #32): what cook mode's handsfree control needs from the
 * device, implemented in core:timer (`voice/`) with Android's speech services and bound by Hilt.
 * Features see only these interfaces, so the ViewModel is tested with fakes and no microphone.
 *
 * Privacy (plan §8.1, §4.5): by default the recognizer is the on-device one
 * ([VoiceEngine.ON_DEVICE], `SpeechRecognizer.createOnDeviceSpeechRecognizer`); audio never goes to
 * a network recognizer. Owner's decision 2026-11-14 (device test B3): the opt-in setting "Använd
 * telefonens taltjänst" ([VoiceEngine.SPEECH_SERVICE], off by default) uses the phone's regular
 * speech service with `EXTRA_PREFER_OFFLINE`, which may send speech over the internet to that
 * service when it has no offline language pack. Neither path logs or stores audio or what was
 * heard; only the matched command is used.
 */
enum class VoiceEngine {
    /** The phone's on-device recognizer: speech never leaves the phone (the default). */
    ON_DEVICE,

    /** The phone's regular speech service (for example Google's), offline when it can be. Opt-in. */
    SPEECH_SERVICE,
}

/** What [VoiceCommandRecognizer.check] found for a language. */
enum class VoiceAvailability {
    /** The recognizer listens in the language now. */
    AVAILABLE,

    /**
     * The recognizer did not say (empty support lists, no answer, or the speech service, which is
     * never asked): listen and let the first round tell (error 12 or 13 ends it at once).
     */
    UNKNOWN,

    /** The phone can download the language for the recognizer ([VoiceCommandRecognizer.downloadLanguage]). */
    DOWNLOADABLE,

    /** The recognizer does not have the language. */
    UNAVAILABLE,

    /** The phone has no recognizer of this kind at all (no on-device recognition, or no speech service). */
    NO_SERVICE,
}

/**
 * The answer of [VoiceCommandRecognizer.check].
 *
 * @property languageTag the full tag to listen in and download ("en-US" for the app's "en", B1).
 */
data class VoiceCheck(val availability: VoiceAvailability, val languageTag: String)

/** How [VoiceCommandRecognizer.downloadLanguage] ended. */
enum class VoiceDownload {
    /** The language is on the phone now: listen again. */
    DONE,

    /** The phone downloads it later (for example on Wi-Fi). */
    SCHEDULED,

    /** The download failed, or the recognizer cannot download. */
    FAILED,
}

/** Why one round of listening ended without words. */
enum class VoiceFailure {
    /** The recognizer is busy (another app, or the previous round not finished): try again a little later. */
    BUSY,

    /** RECORD_AUDIO is not granted (any more). */
    NO_PERMISSION,

    /** The recognizer does not support the language (`ERROR_LANGUAGE_NOT_SUPPORTED`, 12). */
    LANGUAGE_UNAVAILABLE,

    /** The recognizer supports the language but it is not downloaded yet (`ERROR_LANGUAGE_UNAVAILABLE`, 13). */
    LANGUAGE_NOT_DOWNLOADED,

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
 * The phone's speech recognizer ([VoiceEngine]), one utterance at a time. Main-safe.
 *
 * - [check] says whether [VoiceEngine] can listen in a language and which full tag to use (B1).
 * - [listenOnce] opens the microphone, waits for one utterance and closes it again; cancelling the
 *   calling coroutine stops listening at once (cook mode pauses, the screen goes off). No
 *   foreground service: it only runs while cook mode is visible.
 * - [release] frees the recognizer (cook mode left); a later [listenOnce] creates a new one.
 */
interface VoiceCommandRecognizer {
    /** RECORD_AUDIO is granted (asked in context when the user turns the voice on, never at start). */
    fun hasPermission(): Boolean

    /** Whether [engine] can listen in [languageTag] (the app's, "sv", "en", "en-GB"), and in which full tag. */
    suspend fun check(languageTag: String, engine: VoiceEngine): VoiceCheck

    /** Asks the system to download [languageTag] for [engine] (its own UI) and waits for the answer. */
    suspend fun downloadLanguage(languageTag: String, engine: VoiceEngine): VoiceDownload

    /** Listens for one utterance in [languageTag] (a tag from [check]). */
    suspend fun listenOnce(languageTag: String, engine: VoiceEngine): VoiceResult

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
 * - [observeSilent] (device test B11): whether the volume of the stream reading aloud plays on is 0
 *   (or muted), followed while collected, so cook mode can say why nothing is heard.
 * - [raiseVolume] raises that volume one step with the system's volume panel: while handsfree is
 *   on the volume keys turn the steps, so cook mode offers this instead.
 * - [release] shuts the engine down (cook mode left).
 */
interface StepSpeaker {
    suspend fun speak(text: String, languageTag: String): SpeakResult

    fun observeSilent(): Flow<Boolean>

    fun raiseVolume()

    fun release()
}
