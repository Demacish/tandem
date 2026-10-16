package casa.moller.tandem.feature.recipes.list

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import casa.moller.tandem.core.designsystem.component.TandemTextButton
import casa.moller.tandem.core.designsystem.icon.Search
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.feature.recipes.R
import java.text.Normalizer

/** What a [FilterPickerSheet] / [FilterPickerPanel] picks: its texts differ, the behaviour does not. */
enum class FilterPicker {
    /** Keywords the recipes must all have (Tandoor `keywords_and`). */
    KEYWORDS,

    /** Foods the recipes must all contain (Tandoor `foods_and`, plan 6.2 "livsmedel"). */
    FOODS,
}

/** One choice in a filter picker: a keyword or a food. */
@Immutable
data class FilterOption(val id: Long, val name: String)

/**
 * Picks the keywords or foods the recipe list must have (all of them). Changes apply at once; the
 * sheet has a filter field for long lists (case and accent insensitive, "vardag" finds
 * "Vardagsmiddag", "akta" finds "Äkta"). Picked options come first, so they stay in reach in a
 * long food list.
 *
 * A modal bottom sheet on compact and medium windows; on expanded windows the same content is a
 * side panel instead ([FilterPickerPanel], plan 6.5).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterPickerSheet(
    picker: FilterPicker,
    options: List<FilterOption>,
    selectedIds: Set<Long>,
    onToggle: (Long) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Opens fully expanded: the filter field and the first options are visible at once.
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    // The sheet's default window insets include the keyboard, so the filter field and the list
    // stay above it.
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        FilterPickerContent(picker = picker, options = options, selectedIds = selectedIds, onToggle = onToggle, onClear = onClear)
    }
}

/**
 * The filter picker as a side panel on expanded windows (plan 6.5: "filter som sidopanel i
 * stället för bottenark"): beside the recipes, or in place of them when the list pane is narrow.
 * The close button and system back close it; the list keeps its scroll position meanwhile.
 */
@Composable
fun FilterPickerPanel(
    picker: FilterPicker,
    options: List<FilterOption>,
    selectedIds: Set<Long>,
    onToggle: (Long) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onDismiss)
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        FilterPickerContent(
            picker = picker,
            options = options,
            selectedIds = selectedIds,
            onToggle = onToggle,
            onClear = onClear,
            onClose = onDismiss,
            modifier = Modifier
                .padding(top = TandemTheme.spacing.sm)
                // An inline panel is not a window of its own: it keeps clear of the keyboard itself.
                .imePadding(),
        )
    }
}

/** The texts of one picker. */
private class PickerTexts(
    @StringRes val title: Int,
    @StringRes val clear: Int,
    @StringRes val filterHint: Int,
    @StringRes val empty: Int,
    @StringRes val close: Int,
)

private fun FilterPicker.texts(): PickerTexts = when (this) {
    FilterPicker.KEYWORDS -> PickerTexts(
        title = R.string.recipes_keywords_title,
        clear = R.string.recipes_keywords_clear,
        filterHint = R.string.recipes_keywords_filter_hint,
        empty = R.string.recipes_keywords_empty,
        close = R.string.recipes_keywords_close,
    )

    FilterPicker.FOODS -> PickerTexts(
        title = R.string.recipes_foods_title,
        clear = R.string.recipes_foods_clear,
        filterHint = R.string.recipes_foods_filter_hint,
        empty = R.string.recipes_foods_empty,
        close = R.string.recipes_foods_close,
    )
}

/** Heading with "Clear" (and a close button in the side panel), the filter field and the check list. */
@Composable
private fun FilterPickerContent(
    picker: FilterPicker,
    options: List<FilterOption>,
    selectedIds: Set<Long>,
    onToggle: (Long) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
) {
    val spacing = TandemTheme.spacing
    val texts = picker.texts()
    // Each picker has its own filter text (switching from keywords to foods starts empty).
    var filter by rememberSaveable(picker) { mutableStateOf("") }
    val keyed = remember(options) { options.map { it to it.name.searchKey() } }
    // Picked options first, in the order of [options]; computed when the picker opens or the
    // filter changes, not on every toggle, so a ticked row does not jump away under the finger.
    val pickedWhenOpened = remember(picker, options.isEmpty()) { selectedIds }
    val shown = remember(keyed, filter, pickedWhenOpened) {
        val key = filter.searchKey()
        val matching = if (key.isBlank()) keyed.map { it.first } else keyed.filter { key in it.second }.map { it.first }
        val (picked, others) = matching.partition { it.id in pickedWhenOpened }
        picked + others
    }
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = spacing.xl, end = if (onClose != null) spacing.xs else spacing.xl),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(texts.title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() },
            )
            if (selectedIds.isNotEmpty()) {
                TandemTextButton(text = stringResource(texts.clear), onClick = onClear)
            }
            if (onClose != null) {
                IconButton(onClick = onClose) {
                    Icon(imageVector = TandemIcons.Close, contentDescription = stringResource(texts.close))
                }
            }
        }
        OutlinedTextField(
            value = filter,
            onValueChange = { filter = it },
            singleLine = true,
            leadingIcon = { Icon(TandemIcons.Search, contentDescription = null) },
            placeholder = { Text(stringResource(texts.filterHint)) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.xl, vertical = spacing.sm),
        )
        if (options.isEmpty()) {
            Text(
                text = stringResource(texts.empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = spacing.xl, vertical = spacing.lg),
            )
        }
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(shown, key = { it.id }) { option ->
                val checked = option.id in selectedIds
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = TandemTheme.sizes.minTouchTarget)
                        .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle(option.id) })
                        .padding(horizontal = spacing.lg),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    Checkbox(checked = checked, onCheckedChange = null)
                    Text(text = option.name, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

private val combiningMarks = Regex("\\p{Mn}+")

/** Lower case without diacritics: "Äkta" -> "akta". */
private fun String.searchKey(): String = Normalizer.normalize(this, Normalizer.Form.NFD).replace(combiningMarks, "").lowercase()
