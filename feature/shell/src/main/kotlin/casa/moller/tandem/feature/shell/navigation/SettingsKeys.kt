package casa.moller.tandem.feature.shell.navigation

import kotlinx.serialization.Serializable

// Phase 1b (grund lane): the settings sub-screens under "Mer" (plan 6.2 "Inställningar",
// docs/design/stitch-export/mer_inst_llningar*, k_kets_beh_righeter_systemguide). Each is a
// ShellKey on the More tab's stack; the shell maps it to its screen.

/** Mer → Utseende: theme, dynamic colour, cook-mode text size, cook mode always dark. */
@Serializable
data object AppearanceSettingsKey : ShellKey

/** Mer → Kokläge och timers ("Beteende"): in-app timers or the clock app, alarm warnings. */
@Serializable
data object CookingSettingsKey : ShellKey

/** Mer → Synk och lagring: interval, "download all recipes", storage used, "Sync now". */
@Serializable
data object SyncSettingsKey : ShellKey

/**
 * Mer → Behörigheter ("Kökets behörigheter"): an OVERVIEW of what the app may do (notifications,
 * exact alarms, full-screen alarm) with links to the system settings. Never a forced step and
 * never raw permission names; permissions are asked in context (docs/design/README.md).
 */
@Serializable
data object PermissionsKey : ShellKey

/** Mer → Diagnostik: versions, cold start and full sync timings, "Exportera logg". */
@Serializable
data object DiagnosticsKey : ShellKey
