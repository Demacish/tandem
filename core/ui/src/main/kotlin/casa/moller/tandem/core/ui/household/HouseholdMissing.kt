package casa.moller.tandem.core.ui.household

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import casa.moller.tandem.core.designsystem.component.InfoCard
import casa.moller.tandem.core.designsystem.component.TandemTone
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.ui.R

/**
 * 0.3.0 review (plan 6.2 "kontroll av hushåll", 8.6): the account is in no household and the space
 * has not finished its household setup (`SpacePermissions.householdMissing`), so Tandoor shares
 * neither the shopping list nor the meal plan with the other members. Shown on Handla, Veckan and
 * the Mer account card; says what to do on the web (no address: a generic Tandoor client).
 */
@Composable
fun HouseholdMissingCard(modifier: Modifier = Modifier) {
    InfoCard(
        title = stringResource(R.string.ui_household_missing_title),
        body = stringResource(R.string.ui_household_missing_body),
        modifier = modifier,
        icon = TandemIcons.Warning,
        tone = TandemTone.Warning,
    )
}
