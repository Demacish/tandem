package casa.moller.tandem.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import casa.moller.tandem.core.designsystem.R
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme

/**
 * Quiet information card: icon, title and body on a tinted surface. For facts the user should
 * know but need not act on ("Lösenordet sparas aldrig", "Synkar i bakgrunden").
 */
@Composable
fun InfoCard(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = TandemIcons.Info,
    tone: TandemTone = TandemTone.Neutral,
) {
    CalloutCard(
        title = title,
        body = body,
        modifier = modifier,
        icon = icon,
        tone = tone,
    )
}

/**
 * Prominent card with an icon badge, title, body and optional actions, e.g. "Har du ingen
 * Tandoor?" or the demo-mode banner. The card is not clickable itself; put actions in [actions]
 * (they wrap onto several lines with large fonts or long translations).
 *
 * @param onDismiss shows a close button in the top end corner when set.
 */
@Composable
fun CalloutCard(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tone: TandemTone = TandemTone.Accent,
    onDismiss: (() -> Unit)? = null,
    actions: (@Composable FlowRowScope.() -> Unit)? = null,
) {
    val spacing = TandemTheme.spacing
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.largeIncreased,
        color = tone.containerColor(),
        contentColor = tone.contentColor(),
    ) {
        Row(
            modifier = Modifier.padding(
                start = spacing.lg,
                top = spacing.lg,
                bottom = spacing.lg,
                end = if (onDismiss != null) spacing.xs else spacing.lg,
            ),
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            if (icon != null) {
                IconBadge(icon = icon, tone = tone)
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() },
                )
                Text(text = body, style = MaterialTheme.typography.bodyMedium)
                if (actions != null) {
                    FlowRow(
                        modifier = Modifier.padding(top = spacing.sm),
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(spacing.sm),
                        itemVerticalAlignment = Alignment.CenterVertically,
                        content = actions,
                    )
                }
            }
            if (onDismiss != null) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = TandemIcons.Close,
                        contentDescription = stringResource(R.string.tandem_action_close),
                    )
                }
            }
        }
    }
}

/** Rounded square with a tone-coloured icon, as in the design's banners and error cards. */
@Composable
private fun IconBadge(icon: ImageVector, tone: TandemTone, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.size(44.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.6f),
        contentColor = tone.iconColor(),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(TandemTheme.sizes.icon))
        }
    }
}

/** Small circle with an icon, for empty and error states. */
@Composable
fun TandemIconHero(icon: ImageVector, modifier: Modifier = Modifier, tone: TandemTone = TandemTone.Accent) {
    Surface(
        modifier = modifier.size(96.dp),
        shape = CircleShape,
        color = tone.containerColor(),
        contentColor = tone.iconColor(),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(TandemTheme.sizes.iconHero))
        }
    }
}
