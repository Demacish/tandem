package casa.moller.tandem.feature.onboarding.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import casa.moller.tandem.core.designsystem.component.CalloutCard
import casa.moller.tandem.core.designsystem.component.TandemIconHero
import casa.moller.tandem.core.designsystem.component.TandemPrimaryButton
import casa.moller.tandem.core.designsystem.component.TandemTone
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.feature.onboarding.ConnectedSummary
import casa.moller.tandem.feature.onboarding.R

/** After login: "Connected to <server> · Tandoor <version>" (Fas 0 "Klart när" #3), then the first sync. */
@Composable
internal fun ColumnScope.ConnectedStep(summary: ConnectedSummary, onContinue: () -> Unit) {
    TandemIconHero(
        icon = TandemIcons.CheckCircle,
        modifier = Modifier.align(Alignment.CenterHorizontally),
        tone = TandemTone.Success,
    )
    Text(
        text = stringResource(R.string.onboarding_connected_title),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { heading() },
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.onBackground,
        textAlign = TextAlign.Center,
    )
    Text(
        text = stringResource(R.string.onboarding_connected_summary, summary.serverAddress, summary.serverVersion),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
    )
    Text(
        text = stringResource(R.string.onboarding_connected_account, summary.accountLabel),
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    if (summary.safeMode) {
        CalloutCard(
            title = stringResource(R.string.onboarding_safe_mode_title),
            body = stringResource(R.string.onboarding_safe_mode_body, summary.serverVersion),
            icon = TandemIcons.Shield,
            tone = TandemTone.Warning,
        )
    }
    TandemPrimaryButton(
        text = stringResource(R.string.onboarding_continue),
        onClick = onContinue,
        modifier = Modifier.fillMaxWidth(),
        trailingIcon = TandemIcons.ArrowForward,
    )
}
