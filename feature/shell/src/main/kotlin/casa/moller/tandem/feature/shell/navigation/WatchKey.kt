package casa.moller.tandem.feature.shell.navigation

import kotlinx.serialization.Serializable

// Fas 5 (wear lane, plan §7 #33): Mer → Klockan. Shown in Mer only when there is a watch (or a
// pairing to undo); on wide windows it opens in the detail pane like the other Mer pages.

/** Mer → Klockan (Tandem på en Wear OS-klocka). */
@Serializable
data object WatchKey : ShellKey
