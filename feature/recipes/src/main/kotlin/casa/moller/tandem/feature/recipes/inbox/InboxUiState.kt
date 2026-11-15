package casa.moller.tandem.feature.recipes.inbox

import androidx.annotation.StringRes
import casa.moller.tandem.core.model.inbox.InboxAvailability
import casa.moller.tandem.core.model.inbox.InboxEntry
import casa.moller.tandem.core.model.inbox.InboxProblem
import casa.moller.tandem.feature.recipes.R
import casa.moller.tandem.feature.recipes.navigation.RecipeImportKey
import kotlin.time.Instant

// Fas 5 (rss lane, plan §7 #35): the recipe inbox's screen state, actions and effects.

/**
 * What [InboxScreen] shows.
 *
 * @property inUse the inbox has posts to show (set up for the account, or demo mode's built-in one).
 * @property preview the post whose preview is open (tap 1), null when none.
 * @property readOnly a guest: may read, not import (importing writes a recipe).
 */
data class InboxUiState(
    val loading: Boolean = true,
    val availability: InboxAvailability = InboxAvailability.LOADING,
    val inUse: Boolean = false,
    val entries: List<InboxEntry> = emptyList(),
    val refreshing: Boolean = false,
    val problem: InboxProblem? = null,
    val lastRefresh: Instant? = null,
    val readOnly: Boolean = false,
    val preview: InboxEntry? = null,
) {
    val isDemo: Boolean
        get() = availability == InboxAvailability.DEMO

    /** "Uppdaterad …" under the posts: the last refresh worked and there is something to show. */
    val updated: Instant?
        get() = lastRefresh.takeIf { !isDemo && problem == null && entries.isNotEmpty() }

    /** Posts not imported yet (the count beside the title and on Recept's button). */
    val newCount: Int
        get() = entries.count { !it.imported }
}

sealed interface InboxAction {
    data object Refresh : InboxAction

    /** Tap 1: the post's preview. */
    data class Open(val id: Long) : InboxAction

    data object ClosePreview : InboxAction

    /** Tap 2: the import review with the post's address. */
    data class Import(val id: Long) : InboxAction

    /** "Markera som läst" without importing. */
    data class Dismiss(val id: Long) : InboxAction

    data class OpenInBrowser(val id: Long) : InboxAction
}

sealed interface InboxEffect {
    /** Open the import review (the shell navigates). */
    data class OpenImport(val key: RecipeImportKey) : InboxEffect

    data class OpenUrl(val url: String) : InboxEffect

    data class Message(@StringRes val text: Int) : InboxEffect
}

/** The text the user is told for [this] problem. */
@get:StringRes
val InboxProblem.text: Int
    get() = when (this) {
        InboxProblem.INVALID_URL -> R.string.inbox_problem_invalid_url
        InboxProblem.AUTH -> R.string.inbox_problem_auth
        InboxProblem.NOT_MINIFLUX -> R.string.inbox_problem_not_miniflux
        InboxProblem.UNREACHABLE -> R.string.inbox_problem_unreachable
        InboxProblem.TLS -> R.string.inbox_problem_tls
        InboxProblem.THROTTLED -> R.string.inbox_problem_throttled
        InboxProblem.SERVER -> R.string.inbox_problem_server
        InboxProblem.PAUSED -> R.string.inbox_problem_paused
        InboxProblem.OTHER -> R.string.inbox_problem_other
    }
