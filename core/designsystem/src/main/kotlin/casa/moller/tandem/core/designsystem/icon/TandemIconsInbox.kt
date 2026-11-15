package casa.moller.tandem.core.designsystem.icon

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import casa.moller.tandem.core.designsystem.R

// Fas 5 (rss lane, 0.9.0): the recipe inbox (plan §7 #35), converted like the others in [TandemIcons]
// (Material Symbols Rounded, google/material-design-icons at the pinned commit in
// assets/licenses/NOTICE.txt, git blob hashes checked on download 2026-11-15). Extensions in their own
// file so that lanes adding icons in parallel do not edit the same lines.

/** "Inkorg för recept". */
val TandemIcons.Inbox: ImageVector
    @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_inbox)

/** "Markera som läst", and an inbox with nothing new. */
val TandemIcons.DoneAll: ImageVector
    @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_done_all)
