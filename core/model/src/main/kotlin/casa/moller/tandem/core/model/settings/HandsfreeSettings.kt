package casa.moller.tandem.core.model.settings

/**
 * Fas 5 (kok lane, plan §8.1 "Handsfree", #32): cook mode's handsfree control, chosen in cook mode
 * and remembered for the next time. Off until the user turns it on.
 *
 * @property enabled handsfree is on: the volume keys turn the steps while cook mode is in front
 *   (the robust part, no microphone).
 * @property voice also listen for "nästa", "tillbaka" and "upprepa" while cook mode is visible,
 *   with the phone's ON-DEVICE speech recognition unless [speechService] is on.
 * @property readSteps read every new step aloud (text to speech); "upprepa" reads it whatever this says.
 * @property speechService owner's decision 2026-11-14 (device test B3), opt-in, off by default:
 *   listen with the phone's regular speech service (for example Google's) in the app's language,
 *   offline when it can be; speech may go over the internet to that service when it has no
 *   offline language pack.
 * @property englishCommands device test B3: the phone cannot recognise the app's language on the
 *   device, so the on-device recognizer listens for the English commands ("next", "back",
 *   "repeat") instead. Chosen in cook mode; off by default.
 */
data class HandsfreeSettings(
    val enabled: Boolean = false,
    val voice: Boolean = false,
    val readSteps: Boolean = false,
    val speechService: Boolean = false,
    val englishCommands: Boolean = false,
) {
    /** The microphone is wanted: handsfree on with the voice. */
    val listens: Boolean
        get() = enabled && voice
}
