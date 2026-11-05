package casa.moller.tandem.feature.shell.navigation

import kotlinx.serialization.Serializable

// Fas 4 (push lane): Mer → Synk och lagring → Snabb push (plan §4.3, B11; docs/PUSH.md). Opened
// from the sync page; on wide windows it replaces the sync page in the detail pane.

/** Mer → Synk och lagring → Snabb push (egen tjänst, experimentell). */
@Serializable
data object FastPushSettingsKey : ShellKey
