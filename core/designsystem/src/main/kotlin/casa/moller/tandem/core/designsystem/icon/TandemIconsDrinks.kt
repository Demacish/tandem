package casa.moller.tandem.core.designsystem.icon

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import casa.moller.tandem.core.designsystem.R

// Icons of the drinks and "Passar med" (Fas S, drycker lane, #43), converted like the others in
// [TandemIcons] (Material Symbols Rounded, google/material-design-icons at the pinned commit in
// assets/licenses/NOTICE.txt, git blob hashes checked on download 2026-10-29). Declared as
// extensions in their own file so that lanes adding icons in parallel do not edit the same lines.

/** Drinks (a wine glass). */
val TandemIcons.WineBar: ImageVector
    @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_wine_bar)

/** "Ta foto" (the camera). */
val TandemIcons.PhotoCamera: ImageVector
    @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_photo_camera)

/** "Välj bild" (the system photo picker). */
val TandemIcons.Image: ImageVector
    @Composable get() = ImageVector.vectorResource(R.drawable.tandem_ic_image)
