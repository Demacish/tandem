package casa.moller.tandem.feature.cook.handsfree

import androidx.compose.ui.input.key.Key

/** What a key does in cook mode with handsfree ([volumeKey]). */
internal enum class VolumeKey {
    /** Turn to the next page. */
    NEXT,

    /** Turn to the previous page. */
    PREVIOUS,

    /** Eaten without doing anything (the key's release, or a key held down): the volume stays. */
    SWALLOW,

    /** Not ours: the system changes the volume (handsfree off, an alarm ringing) or another handler takes it. */
    PASS,
}

/**
 * Fas 5 (plan §8.1 "Handsfree", #32): the volume keys as next/previous page while cook mode is in
 * front. Volume DOWN is "next" and volume UP "previous", like page turning in e-book readers.
 *
 * - [active] is false when handsfree is off or a timer alarm rings: the keys keep their volume
 *   (the alarm may need to be turned down).
 * - Only the first key-down turns a page; a key held down ([repeatCount] > 0) and the key-up are
 *   swallowed, so the volume does not change either.
 */
internal fun volumeKey(key: Key, down: Boolean, repeatCount: Int, active: Boolean): VolumeKey {
    if (!active || (key != Key.VolumeDown && key != Key.VolumeUp)) return VolumeKey.PASS
    if (!down || repeatCount > 0) return VolumeKey.SWALLOW
    return if (key == Key.VolumeDown) VolumeKey.NEXT else VolumeKey.PREVIOUS
}
