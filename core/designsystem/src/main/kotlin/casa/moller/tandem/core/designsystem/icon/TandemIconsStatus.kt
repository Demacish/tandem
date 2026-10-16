package casa.moller.tandem.core.designsystem.icon

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import casa.moller.tandem.core.designsystem.R

// Icons of the Today tab, the first sync, the server status UI and the recovery screen (Phase 1,
// idag-ui lane), converted like the others in [TandemIcons] (Material Symbols Rounded,
// google/material-design-icons at the pinned commit in assets/licenses/NOTICE.txt, git blob
// hashes checked on download 2026-10-11). Declared as extensions in their own file so that lanes
// adding icons in parallel do not edit the same lines; use them like the members:
// `TandemIcons.Share` (import casa.moller.tandem.core.designsystem.icon.Share).

/** Battery optimisation tip (Samsung, plan 6.2 "Efter inloggning"). */
val TandemIcons.BatteryAlert: ImageVector
    @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_battery_alert)

/** "Open" affordance at the end of a row (auto-mirrored for RTL). */
val TandemIcons.ChevronRight: ImageVector
    @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_chevron_right)

/** Delete / clear data. */
val TandemIcons.Delete: ImageVector
    @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_delete)

/** A meal: placeholder for recipes without a photo; also the food filter chip of the recipe list. */
val TandemIcons.Restaurant: ImageVector
    @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_restaurant)

/** Share (share sheet). */
val TandemIcons.Share: ImageVector
    @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_share)

/** Sync with the server. */
val TandemIcons.Sync: ImageVector
    @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_sync)
