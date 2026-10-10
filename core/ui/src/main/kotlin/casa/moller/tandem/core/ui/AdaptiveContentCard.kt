package casa.moller.tandem.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import casa.moller.tandem.core.designsystem.theme.TandemTheme

/**
 * Container for form-like screens (intro, login, server address) that must not stretch across a
 * tablet (plan 6.5: "Inloggning: helskärm på mobil, centrerat kort max ca 480 dp på
 * surfplatta").
 *
 * - Compact width (< 600 dp): content fills the width with the normal side padding.
 * - Wider: content sits in a centred card, at most [maxContentWidth] wide.
 *
 * The decision uses the space this composable gets, not "is it a tablet", so split screen and
 * desktop windows behave correctly. The content scrolls vertically; do not add another vertical
 * scroll inside.
 */
@Composable
fun AdaptiveContentCard(
    modifier: Modifier = Modifier,
    maxContentWidth: Dp = TandemTheme.sizes.maxContentWidth,
    content: @Composable ColumnScope.() -> Unit,
) {
    val spacing = TandemTheme.spacing
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val compact = maxWidth < CompactWidthLimit
        // Fill at least the visible height so a short card is centred, but scroll when taller.
        val minHeight = if (constraints.hasBoundedHeight) maxHeight else 0.dp
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = minHeight),
            contentAlignment = if (compact) Alignment.TopCenter else Alignment.Center,
        ) {
            if (compact) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = spacing.screenHorizontal, vertical = spacing.xl),
                    verticalArrangement = Arrangement.spacedBy(spacing.lg),
                    content = content,
                )
            } else {
                Surface(
                    modifier = Modifier
                        .padding(spacing.xxl)
                        .widthIn(max = maxContentWidth)
                        .fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    tonalElevation = TandemTheme.elevation.level1,
                ) {
                    Column(
                        modifier = Modifier.padding(spacing.xl),
                        verticalArrangement = Arrangement.spacedBy(spacing.lg),
                        content = content,
                    )
                }
            }
        }
    }
}

/** Material 3 compact window width limit. */
private val CompactWidthLimit = 600.dp
