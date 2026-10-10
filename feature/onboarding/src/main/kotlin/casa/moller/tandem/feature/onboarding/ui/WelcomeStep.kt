package casa.moller.tandem.feature.onboarding.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import casa.moller.tandem.core.designsystem.component.TandemPrimaryButton
import casa.moller.tandem.core.designsystem.component.TandemSecondaryButton
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.ui.language.AppLanguage
import casa.moller.tandem.feature.onboarding.R

/**
 * Step 1: name and logo, one-line pitch, "Get started" and an equally prominent "Try demo mode"
 * (plan 6.2), and the language picker in the corner (plan 6.7). [crashNotice] (the app's
 * "Tandem crashed last time" card, plan 4.2 p7) sits right below the header when there is a
 * crash log; it never blocks the buttons.
 */
@Composable
internal fun ColumnScope.WelcomeStep(
    language: AppLanguage,
    onLanguageSelected: (AppLanguage) -> Unit,
    onGetStarted: () -> Unit,
    onStartDemo: () -> Unit,
    showLogo: Boolean,
    crashNotice: @Composable () -> Unit = {},
) {
    StepHeader(
        step = 1,
        end = { LanguagePicker(current = language, onSelect = onLanguageSelected) },
    )
    crashNotice()
    if (showLogo) {
        BrandLogo(modifier = Modifier.align(Alignment.CenterHorizontally))
    }
    Text(
        text = stringResource(R.string.onboarding_welcome_title),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { heading() },
        style = MaterialTheme.typography.displaySmall,
        color = MaterialTheme.colorScheme.onBackground,
        textAlign = TextAlign.Center,
    )
    Text(
        text = stringResource(R.string.onboarding_welcome_body),
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Column(verticalArrangement = Arrangement.spacedBy(TandemTheme.spacing.md)) {
        FeatureRow(
            icon = TandemIcons.MenuBook,
            title = stringResource(R.string.onboarding_feature_recipes_title),
            body = stringResource(R.string.onboarding_feature_recipes_body),
        )
        FeatureRow(
            icon = TandemIcons.CalendarMonth,
            title = stringResource(R.string.onboarding_feature_plan_title),
            body = stringResource(R.string.onboarding_feature_plan_body),
        )
        FeatureRow(
            icon = TandemIcons.Dns,
            title = stringResource(R.string.onboarding_feature_server_title),
            body = stringResource(R.string.onboarding_feature_server_body),
        )
    }
    TandemPrimaryButton(
        text = stringResource(R.string.onboarding_get_started),
        onClick = onGetStarted,
        modifier = Modifier.fillMaxWidth(),
        trailingIcon = TandemIcons.ArrowForward,
    )
    TandemSecondaryButton(
        text = stringResource(R.string.onboarding_try_demo),
        onClick = onStartDemo,
        modifier = Modifier.fillMaxWidth(),
        leadingIcon = TandemIcons.Science,
    )
    Text(
        text = stringResource(R.string.onboarding_unofficial),
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun FeatureRow(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(TandemTheme.spacing.md),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(TandemTheme.sizes.icon),
            tint = MaterialTheme.colorScheme.primary,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(text = body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
