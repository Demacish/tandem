package casa.moller.tandem.core.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import casa.moller.tandem.core.designsystem.R as DesignR
import casa.moller.tandem.core.designsystem.component.TandemIconHero
import casa.moller.tandem.core.designsystem.component.TandemLoadingIndicator
import casa.moller.tandem.core.designsystem.component.TandemPrimaryButton
import casa.moller.tandem.core.designsystem.component.TandemTextButton
import casa.moller.tandem.core.designsystem.component.TandemTone
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme

/**
 * Empty state: large icon, title, explanation and the next useful actions (design: "tomma
 * lägen" with e.g. "Importera" / "Ladda exempelrecept"). Centred and width-limited so it reads
 * well on tablets.
 *
 * @param actions usually one [TandemPrimaryButton] and optionally a secondary button.
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actions: @Composable ColumnScope.() -> Unit = {},
) {
    StateLayout(
        icon = icon,
        tone = TandemTone.Accent,
        title = title,
        body = body,
        modifier = modifier,
        actions = actions,
    )
}

/**
 * Error state for a screen or a section: what happened (title), what the user can do (body),
 * a retry button and, folded away, the technical details for bug reports (plan 5.4).
 *
 * @param details technical text (error type, HTTP status, server version). Selectable so it can
 *   be copied; never contains tokens or passwords (the caller sanitises it).
 * @param onRetry shows "Try again" as the primary action when set.
 * @param secondaryAction an extra action below retry, e.g. "Testa med demoläget i stället".
 */
@Composable
fun ErrorState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = TandemIcons.CloudOff,
    details: String? = null,
    onRetry: (() -> Unit)? = null,
    secondaryAction: (@Composable ColumnScope.() -> Unit)? = null,
) {
    var showDetails by rememberSaveable { mutableStateOf(false) }
    StateLayout(
        icon = icon,
        tone = TandemTone.Error,
        title = title,
        body = body,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        if (onRetry != null) {
            TandemPrimaryButton(
                text = stringResource(DesignR.string.tandem_action_retry),
                onClick = onRetry,
                leadingIcon = TandemIcons.Refresh,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        secondaryAction?.invoke(this)
        if (details != null) {
            TandemTextButton(
                text = stringResource(
                    if (showDetails) {
                        R.string.ui_error_hide_details
                    } else {
                        R.string.ui_error_show_details
                    },
                ),
                onClick = { showDetails = !showDetails },
            )
            AnimatedVisibility(visible = showDetails) {
                ErrorDetails(details)
            }
        }
    }
}

/**
 * Full-area loading state with the expressive loading indicator and an optional message
 * ("Hämtar recept…"). Prefer skeletons or cached content where possible; this is for first
 * loads with nothing to show yet.
 */
@Composable
fun LoadingState(modifier: Modifier = Modifier, message: String? = null) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(TandemTheme.spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(TandemTheme.spacing.lg, Alignment.CenterVertically),
    ) {
        TandemLoadingIndicator()
        if (message != null) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun StateLayout(
    icon: ImageVector,
    tone: TandemTone,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actions: @Composable ColumnScope.() -> Unit,
) {
    val spacing = TandemTheme.spacing
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.xl, vertical = spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.lg, Alignment.CenterVertically),
    ) {
        TandemIconHero(icon = icon, tone = tone)
        Column(
            modifier = Modifier.widthIn(max = TandemTheme.sizes.maxContentWidth),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Column(
            modifier = Modifier.widthIn(max = TandemTheme.sizes.maxContentWidth),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
            content = actions,
        )
    }
}

@Composable
private fun ErrorDetails(details: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier.padding(TandemTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(TandemTheme.spacing.xs),
        ) {
            Text(
                text = stringResource(R.string.ui_error_details_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SelectionContainer {
                Text(
                    text = details,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
        }
    }
}
