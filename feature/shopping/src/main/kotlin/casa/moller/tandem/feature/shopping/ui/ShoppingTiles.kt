package casa.moller.tandem.feature.shopping.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import casa.moller.tandem.core.designsystem.icon.Sync
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.domain.shopping.MergedShoppingRow
import casa.moller.tandem.feature.shopping.R
import casa.moller.tandem.feature.shopping.ShoppingFormat

/** Test tag of a tile in the grid: `shopping:tile:<row id>`. */
fun shoppingTileTag(id: Long): String = "shopping:tile:$id"

/** The narrowest a tile gets; the tiles of a card share its width (at least two per line). */
private val TileMinWidth = 112.dp

/** Tiles are at least this high: well above the 64 dp kitchen target (plan 6.6), Bring!-sized. */
private val TileMinHeight = 104.dp

private const val MIN_TILES_PER_LINE = 2

/**
 * Fas 5 (plan §8.2 "Rutnätsvy med stora rutor", Bring!): the rows of one card as large tiles that
 * share its width, in the same order as the rows (the store's walking order, merged rows). Tap
 * checks off (or back), long press opens the edit sheet ("details"); a guest's tiles do nothing.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ShoppingTiles(
    rows: List<MergedShoppingRow>,
    format: ShoppingFormat,
    categoryOf: (MergedShoppingRow) -> String,
    delayed: Boolean,
    actionsOf: @Composable (MergedShoppingRow) -> RowActions,
    modifier: Modifier = Modifier,
) {
    val spacing = TandemTheme.spacing
    BoxWithConstraints(modifier = modifier.fillMaxWidth().padding(horizontal = spacing.md, vertical = spacing.xs)) {
        val gap = spacing.sm
        val perLine = ((maxWidth + gap) / (TileMinWidth + gap)).toInt().coerceAtLeast(MIN_TILES_PER_LINE)
        val tileWidth = (maxWidth - gap * (perLine - 1)) / perLine
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalArrangement = Arrangement.spacedBy(gap),
            maxItemsInEachRow = perLine,
        ) {
            rows.forEach { row ->
                key(row.id) {
                    ShoppingTile(
                        row = row,
                        format = format,
                        categoryName = categoryOf(row),
                        delayed = delayed,
                        actions = actionsOf(row),
                        width = tileWidth,
                    )
                }
            }
        }
    }
}

/**
 * One tile: food (large), amount and aisle. ONE accessibility node like a row ("5 dl grädde,
 * Mejeri, 2 rader på listan", the state, "Bocka av", and "Köp senare" / "Ändra" as TalkBack
 * actions); colour is never the only sign (a check icon and strike-through when checked).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShoppingTile(
    row: MergedShoppingRow,
    format: ShoppingFormat,
    categoryName: String,
    delayed: Boolean,
    actions: RowActions,
    width: Dp,
) {
    val quantity = format.quantity(row)
    val food = format.food(row)
    val label = row.recipeNames.joinToString(", ").takeIf { it.isNotBlank() }
    val merged = if (row.isMerged) pluralStringResource(R.plurals.shopping_row_merged, row.entries.size, row.entries.size) else null
    val description = buildList {
        add(listOfNotNull(quantity, food).joinToString(" "))
        add(categoryName)
        label?.let(::add)
        merged?.let(::add)
        if (delayed) add(stringResource(R.string.shopping_row_later))
        if (row.pending) add(stringResource(R.string.shopping_row_pending))
    }.joinToString(", ")
    val checked = row.checked
    val state = stringResource(if (checked) R.string.shopping_row_checked else R.string.shopping_row_unchecked)
    val toggleLabel = stringResource(if (checked) R.string.shopping_action_uncheck else R.string.shopping_action_check)
    val laterLabel = stringResource(if (delayed) R.string.shopping_action_now else R.string.shopping_action_later)
    val editLabel = stringResource(R.string.shopping_action_edit)
    val colors = MaterialTheme.colorScheme
    val (container, content) = when {
        checked -> colors.surfaceContainerHigh to colors.onSurfaceVariant
        delayed -> colors.tertiaryContainer to colors.onTertiaryContainer
        else -> colors.secondaryContainer to colors.onSecondaryContainer
    }
    val toggle = actions.onToggle
    val clickable = if (toggle != null) {
        Modifier.combinedClickable(
            onClickLabel = toggleLabel,
            onLongClickLabel = actions.onEdit?.let { editLabel },
            onLongClick = actions.onEdit,
            onClick = toggle,
        )
    } else {
        Modifier
    }
    Column(
        modifier = Modifier
            .testTag(shoppingTileTag(row.id))
            .width(width)
            .heightIn(min = TileMinHeight)
            .clip(MaterialTheme.shapes.large)
            .background(container)
            .then(clickable)
            .clearAndSetSemantics {
                contentDescription = description
                stateDescription = state
                if (toggle != null) {
                    role = Role.Checkbox
                    toggleableState = ToggleableState(checked)
                    customActions = listOfNotNull(
                        actions.onLater?.let { later ->
                            CustomAccessibilityAction(laterLabel) {
                                later()
                                true
                            }
                        },
                        actions.onEdit?.let { edit ->
                            CustomAccessibilityAction(editLabel) {
                                edit()
                                true
                            }
                        },
                    )
                }
            }
            .padding(TandemTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(TandemTheme.spacing.xs),
    ) {
        CompositionLocalProvider(LocalContentColor provides content) {
            TileStatus(checked = checked, pending = row.pending, delayed = delayed, tint = content)
            Text(
                text = food,
                style = MaterialTheme.typography.titleMedium,
                textDecoration = if (checked) TextDecoration.LineThrough else null,
            )
            if (quantity != null) {
                Text(
                    text = quantity,
                    style = MaterialTheme.typography.bodyLarge,
                    textDecoration = if (checked) TextDecoration.LineThrough else null,
                )
            }
            Text(text = categoryName, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** The small icons at the top of a tile: checked, "köp senare", waiting to sync. */
@Composable
private fun TileStatus(checked: Boolean, pending: Boolean, delayed: Boolean, tint: Color) {
    if (!checked && !pending && !delayed) return
    Row(horizontalArrangement = Arrangement.spacedBy(TandemTheme.spacing.xs), verticalAlignment = Alignment.CenterVertically) {
        val size = Modifier.size(TandemTheme.sizes.iconSmall)
        if (checked) Icon(TandemIcons.CheckCircleFilled, contentDescription = null, tint = tint, modifier = size)
        if (delayed) Icon(TandemIcons.Schedule, contentDescription = null, tint = tint, modifier = size)
        if (pending) Icon(TandemIcons.Sync, contentDescription = null, tint = tint, modifier = size)
    }
}
