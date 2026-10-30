package casa.moller.tandem.feature.pantry.hemma

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import casa.moller.tandem.core.designsystem.component.TandemTextButton
import casa.moller.tandem.core.designsystem.icon.Add
import casa.moller.tandem.core.designsystem.icon.Delete
import casa.moller.tandem.core.designsystem.icon.MoreVert
import casa.moller.tandem.core.designsystem.icon.Sync
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.ui.pantry.ExpiryLabel
import casa.moller.tandem.core.ui.pantry.expiryText
import casa.moller.tandem.core.ui.pantry.foodTitle
import casa.moller.tandem.core.ui.pantry.formatPantryAmount
import casa.moller.tandem.core.ui.recipe.currentLocale
import casa.moller.tandem.feature.pantry.R
import casa.moller.tandem.feature.pantry.ui.PantryCard
import casa.moller.tandem.feature.pantry.ui.PantryIcons
import kotlinx.datetime.LocalDate

/** Test tag of Hemma's list. */
const val PANTRY_LIST_TAG = "pantry:list"

/** Test tag of one stock entry's row. */
fun pantryRowTag(id: Long): String = "pantry:row:$id"

/** Test tag of a place's card. */
fun pantrySectionTag(id: Long): String = "pantry:section:$id"

/** Test tag of a row on "Har hemma". */
fun pantryOnHandTag(name: String): String = "pantry:on-hand:${name.lowercase()}"

/**
 * One stock entry (plan §8.8, 6.6): food, amount, the best-before day in colour with an icon and
 * text, and the quick actions "−1" and "Slut". ONE accessibility node, read as "Grädde, 2 dl, Kyl,
 * går ut i morgon"; "Slut", "En mindre", "Flytta" and "Ändra" are TalkBack actions (the buttons
 * are there for touch). A tap opens the entry. A guest sees no actions at all.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PantryEntryRow(
    row: PantryRow,
    today: LocalDate,
    canEdit: Boolean,
    onAction: (PantryAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val entry = row.entry
    val spacing = TandemTheme.spacing
    val locale = currentLocale()
    val title = foodTitle(entry.food.name, locale)
    val amount = formatPantryAmount(entry.amount, entry.unit?.name, locale)
    val expiry = entry.expires?.let { expiryText(it, today) }
    val pending = if (entry.pending) stringResource(R.string.pantry_row_pending) else null
    val note = entry.note?.takeIf { it.isNotBlank() }
    val description = listOfNotNull(title, amount, row.locationName, expiry, note, pending).joinToString(", ")
    val editLabel = stringResource(R.string.pantry_action_edit)
    val emptyLabel = stringResource(R.string.pantry_action_empty)
    val lessLabel = stringResource(R.string.pantry_action_less)
    val moveLabel = stringResource(R.string.pantry_action_move)
    val open = { onAction(PantryAction.OpenEntry(entry.id)) }
    Row(
        modifier = modifier
            .testTag(pantryRowTag(entry.id))
            .fillMaxWidth()
            .heightIn(min = TandemTheme.sizes.kitchenTouchTarget)
            .then(if (canEdit) Modifier.clickable(onClickLabel = editLabel, role = Role.Button, onClick = open) else Modifier)
            .clearAndSetSemantics {
                contentDescription = description
                if (canEdit) {
                    onClick(label = editLabel) {
                        open()
                        true
                    }
                    customActions = listOf(
                        CustomAccessibilityAction(emptyLabel) {
                            onAction(PantryAction.MarkEmpty(entry.id))
                            true
                        },
                        CustomAccessibilityAction(lessLabel) {
                            onAction(PantryAction.Decrement(entry.id))
                            true
                        },
                        CustomAccessibilityAction(moveLabel) {
                            onAction(PantryAction.StartMove(entry.id))
                            true
                        },
                    )
                }
            }
            .padding(start = spacing.lg, end = spacing.xs, top = spacing.sm, bottom = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                verticalArrangement = Arrangement.spacedBy(spacing.xxs),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = amount, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (expiry != null) ExpiryLabel(text = expiry, urgency = row.urgency)
                if (entry.pending) {
                    Icon(
                        imageVector = TandemIcons.Sync,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(TandemTheme.sizes.iconSmall),
                    )
                }
            }
            if (note != null) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (canEdit) {
            TandemTextButton(
                text = stringResource(R.string.pantry_action_less_short),
                onClick = { onAction(PantryAction.Decrement(entry.id)) },
                modifier = Modifier
                    .heightIn(min = TandemTheme.sizes.minTouchTarget)
                    .widthIn(min = TandemTheme.sizes.minTouchTarget),
            )
            TandemTextButton(
                text = emptyLabel,
                onClick = { onAction(PantryAction.MarkEmpty(entry.id)) },
                modifier = Modifier.heightIn(min = TandemTheme.sizes.minTouchTarget),
            )
        }
    }
}

/**
 * A storage place: its name (a tap opens the place on its own page, [onOpen]), how many items,
 * a menu (add here, rename, remove) and the entries; "Inget här ännu" when empty. Used as one
 * card in the tablet's columns; phones lay out the same parts as separate lazy items
 * ([LocationHeader], [PantryCardRow]) so a long place is not composed at once (fix-ui).
 */
@Composable
internal fun LocationCard(
    section: PantrySection,
    today: LocalDate,
    canEdit: Boolean,
    onAction: (PantryAction) -> Unit,
    modifier: Modifier = Modifier,
    onOpen: (() -> Unit)? = null,
) {
    val spacing = TandemTheme.spacing
    Column(modifier = modifier.testTag(pantrySectionTag(section.location.id)), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        LocationHeader(section = section, canEdit = canEdit, onAction = onAction, onOpen = onOpen)
        if (section.rows.isEmpty()) {
            LocationEmpty()
        } else {
            PantryCard {
                section.rows.forEachIndexed { index, row ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    PantryEntryRow(row = row, today = today, canEdit = canEdit, onAction = onAction)
                }
            }
        }
    }
}

