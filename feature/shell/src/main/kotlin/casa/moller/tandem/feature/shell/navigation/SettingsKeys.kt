package casa.moller.tandem.feature.shell.navigation

import androidx.navigation3.runtime.NavKey
import casa.moller.tandem.feature.recipes.navigation.BookDetailKey
import casa.moller.tandem.feature.recipes.navigation.FavoritesKey
import casa.moller.tandem.feature.recipes.navigation.WantToCookKey
import casa.moller.tandem.feature.scan.navigation.AiSettingsKey
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

/**
 * A page opened from the More list (Phase 1b): on wide windows it is a detail pane beside the
 * list, and picking another row replaces it together with whatever was opened from it (About →
 * Licenses → a licence text). The tab's first screen ([MoreKey]) is never one.
 */
internal val NavKey.isMorePage: Boolean
    get() = when (this) {
        AppearanceSettingsKey, CookingSettingsKey, SyncSettingsKey, PermissionsKey, DiagnosticsKey,
        LanguageKey, AboutKey, LicensesKey, is LicenseTextKey,
        // Phase 2 (konto lane).
        AccountsKey,
        // Fas S (grund lane): Settings → AI (feature:scan, B22).
        AiSettingsKey,
        -> true

        else -> false
    }

/**
 * Phase 2 (books lane): a page opened from the books list (one book, Favoriter, Vill laga). On wide
 * windows it is the detail pane beside the books (or beside the recipe list when it was opened from
 * the Recipes tab's collections row), and picking another one replaces it.
 */
internal val NavKey.isBookPage: Boolean
    get() = this is BookDetailKey || this == FavoritesKey || this == WantToCookKey
