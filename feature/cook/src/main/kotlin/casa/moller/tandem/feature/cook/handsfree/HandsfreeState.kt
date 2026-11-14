package casa.moller.tandem.feature.cook.handsfree

import androidx.compose.runtime.Immutable
import casa.moller.tandem.core.domain.voice.VoiceCommand
import casa.moller.tandem.core.domain.voice.VoiceLocales
import casa.moller.tandem.core.model.settings.HandsfreeSettings

/**
 * Where the voice part of handsfree is (plan §8.1 "Handsfree", #32), shown by [HandsfreeBar].
 *
 * - [OFF]: the voice is not on (handsfree may still be on: the volume keys).
 * - [STARTING]: checking the microphone and the phone's recognition.
 * - [LISTENING]: the microphone is open for "nästa", "tillbaka" or "upprepa".
 * - [SPEAKING]: a step is read aloud (the microphone is closed meanwhile, so the phone never hears itself).
 * - [NEEDS_PERMISSION]: RECORD_AUDIO is not granted; "Tillåt" asks again.
 * - [DOWNLOADABLE]: the phone can download the language for the recognizer; "Hämta" (B2).
 * - [DOWNLOADING]: the download runs; listening starts when it is done.
 * - [DOWNLOAD_LATER]: the phone downloads the language later (for example on Wi-Fi); "Försök igen".
 * - [UNAVAILABLE]: the recognizer has no such language; the volume keys still work.
 * - [OFFER_ENGLISH]: the phone cannot recognise the UI's language on the device but may English:
 *   "Använd engelska" (B3).
 * - [NO_SPEECH_SERVICE]: "Använd telefonens taltjänst" is on but the phone has no speech service (B3).
 * - [RETRYING]: a round failed; listening starts again after a pause (back-off).
 * - [FAILED]: several rounds in a row failed; "Försök igen".
 */
enum class VoiceStatus {
    OFF,
    STARTING,
    LISTENING,
    SPEAKING,
    NEEDS_PERMISSION,
    DOWNLOADABLE,
    DOWNLOADING,
    DOWNLOAD_LATER,
    UNAVAILABLE,
    OFFER_ENGLISH,
    NO_SPEECH_SERVICE,
    RETRYING,
    FAILED,
}

/**
 * Cook mode's handsfree control, built by [HandsfreeViewModel].
 *
 * @property settings the remembered choice (volume keys, voice, read every step).
 * @property voice what the voice part does now ([VoiceStatus]).
 * @property languageTag the UI's language ("sv", "en-GB").
 * @property voiceLanguageTag the full tag the voice listens in or checked last ("sv-SE", "en-US"
 *   for the English commands); empty while the voice is off.
 * @property heard the command just heard, shown for a moment ("Hörde ”nästa”").
 * @property speechUnavailable the phone has no text-to-speech voice for the step's language.
 * @property permissionDenied the system dialog for the microphone was answered "Don't allow" in
 *   this cook mode: "Tillåt" opens the app's settings instead (the dialog may not come again).
 * @property sheetOpen the handsfree sheet is open.
 * @property downloadFailed the last download of the language failed (B2).
 * @property volumeSilent reading aloud is wanted but its volume is 0 (B11): "Höj volymen".
 */
@Immutable
data class HandsfreeUiState(
    val settings: HandsfreeSettings = HandsfreeSettings(),
    val voice: VoiceStatus = VoiceStatus.OFF,
    val languageTag: String = "",
    val voiceLanguageTag: String = "",
    val heard: VoiceCommand? = null,
    val speechUnavailable: Boolean = false,
    val permissionDenied: Boolean = false,
    val sheetOpen: Boolean = false,
    val downloadFailed: Boolean = false,
    val volumeSilent: Boolean = false,
) {
    /** Handsfree is on: the volume keys turn the steps. */
    val enabled: Boolean
        get() = settings.enabled

    /** The UI is in English (no English commands to offer). */
    val uiInEnglish: Boolean
        get() = VoiceLocales.isEnglish(languageTag)

    /** The voice listens for the English words ("next", "back", "repeat") although the UI is not English (B3). */
    val commandsInEnglish: Boolean
        get() = settings.englishCommands && !settings.speechService && !uiInEnglish

    /** The language the voice is about (for the texts): the one listened in, else the UI's. */
    val voiceLanguage: String
        get() = voiceLanguageTag.ifEmpty { languageTag }
}

/** What the handsfree control can do. */
sealed interface HandsfreeAction {
    data object OpenSheet : HandsfreeAction

    data object CloseSheet : HandsfreeAction

    /** The master switch: the volume keys (and, with them, the voice and reading aloud). */
    data class SetEnabled(val enabled: Boolean) : HandsfreeAction

    /** Voice commands; turning them on asks for the microphone in context when needed. */
    data class SetVoice(val enabled: Boolean) : HandsfreeAction

    /** Read every new step aloud. */
    data class SetReadSteps(val enabled: Boolean) : HandsfreeAction

    /** The answer of the system's microphone dialog. */
    data class PermissionResult(val granted: Boolean) : HandsfreeAction

    /** "Tillåt" on the bar: the dialog again, or the app's settings after a denial. */
    data object AllowMicrophone : HandsfreeAction

    /** "Hämta": the system downloads the language for the recognizer, then the voice listens (B2). */
    data object DownloadLanguage : HandsfreeAction

    /** B3: listen with the phone's regular speech service ("Använd telefonens taltjänst", opt-in). */
    data class SetSpeechService(val enabled: Boolean) : HandsfreeAction

    /** B3: listen for the English commands on the device ("Använd engelska", or the sheet's switch). */
    data class SetEnglishCommands(val enabled: Boolean) : HandsfreeAction

    /** B11: "Höj volymen", one step up with the system's volume panel. */
    data object RaiseVolume : HandsfreeAction

    /** "Försök igen" after the voice failed, or back from the system settings: check everything again. */
    data object Retry : HandsfreeAction

    /** Cook mode is in front (resumed) or not (paused, screen off, left): listen only while it is. */
    data class SetVisible(val visible: Boolean) : HandsfreeAction

    /** The UI's language, which the recognizer listens in. */
    data class SetLanguage(val languageTag: String) : HandsfreeAction

    /**
     * The page cook mode shows now ([key] = recipe and page) and what reading it aloud says. A new
     * key is read aloud when "read every step" is on.
     */
    data class StepShown(val key: String, val speech: String) : HandsfreeAction

    /** "Läs upp" (and the voice's "upprepa"): read the current page aloud now. */
    data object ReadAloud : HandsfreeAction
}

/** One-off events of the handsfree control. */
sealed interface HandsfreeEffect {
    /** A voice command for the shown recipe: [VoiceCommand.NEXT] or [VoiceCommand.PREVIOUS]. */
    data class Command(val command: VoiceCommand) : HandsfreeEffect

    /** Show the system's microphone dialog (the user just turned the voice on). */
    data object RequestMicrophonePermission : HandsfreeEffect

    /** The app's system settings page (the microphone was denied before). */
    data object OpenAppSettings : HandsfreeEffect
}
