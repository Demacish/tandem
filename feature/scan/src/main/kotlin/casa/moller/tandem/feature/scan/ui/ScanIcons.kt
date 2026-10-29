package casa.moller.tandem.feature.scan.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import casa.moller.tandem.feature.scan.R

// Fas S (skanning lane): the icons only the scanners use, converted like core:designsystem's
// (Material Symbols Rounded, google/material-design-icons at the commit pinned in core:designsystem's
// assets/licenses/NOTICE.txt, git blob hashes checked on download 2026-10-29). Kept in this module so
// the parallel lanes never add the same name to TandemIcons.

/** Icons of feature:scan. */
internal object ScanIcons {
    val Barcode: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.scan_ic_barcode_scanner)

    val Receipt: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.scan_ic_receipt_long)

    /** "Välj bild från galleriet". */
    val Gallery: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.scan_ic_photo_library)

    val TorchOn: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.scan_ic_flashlight_on)

    val TorchOff: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.scan_ic_flashlight_off)

    val Edit: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.scan_ic_edit)

    val Camera: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.scan_ic_photo_camera)

    /** "Ta ett foto till" (a long receipt in several photos). */
    val AddPhoto: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.scan_ic_add_a_photo)
}
