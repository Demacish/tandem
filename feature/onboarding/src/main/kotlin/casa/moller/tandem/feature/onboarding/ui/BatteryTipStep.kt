package casa.moller.tandem.feature.onboarding.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import casa.moller.tandem.core.designsystem.component.TandemIconHero
import casa.moller.tandem.core.designsystem.component.TandemPrimaryButton
import casa.moller.tandem.core.designsystem.component.TandemSecondaryButton
import casa.moller.tandem.core.designsystem.component.TandemTone
import casa.moller.tandem.core.designsystem.icon.BatteryAlert
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.feature.onboarding.R

/**
 * A short tip about Samsung's battery optimisation after the first sync (plan 6.2 "Efter
 * inloggning", open question 13). Shown only on Samsung devices; a link to the app's settings and
 * "Done", never a forced flow or a permission request.
 *
 * @param onOpenSettings opens the app's page in the system settings (an Intent, see OnboardingFlow).
 */
@Composable
internal fun ColumnScope.BatteryTipStep(onOpenSettings: () -> Unit, onDone: () -> Unit) {
    TandemIconHero(
        icon = TandemIcons.BatteryAlert,
        modifier = Modifier.align(Alignment.CenterHorizontally),
        tone = TandemTone.Warning,
    )
    StepTitle(text = stringResource(R.string.onboarding_battery_title), modifier = Modifier.fillMaxWidth())
    StepBody(text = stringResource(R.string.onboarding_battery_body))
    TandemSecondaryButton(
        text = stringResource(R.string.onboarding_battery_open_settings),
        onClick = onOpenSettings,
        modifier = Modifier.fillMaxWidth(),
        trailingIcon = TandemIcons.OpenInNew,
    )
    TandemPrimaryButton(
        text = stringResource(R.string.onboarding_battery_done),
        onClick = onDone,
        modifier = Modifier.fillMaxWidth(),
        trailingIcon = TandemIcons.ArrowForward,
    )
}
