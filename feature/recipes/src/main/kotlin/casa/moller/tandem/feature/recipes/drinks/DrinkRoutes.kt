package casa.moller.tandem.feature.recipes.drinks

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation3.runtime.NavKey
import casa.moller.tandem.core.ui.placeholder.PlaceholderPage
import casa.moller.tandem.feature.recipes.R
import casa.moller.tandem.feature.recipes.navigation.CreateDrinkKey
import casa.moller.tandem.feature.recipes.navigation.DrinkDestination
import casa.moller.tandem.feature.recipes.navigation.DrinkDetailKey
import casa.moller.tandem.feature.recipes.navigation.DrinksKey
import casa.moller.tandem.feature.recipes.navigation.PairingPickerKey

// Fas S (grund lane): the ONE route feature:shell shows for every [DrinkDestination]; the drycker
// lane replaces the placeholders (and adds "Passar med" to the recipe detail itself).

/**
 * @param onNavigate opens another drink destination.
 * @param onOpenRecipe a dish's detail.
 * @param onBack null on a page beside a list on wide windows.
 *
 * `onNavigate` and `onOpenRecipe` are unused until the drycker lane builds the screens.
 */
@Suppress("UnusedParameter")
@Composable
fun DrinkRoute(
    key: DrinkDestination,
    onNavigate: (NavKey) -> Unit,
    onOpenRecipe: (Long) -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val title = when (key) {
        is DrinksKey -> R.string.drinks_title
        is DrinkDetailKey -> R.string.drinks_detail_title
        is PairingPickerKey -> R.string.drinks_pairing_picker_title
        is CreateDrinkKey -> R.string.drinks_create_title
    }
    PlaceholderPage(title = stringResource(title), onNavigateBack = onBack, modifier = modifier)
}
