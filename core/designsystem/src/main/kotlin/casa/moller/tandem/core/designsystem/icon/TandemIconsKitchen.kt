package casa.moller.tandem.core.designsystem.icon

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import casa.moller.tandem.core.designsystem.R

// Fas 5 (kok lane, 0.7.0): handsfree, the kitchen display and the tin size (plan §8.1, §8.7 #41),
// converted like the others in [TandemIcons] (Material Symbols Rounded, google/material-design-icons
// at the pinned commit in assets/licenses/NOTICE.txt, git blob hashes checked on download 2026-11-08).
// Extensions in their own file so that lanes adding icons in parallel do not edit the same lines.

/** Handsfree with the voice: listening. */
val TandemIcons.Mic: ImageVector
    @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_mic)

/** Handsfree without the voice (not listening, or no on-device recognition). */
val TandemIcons.MicOff: ImageVector
    @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_mic_off)

/** Read the step aloud. Auto-mirrored. */
val TandemIcons.VolumeUp: ImageVector
    @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_volume_up)

/** The kitchen display ("Köksskärm"). */
val TandemIcons.Countertops: ImageVector
    @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_countertops)

/** A baking tin (the tin size of a recipe). */
val TandemIcons.Cake: ImageVector
    @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_cake)
