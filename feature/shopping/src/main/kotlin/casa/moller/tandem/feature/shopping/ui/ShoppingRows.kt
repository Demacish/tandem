package casa.moller.tandem.feature.shopping.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import casa.moller.tandem.core.designsystem.icon.Check
import casa.moller.tandem.core.designsystem.icon.Sync
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.shopping.ShoppingEntry
import casa.moller.tandem.feature.shopping.R
import casa.moller.tandem.feature.shopping.ShoppingFormat

/** Test tag of a list row: `shopping:row:<id>`. */
fun shoppingRowTag(id: Long): String = "shopping:row:$id"

/**
 * What a row can do; null = not offered (guest, store mode).
 *
 * @property onToggle tap or swipe to the end: check or uncheck.
 * @property onLater swipe to the start: "köp senare", or "köp nu" for a delayed entry.
 * @property onEdit long press: the edit sheet.
 */
internal class RowActions(val onToggle: (() -> Unit)? = null, val onLater: (() -> Unit)? = null, val onEdit: (() -> Unit)? = null)

/**
 * One entry on the list (plan 6.2, 6.6, Stitch ink_pslista_*): check mark, food, recipe label and
 * amount. ONE accessibility node, read as "2 dl grädde, Mejeri, avbockad" with the other actions
 * (köp senare, ändra) as TalkBack actions instead of extra buttons; colour is never the only
 * sign (strike-through and a check icon). At least [minHeight] high (48 dp, 64 dp in store mode).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ShoppingEntryRow(
    entry: ShoppingEntry,
    format: ShoppingFormat,
    categoryName: String,
    delayed: Boolean,
    actions: RowActions,
    modifier: Modifier = Modifier,
    large: Boolean = false,
    minHeight: Dp = if (large) TandemTheme.sizes.kitchenTouchTarget else 56.dp,
) {
    val quantity = format.quantity(entry)
    val food = format.food(entry)
    val description = buildList {
        add(listOfNotNull(quantity, food).joinToString(" "))
        add(categoryName)
        entry.recipe?.recipeName?.takeIf { it.isNotBlank() }?.let(::add)
        if (delayed) add(stringResource(R.string.shopping_row_later))
        if (entry.pending) add(stringResource(R.string.shopping_row_pending))
    }.joinToString(", ")
    val state = stringResource(if (entry.checked) R.string.shopping_row_checked else R.string.shopping_row_unchecked)
    val laterLabel = stringResource(if (delayed) R.string.shopping_action_now else R.string.shopping_action_later)
    val editLabel = stringResource(R.string.shopping_action_edit)
    val toggleLabel = stringResource(if (entry.checked) R.string.shopping_action_uncheck else R.string.shopping_action_check)
    val clickable = actions.onToggle
    val rowModifier = if (clickable != null) {
        Modifier.combinedClickable(
            onClickLabel = toggleLabel,
            onLongClickLabel = actions.onEdit?.let { editLabel },
            onLongClick = actions.onEdit,
            onClick = clickable,
        )
    } else {
        Modifier
    }
    SwipeRow(
        enabled = actions.onToggle != null,
        startToEnd = actions.onToggle,
        endToStart = actions.onLater,
        startIcon = TandemIcons.Check,
        endIcon = TandemIcons.Schedule,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .testTag(shoppingRowTag(entry.id))
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .heightIn(min = minHeight)
                .then(rowModifier)
                .clearAndSetSemantics {
                    contentDescription = description
                    stateDescription = state
                    if (clickable != null) {
                        role = Role.Checkbox
                        toggleableState = ToggleableState(entry.checked)
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
                .padding(horizontal = TandemTheme.spacing.lg, vertical = TandemTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TandemTheme.spacing.md),
        ) {
            CheckMark(checked = entry.checked, large = large)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = food,
                    style = if (large) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge,
                    textDecoration = if (entry.checked) TextDecoration.LineThrough else null,
                    color = if (entry.checked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
                val label = entry.recipe?.recipeName?.takeIf { it.isNotBlank() }
                if (label != null) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (entry.pending) {
                Icon(
                    imageVector = TandemIcons.Sync,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(TandemTheme.sizes.iconSmall),
                )
            }
            if (quantity != null) {
                Text(
                    text = quantity,
                    style = if (large) MaterialTheme.typography.titleLarge else MaterialTheme.typography.labelLarge,
                    color = if (large) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    textDecoration = if (entry.checked) TextDecoration.LineThrough else null,
                )
            }
        }
    }
}

/** The round check mark: an outline when open, a filled check when checked (never colour alone). */
@Composable
private fun CheckMark(checked: Boolean, large: Boolean) {
    val size = if (large) 32.dp else 24.dp
    Icon(
        imageVector = if (checked) TandemIcons.CheckCircleFilled else TandemIcons.CheckCircle,
        contentDescription = null,
        tint = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        modifier = Modifier.size(size),
    )
}

/**
 * Swipe to the end ([startToEnd], check) or to the start ([endToStart], "köp senare"). The row
 * snaps back: the change itself moves it to its new section.
 */
@Composable
private fun SwipeRow(
    enabled: Boolean,
    startToEnd: (() -> Unit)?,
    endToStart: (() -> Unit)?,
    startIcon: ImageVector,
    endIcon: ImageVector,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    if (!enabled) {
        Row(modifier = modifier, content = content)
        return
    }
    val state = rememberSwipeToDismissBoxState()
    val currentStart by rememberUpdatedState(startToEnd)
    val currentEnd by rememberUpdatedState(endToStart)
    LaunchedEffect(state.currentValue) {
        when (state.currentValue) {
            SwipeToDismissBoxValue.StartToEnd -> currentStart?.invoke()
            SwipeToDismissBoxValue.EndToStart -> currentEnd?.invoke()
            SwipeToDismissBoxValue.Settled -> return@LaunchedEffect
        }
        state.reset()
    }
    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        enableDismissFromStartToEnd = startToEnd != null,
        enableDismissFromEndToStart = endToStart != null,
        backgroundContent = {
            val direction = state.dismissDirection
            val (color, icon, alignment) = when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> Triple(MaterialTheme.colorScheme.primaryContainer, startIcon, Alignment.CenterStart)
                SwipeToDismissBoxValue.EndToStart -> Triple(MaterialTheme.colorScheme.tertiaryContainer, endIcon, Alignment.CenterEnd)
                SwipeToDismissBoxValue.Settled -> Triple(Color.Transparent, null, Alignment.Center)
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(color)
                    .padding(horizontal = TandemTheme.spacing.xl),
                contentAlignment = alignment,
            ) {
                if (icon != null) Icon(imageVector = icon, contentDescription = null)
            }
        },
        content = content,
    )
}
