package casa.moller.tandem.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import casa.moller.tandem.core.designsystem.theme.TandemTheme

/**
 * Non-interactive status pill ("Demo", "Offline", "Säkert läge", "Alla ingredienser hemma").
 * Icon + text + tone, so the state is never shown by colour alone (plan 6.6). TalkBack reads it
 * as one item with [label] (or [contentDescription] when the visible label is too terse).
 *
 * The pill is one line; a label too wide for it (large font scale) ends with "…" instead of
 * being cut mid-glyph. Longer texts that must stay readable belong in a wrapping element.
 */
@Composable
fun StatusChip(
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tone: TandemTone = TandemTone.Neutral,
    contentDescription: String = label,
) {
    val spacing = TandemTheme.spacing
    Surface(
        modifier = modifier
            .heightIn(min = 32.dp)
            .clearAndSetSemantics { this.contentDescription = contentDescription },
        shape = CircleShape,
        color = tone.containerColor(),
        contentColor = tone.contentColor(),
    ) {
        Row(
            modifier = Modifier.padding(
                start = if (icon != null) spacing.sm else spacing.md,
                end = spacing.md,
                top = spacing.xs,
                bottom = spacing.xs,
            ),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tone.iconColor(),
                    modifier = Modifier.size(TandemTheme.sizes.iconSmall),
                )
            }
            Text(text = label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
