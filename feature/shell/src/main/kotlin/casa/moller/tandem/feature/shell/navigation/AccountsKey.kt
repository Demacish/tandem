package casa.moller.tandem.feature.shell.navigation

import kotlinx.serialization.Serializable

// Phase 2 (konto lane): Mer → Konton och servrar (decision B14, plan 6.2 "Konto/server", Stitch
// konto_servrar). A More page: on wide windows the detail pane beside the More list.

/** Mer → Konton och servrar: the active account, the other stored ones, add, switch, remove, log out. */
@Serializable
data object AccountsKey : ShellKey
