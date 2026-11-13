package casa.moller.tandem.core.designsystem.icon

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import casa.moller.tandem.core.designsystem.R

// Fas 5 (wear lane, 0.8.0): Tandem on a Wear OS watch (plan §7 #33). Own file, so lanes adding
// icons in parallel never edit the same lines.

/** A wrist watch: Mer → Klockan. */
val TandemIcons.Watch: ImageVector
    @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_watch)
