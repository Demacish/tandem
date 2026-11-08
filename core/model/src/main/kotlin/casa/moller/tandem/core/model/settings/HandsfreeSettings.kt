package casa.moller.tandem.core.model.settings

/**
 * Fas 5 (kok lane, plan §8.1 "Handsfree", #32): cook mode's handsfree control, chosen in cook mode
 * and remembered for the next time. Off until the user turns it on.
 *
 * @property enabled handsfree is on: the volume keys turn the steps while cook mode is in front
 *   (the robust part, no microphone).
 * @property voice also listen for "nästa", "tillbaka" and "upprepa" with the phone's ON-DEVICE
 *   speech recognition (never a network recognizer), while cook mode is visible.
 * @property readSteps read every new step aloud (text to speech); "upprepa" reads it whatever this says.
 */
data class HandsfreeSettings(val enabled: Boolean = false, val voice: Boolean = false, val readSteps: Boolean = false) {
    /** The microphone is wanted: handsfree on with the voice. */
    val listens: Boolean
        get() = enabled && voice
}
