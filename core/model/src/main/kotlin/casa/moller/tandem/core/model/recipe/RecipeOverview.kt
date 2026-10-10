package casa.moller.tandem.core.model.recipe

import kotlin.time.Instant

/**
 * A recipe as shown in lists, search results and the Today carousels (plan 3.2, Tandoor
 * `RecipeOverview` / `RecipeFlat`). Comes from Room; the index (`recipe/flat`) only fills [id],
 * [name] and [imageUrl], the recipe list endpoint the rest.
 *
 * @property id Tandoor recipe id (server id; recipes are never created offline in Phase 1).
 * @property name recipe name as entered on the server, shown as is (never translated).
 * @property description short description, if any.
 * @property imageUrl absolute image URL (Tandoor `/media/...`, immutable per file name), or null.
 * @property workingMinutes active time in minutes (`working_time`), 0 when unknown.
 * @property waitingMinutes passive time in minutes (`waiting_time`), 0 when unknown.
 * @property rating the user's rating 0..5 (may have decimals), null when never rated.
 * @property lastCooked when the recipe was last cooked (cook log), null when never.
 * @property timesCooked how often it was cooked, when known (only server sorts deliver it).
 * @property keywords the recipe's keywords (labels only in list responses).
 * @property isNew Tandoor's `new` flag (recently added, controlled by the space's `num_recent`).
 * @property isPrivate Tandoor's `private` flag (visible only to the creator and shared users).
 * @property updatedAt server `updated_at`. Unreliable for change detection (plan 3.5: step and
 *   ingredient edits do not bump it); used for sorting and as a hint only.
 * @property availableOffline the detail (and image) is stored on the device, so the recipe can be
 *   opened without a connection. Lists show other recipes dimmed with a cloud icon when offline.
 * @property pinned the user chose "Download for offline use": the detail is kept and refreshed
 *   every 24 h (plan 3.5) and never evicted.
 */
data class RecipeOverview(
    val id: Long,
    val name: String,
    val description: String? = null,
    val imageUrl: String? = null,
    val workingMinutes: Int = 0,
    val waitingMinutes: Int = 0,
    val rating: Double? = null,
    val lastCooked: Instant? = null,
    val timesCooked: Int? = null,
    val keywords: List<Keyword> = emptyList(),
    val isNew: Boolean = false,
    val isPrivate: Boolean = false,
    val updatedAt: Instant? = null,
    val availableOffline: Boolean = false,
    val pinned: Boolean = false,
) {
    /** Working plus waiting time in minutes; 0 when neither is known. */
    val totalMinutes: Int
        get() = workingMinutes + waitingMinutes
}

/**
 * A Tandoor keyword (tag). Keywords form a tree on the server ([parentId]); the app shows them
 * flat. Names come from the user's server and are shown as they are.
 *
 * @property id Tandoor keyword id.
 * @property name the keyword name (`name`, or `label` in list responses).
 * @property description optional description.
 * @property parentId id of the parent keyword, null for a root keyword or when unknown.
 */
data class Keyword(val id: Long, val name: String, val description: String = "", val parentId: Long? = null)
