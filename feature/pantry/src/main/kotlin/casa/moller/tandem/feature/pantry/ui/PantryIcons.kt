package casa.moller.tandem.feature.pantry.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import casa.moller.tandem.feature.pantry.R

// Fas S (hemma-ui lane): the icons only "Hemma" uses, converted like core:designsystem's (Material
// Symbols Rounded, google/material-design-icons at the commit pinned in core:designsystem's
// assets/licenses/NOTICE.txt, git blob hashes checked on download 2026-10-29). Kept in this module
// so the parallel lanes never add the same name to TandemIcons.

/** Icons of feature:pantry. */
internal object PantryIcons {
    /** "Hemma", a storage place. */
    val Kitchen: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.pantry_ic_kitchen)

    /** "Skanna streckkod". */
    val BarcodeScanner: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.pantry_ic_barcode_scanner)

    /** "Fota kvittot". */
    val Receipt: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.pantry_ic_receipt_long)

    /** "Foto av kylen". */
    val Camera: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.pantry_ic_photo_camera)

    /** "Flytta" to another place. Auto-mirrored. */
    val Move: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.pantry_ic_swap_horiz)

    /** "Ändra". */
    val Edit: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.pantry_ic_edit)

    /** A best-before day ("Välj datum"). */
    val Event: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.pantry_ic_event)

    /** "Överraska mig". */
    val Surprise: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.pantry_ic_casino)

    /** "Packa upp". */
    val Unpack: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.pantry_ic_inventory_2)
}
