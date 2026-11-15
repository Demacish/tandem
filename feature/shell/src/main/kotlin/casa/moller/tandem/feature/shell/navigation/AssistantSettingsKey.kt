package casa.moller.tandem.feature.shell.navigation

import kotlinx.serialization.Serializable

// Fas 5 (funktioner lane, plan §8.7): Mer → Assistenten. On wide windows it opens in the detail
// pane like the other Mer pages.

/** Mer → Assistenten (the App Functions an assistant on the phone may use). */
@Serializable
data object AssistantSettingsKey : ShellKey
