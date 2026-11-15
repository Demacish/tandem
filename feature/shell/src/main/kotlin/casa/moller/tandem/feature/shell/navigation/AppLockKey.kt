package casa.moller.tandem.feature.shell.navigation

import kotlinx.serialization.Serializable

// Fas 5 (las lane, plan §5.5): Mer → Säkerhet → Lås appen. On wide windows it opens in the detail
// pane like the other Mer pages.

/** Mer → Lås appen (the phone's screen lock in front of the app). */
@Serializable
data object AppLockKey : ShellKey
