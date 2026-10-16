package casa.moller.tandem.core.designsystem.icon

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import casa.moller.tandem.core.designsystem.R

// Icons of cook mode and the timer bar (Phase 1b, feature:cook and core:ui timer/), converted like
// the others in [TandemIcons] (Material Symbols Rounded, google/material-design-icons at the
// pinned commit in assets/licenses/NOTICE.txt, git blob hashes checked on download 2026-10-16).
// Extensions in their own file so that lanes adding icons in parallel do not edit the same lines.

/** A timer (chips, the timer bar). */
val TandemIcons.Timer: ImageVector
    @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_timer)

/** Pause a running timer. */
val TandemIcons.Pause: ImageVector
    @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_pause)

/** Resume a paused timer. */
val TandemIcons.PlayArrow: ImageVector
    @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_play_arrow)

/** Stop a ringing timer / cancel one. */
val TandemIcons.Stop: ImageVector
    @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_stop)

/** Cook mode's A+ (larger text). */
val TandemIcons.TextIncrease: ImageVector
    @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_text_increase)

/** Cook mode's A− (smaller text). */
val TandemIcons.TextDecrease: ImageVector
    @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_text_decrease)

/** Cook mode ("Laga"). */
val TandemIcons.Cooking: ImageVector
    @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_cooking)

/** A ringing timer. */
val TandemIcons.Alarm: ImageVector
    @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_alarm)

/** Notifications (asking for them in context). */
val TandemIcons.NotificationsActive: ImageVector
    @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_notifications_active)

/** The step's ingredients (cook mode's ingredient sheet). Auto-mirrored. */
val TandemIcons.FormatListBulleted: ImageVector
    @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_format_list_bulleted)
