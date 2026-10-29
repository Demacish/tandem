package casa.moller.tandem.feature.pantry

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation3.runtime.NavKey
import casa.moller.tandem.core.ui.placeholder.PlaceholderPage
import casa.moller.tandem.feature.pantry.navigation.MakeNowKey
import casa.moller.tandem.feature.pantry.navigation.PantryDestination
import casa.moller.tandem.feature.pantry.navigation.PantryEntryKey
import casa.moller.tandem.feature.pantry.navigation.PantryKey
import casa.moller.tandem.feature.pantry.navigation.PantryLocationKey
import casa.moller.tandem.feature.pantry.navigation.StaplesKey
import casa.moller.tandem.feature.pantry.navigation.UnpackKey

// Fas S (grund lane): the ONE route feature:shell shows for every [PantryDestination]. The lanes
// (hemma-ui) replace each branch's placeholder with the real screen; the callbacks are the shell's
// navigation, so this module never knows other features. A callback the screens need later (the
// shopping sheet for "Lägg det som saknas på listan", the scanner) is added here and in the shell.

/**
 * @param onNavigate opens another destination (one of this module's keys, or a scan key from the
 *   shell's mapping).
 * @param onOpenRecipe a recipe's detail (feature:recipes, mapped by the shell).
 * @param onBack null on a tab root (no back arrow).
 *
 * `onNavigate` and `onOpenRecipe` are unused until the lanes build the screens (placeholders now).
 */
@Suppress("UnusedParameter")
@Composable
fun PantryRoute(
    key: PantryDestination,
    onNavigate: (NavKey) -> Unit,
    onOpenRecipe: (Long) -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val title = when (key) {
        PantryKey -> stringResource(R.string.pantry_title)
        is PantryLocationKey -> stringResource(R.string.pantry_location_title)
        is PantryEntryKey -> stringResource(
            if (key.entryId == null) R.string.pantry_entry_new_title else R.string.pantry_entry_edit_title,
        )
        UnpackKey -> stringResource(R.string.pantry_unpack_title)
        is MakeNowKey -> stringResource(R.string.pantry_make_now_title)
        StaplesKey -> stringResource(R.string.pantry_staples_title)
    }
    PlaceholderPage(title = title, onNavigateBack = onBack, modifier = modifier)
}