/** A place's name, its count and its menu (a heading for TalkBack; a tap opens the place when [onOpen] is set). */
@Composable
internal fun LocationHeader(
    section: PantrySection,
    canEdit: Boolean,
    onAction: (PantryAction) -> Unit,
    modifier: Modifier = Modifier,
    onOpen: (() -> Unit)? = null,
) {
    val spacing = TandemTheme.spacing
    val location = section.location
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
        val count = section.rows.size
        val countText = pluralStringResource(R.plurals.pantry_location_count, count, count)
        Row(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = TandemTheme.sizes.minTouchTarget)
                .then(if (onOpen != null) Modifier.clickable(role = Role.Button, onClick = onOpen) else Modifier)
                .semantics(mergeDescendants = true) { heading() },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Icon(PantryIcons.Kitchen, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(
                text = location.name,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(text = countText, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (canEdit) LocationMenu(locationId = location.id, name = location.name, onAction = onAction)
    }
}

/** "Inget här ännu". */
@Composable
internal fun LocationEmpty(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.pantry_location_empty),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = TandemTheme.spacing.xs),
    )
}

/**
 * One row of a [PantryCard] drawn on its own (a lazy item of its own on phones): the card's
 * colour, its rounded corners only on the [first] and [last] row, a divider above every other
 * row, and the card's inner padding at its ends. Looks like the card around all rows.
 */
@Composable
internal fun PantryCardRow(first: Boolean, last: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val spacing = TandemTheme.spacing
    val large = MaterialTheme.shapes.large
    val none = CornerSize(0.dp)
    val shape = when {
        first && last -> large
        first -> large.copy(bottomStart = none, bottomEnd = none)
        last -> large.copy(topStart = none, topEnd = none)
        else -> RectangleShape
    }
    Surface(modifier = modifier.fillMaxWidth(), shape = shape, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(modifier = Modifier.padding(top = if (first) spacing.xs else 0.dp, bottom = if (last) spacing.xs else 0.dp)) {
            if (!first) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            content()
        }
    }
}

@Composable
private fun LocationMenu(locationId: Long, name: String, onAction: (PantryAction) -> Unit) {
    var open by rememberSaveable(locationId) { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(TandemIcons.MoreVert, contentDescription = stringResource(R.string.pantry_location_menu, name))
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.pantry_location_add_here)) },
            leadingIcon = { Icon(TandemIcons.Add, contentDescription = null) },
            onClick = {
                open = false
                onAction(PantryAction.AddEntry(locationId))
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.pantry_location_rename)) },
            leadingIcon = { Icon(PantryIcons.Edit, contentDescription = null) },
            onClick = {
                open = false
                onAction(PantryAction.EditLocation(locationId))
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.pantry_location_delete)) },
            leadingIcon = { Icon(TandemIcons.Delete, contentDescription = null) },
            onClick = {
                open = false
                onAction(PantryAction.AskDeleteLocation(locationId))
            },
        )
    }
}

/**
 * "Har hemma": foods only marked as at home, without an amount (salt, a jar of honey), and the
 * staples. Each row is one node with "Inte hemma längre" as its action. One card in the tablet's
 * columns; phones lay out [OnHandHeader] and each [OnHandRow] as lazy items of their own.
 */
@Composable
internal fun OnHandCard(foods: List<OnHandFood>, canEdit: Boolean, onAction: (PantryAction) -> Unit, modifier: Modifier = Modifier) {
    val spacing = TandemTheme.spacing
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        OnHandHeader()
        PantryCard {
            foods.forEachIndexed { index, food ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                OnHandRow(food = food, canEdit = canEdit, onAction = onAction)
            }
        }
    }
}

/** "Har hemma" and what it means. */
@Composable
internal fun OnHandHeader(modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(TandemTheme.spacing.xxs)) {
        Text(
            text = stringResource(R.string.pantry_on_hand_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(R.string.pantry_on_hand_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One food on "Har hemma": one TalkBack node with "Inte hemma längre". */
@Composable
internal fun OnHandRow(food: OnHandFood, canEdit: Boolean, onAction: (PantryAction) -> Unit, modifier: Modifier = Modifier) {
    val spacing = TandemTheme.spacing
    val title = foodTitle(food.name, currentLocale())
    val state = stringResource(if (food.staple) R.string.pantry_on_hand_staple else R.string.pantry_on_hand_row)
    val removeLabel = stringResource(R.string.pantry_on_hand_remove)
    val remove = { onAction(PantryAction.RemoveOnHand(food)) }
    Row(
        modifier = modifier
            .testTag(pantryOnHandTag(food.name))
            .fillMaxWidth()
            .heightIn(min = TandemTheme.sizes.minTouchTarget + spacing.sm)
            .clearAndSetSemantics {
                contentDescription = "$title, $state"
                if (canEdit) {
                    customActions = listOf(
                        CustomAccessibilityAction(removeLabel) {
                            remove()
                            true
                        },
                    )
                }
            }
            .padding(start = spacing.lg, end = spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(text = state, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (canEdit) {
            TandemTextButton(
                text = stringResource(R.string.pantry_on_hand_remove_short),
                onClick = remove,
                modifier = Modifier.heightIn(min = TandemTheme.sizes.minTouchTarget),
            )
        }
    }
}
