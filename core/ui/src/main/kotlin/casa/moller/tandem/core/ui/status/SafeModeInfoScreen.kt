package casa.moller.tandem.core.ui.status

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import casa.moller.tandem.core.designsystem.component.CalloutCard
import casa.moller.tandem.core.designsystem.component.TandemSecondaryButton
import casa.moller.tandem.core.designsystem.component.TandemTone
import casa.moller.tandem.core.designsystem.component.TandemTopAppBar
import casa.moller.tandem.core.designsystem.component.iconColor
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.server.ServerCompatibility
import casa.moller.tandem.core.ui.R

/** Tandem's releases on GitHub: where a newer, tested app version is published (plan 4.4, B10). */
const val TANDEM_RELEASES_URL: String = "https://github.com/Demacish/tandem/releases"

/**
 * What safe mode means (feature #2, plan 3.6 point 4), opened from [SafeModeBanner]: the server's
 * version, that reading works as usual, that changes are kept and queued instead of sent, that
 * "Send anyway" comes in a later version, and where newer app versions are published.
 *
 * @param onOpenReleases opens [TANDEM_RELEASES_URL] in the browser (the caller owns the intent).
 */
@Composable
fun SafeModeInfoScreen(
    version: String?,
    compatibility: ServerCompatibility,
    onBack: () -> Unit,
    onOpenReleases: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = TandemTheme.spacing
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TandemTopAppBar(title = stringResource(R.string.ui_safe_mode_title), onNavigateBack = onBack) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.screenHorizontal, vertical = spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = TandemTheme.sizes.maxReadingWidth)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(spacing.lg),
            ) {
                CalloutCard(
                    title = safeModeHeadline(version, compatibility),
                    body = stringResource(R.string.ui_safe_mode_intro),
                    icon = TandemIcons.Shield,
                    tone = TandemTone.Warning,
                )
                Text(
                    text = stringResource(R.string.ui_safe_mode_meaning_heading),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
                SafeModeFact(
                    icon = TandemIcons.CheckCircle,
                    tone = TandemTone.Success,
                    title = stringResource(R.string.ui_safe_mode_reading_title),
                    body = stringResource(R.string.ui_safe_mode_reading_body),
                )
                SafeModeFact(
                    icon = TandemIcons.Schedule,
                    tone = TandemTone.Warning,
                    title = stringResource(R.string.ui_safe_mode_queued_title),
                    body = stringResource(R.string.ui_safe_mode_queued_body),
                )
                SafeModeFact(
                    icon = TandemIcons.Info,
                    tone = TandemTone.Neutral,
                    title = stringResource(R.string.ui_safe_mode_send_anyway_title),
                    body = stringResource(R.string.ui_safe_mode_send_anyway_body),
                )
                Text(
                    text = stringResource(R.string.ui_safe_mode_update_heading),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    text = stringResource(R.string.ui_safe_mode_update_body),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TandemSecondaryButton(
                    text = stringResource(R.string.ui_safe_mode_releases),
                    onClick = onOpenReleases,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = TandemIcons.OpenInNew,
                )
            }
        }
    }
}

@Composable
private fun SafeModeFact(icon: ImageVector, tone: TandemTone, title: String, body: String, modifier: Modifier = Modifier) {
    val spacing = TandemTheme.spacing
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = tone.iconColor(), modifier = Modifier.size(TandemTheme.sizes.icon))
            Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Text(text = body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
