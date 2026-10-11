package casa.moller.tandem.core.designsystem.icon

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import casa.moller.tandem.core.designsystem.R

// Icons of the recipe screens (Phase 1, feature:recipes), converted like the others in
// [TandemIcons] (Material Symbols Rounded, google/material-design-icons at the pinned commit in
// assets/licenses/NOTICE.txt, git blob hashes checked on download 2026-10-11). Declared as
// extensions in their own file so that lanes adding icons in parallel do not edit the same lines;
// use them like the members: `TandemIcons.Search` (import casa.moller.tandem.core.designsystem.icon.Search).

/** Increase (servings stepper). */
val TandemIcons.Add: ImageVector
    @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_add)

/** Selected / done (filter chips). */
val TandemIcons.Check: ImageVector
    @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_check)

/** On the server only (not downloaded). */
val TandemIcons.Cloud: ImageVector
    @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_cloud)

/** "Download for offline use". */
val TandemIcons.DownloadForOffline: ImageVector
    @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_download_for_offline)

/** Available offline (pinned). Filled variant of [DownloadForOffline]. */
val TandemIcons.DownloadForOfflineFilled: ImageVector
    @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_download_for_offline_filled)

/** Opens a menu or a picker. */
val TandemIcons.ExpandMore: ImageVector
    @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_expand_more)

/** Recipe list as a grid. */
val TandemIcons.GridView: ImageVector
    @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_grid_view)

/** Cook history ("cooked 12 times, last ..."). */
val TandemIcons.History: ImageVector
    @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_history)

/** A web link (recipe source). */
val TandemIcons.Link: ImageVector
    @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_link)

/** Overflow menu. */
val TandemIcons.MoreVert: ImageVector
    @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_more_vert)

/** Decrease (servings stepper). */
val TandemIcons.Remove: ImageVector
    @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_remove)

/** Search. */
val TandemIcons.Search: ImageVector
    @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_search)

/** Keywords (tags). */
val TandemIcons.Sell: ImageVector
    @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_sell)

/** Sort order (auto-mirrored). */
val TandemIcons.Sort: ImageVector
    @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_sort)

/** Rating: an empty star. */
val TandemIcons.Star: ImageVector
    @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_star)

/** Rating: a full star. */
val TandemIcons.StarFilled: ImageVector
    @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_star_filled)

/** Rating: a half star (auto-mirrored). */
val TandemIcons.StarHalfFilled: ImageVector
    @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_star_half_filled)

/** Filters. */
val TandemIcons.Tune: ImageVector
    @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_tune)

/** Recipe list as a list. */
val TandemIcons.ViewAgenda: ImageVector
    @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_view_agenda)
