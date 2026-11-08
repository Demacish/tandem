package casa.moller.tandem.feature.cook.handsfree

import androidx.compose.runtime.Immutable
import casa.moller.tandem.core.domain.voice.VoiceCommand
import casa.moller.tandem.core.model.settings.HandsfreeSettings

/**
 * Where the voice part of handsfree is (plan §8.1 "Handsfree", #32), shown by [HandsfreeBar].
 *
 * - [OFF]: the voice is not on (handsfree may still be on: the volume keys).
 * - [STARTING]: checking the microphone and the phone's on-device recognition.
 * - [LISTENING]: the microphone is open for "nästa", "tillbaka" or "upprepa".
 * - [SPEAKING]: a step is read aloud (the microphone is closed meanwhile, so the phone never hears itself).
 * - [NEEDS_PERMISSION]: RECORD_AUDIO is not granted; "Tillåt" asks again.
 * - [DOWNLOADABLE]: the phone can download the language for on-device recognition.
 * - [UNAVAILABLE]: no on-device recognition for the language; the volume keys still work.
 * - [RETRYING]: a round failed; listening starts again after a pause (back-off).
 * - [FAILED]: several rounds in a row failed; "Försök igen".
 */
enum class VoiceStatus { OFF, STARTING, LISTENING, SPEAKING, NEEDS_PERMISSION, DOWNLOADABLE, UNAVAILABLE, RETRYING, FAILED }

/**
 * Cook mode's handsfree control, built by [HandsfreeViewModel].
 *
 * @property settings the remembered choice (volume keys, voice, read every step).
 * @property voice what the voice part does now ([VoiceStatus]).
 * @property languageTag the language listened in (the UI's, e.g. "sv-SE").
 * @property heard the command just heard, shown for a moment ("Hörde ”nästa”").
 * @property speechUnavailable the phone has no text-to-speech voice for the step's language.
 * @property permissionDenied the system dialog for the microphone was answered "Don't allow" in
 *   this cook mode: "Tillåt" opens the app's settings instead (the dialog may not come again).
 * @property sheetOpen the handsfree sheet is open.
 */
@Immutable
data class HandsfreeUiState(
    val settings: HandsfreeSettings = HandsfreeSettings(),
    val voice: VoiceStatus = VoiceStatus.OFF,
    val languageTag: String = "",
    val heard: VoiceCommand? = null,
    val speechUnavailable: Boolean = false,
    val permissionDenied: Boolean = false,
    val sheetOpen: Boolean = false,
) {
    /** Handsfree is on: the volume keys turn the steps. */
    val enabled: Boolean
        get() = settings.enabled
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

    /** "Hämta språket": the system downloads the language for on-device recognition. */
    data object DownloadLanguage : HandsfreeAction

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
