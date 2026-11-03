package casa.moller.tandem.core.designsystem.icon

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import casa.moller.tandem.core.designsystem.R

// Icons of the recipe editor (Fas 3, redigerare lane, #22), converted like the others in
// [TandemIcons] (Material Symbols Rounded, google/material-design-icons `master` on download
// 2026-11-03; git blob hashes of the SVGs: edit 13835e4b, arrow_upward 2105d6e4, arrow_downward
// 51346a11). Declared as extensions in their own file so that lanes adding icons in parallel do
// not edit the same lines.

/** "Redigera" (a pencil). */
val TandemIcons.Edit: ImageVector
    @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_edit)

/** "Flytta upp" (a step or an ingredient one place up). */
val TandemIcons.ArrowUpward: ImageVector
    @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_arrow_upward)

/** "Flytta ned". */
val TandemIcons.ArrowDownward: ImageVector
    @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_arrow_downward)
