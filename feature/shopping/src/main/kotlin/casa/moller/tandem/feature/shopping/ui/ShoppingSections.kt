package casa.moller.tandem.feature.shopping.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import casa.moller.tandem.core.designsystem.component.TandemTextButton
import casa.moller.tandem.core.designsystem.icon.ExpandMore
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.feature.shopping.R
import casa.moller.tandem.feature.shopping.ShoppingSection

/** Test tag of an aisle card: `shopping:section:<key>`. */
fun shoppingSectionTag(key: String): String = "shopping:section:$key"

/** The rounded card around an aisle or a folded section (Stitch ink_pslista_*). */
@Composable
internal fun SectionSurface(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(vertical = TandemTheme.spacing.sm), content = content)
    }
}

/**
 * One aisle: its name (a heading), how many items, "Bocka av alla" when [onCheckAll] is set, and
 * the rows.
 */
@Composable
internal fun AisleCard(
    section: ShoppingSection,
    title: String,
    onCheckAll: (() -> Unit)?,
    modifier: Modifier = Modifier,
    rows: @Composable ColumnScope.() -> Unit,
) {
    SectionSurface(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = TandemTheme.sizes.minTouchTarget)
                .padding(start = TandemTheme.spacing.lg, end = TandemTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    text = pluralStringResource(R.plurals.shopping_section_count, section.entries.size, section.entries.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (onCheckAll != null) {
                val description = stringResource(R.string.shopping_check_all_description, title)
                TandemTextButton(
                    text = stringResource(R.string.shopping_check_all),
                    onClick = onCheckAll,
                    modifier = Modifier.semantics { contentDescription = description },
                )
            }
        }
        rows()
    }
}

/**
 * "Köp senare (2)" or "Avbockat (5)": a heading that folds the rows away (the checked section
 * starts folded, plan 6.2), with an optional action ("Rensa").
 */
@Composable
internal fun FoldingCard(
    title: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
    rows: @Composable ColumnScope.() -> Unit,
) {
    val state = stringResource(if (expanded) R.string.shopping_section_expanded else R.string.shopping_section_collapsed)
    val heading = stringResource(R.string.shopping_section_title_count, title, count)
    SectionSurface(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = TandemTheme.sizes.minTouchTarget)
                .padding(end = TandemTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = TandemTheme.sizes.minTouchTarget)
                    .clickable(role = Role.Button, onClick = onToggle)
                    .semantics(mergeDescendants = true) {
                        heading()
                        stateDescription = state
                    }
                    .padding(start = TandemTheme.spacing.lg),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(TandemTheme.spacing.sm),
            ) {
                Text(
                    text = heading,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Icon(
                    imageVector = TandemIcons.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.rotate(if (expanded) 180f else 0f),
                )
            }
            action?.invoke()
        }
        if (expanded) rows()
    }
}
