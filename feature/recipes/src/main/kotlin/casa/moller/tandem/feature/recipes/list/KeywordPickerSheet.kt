package casa.moller.tandem.feature.recipes.list

import androidx.activity.compose.BackHandler
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
import casa.moller.tandem.core.model.recipe.Keyword
import casa.moller.tandem.feature.recipes.R
import java.text.Normalizer

/**
 * Picks the keywords the recipe list must have (all of them, Tandoor `keywords_and`). Changes
 * apply at once; the sheet has a filter field for long keyword lists (case and accent
 * insensitive, "vardag" finds "Vardagsmiddag", "akta" finds "Äkta").
 *
 * A modal bottom sheet on compact and medium windows; on expanded windows the same content is a
 * side panel instead ([KeywordPickerPanel], plan 6.5).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeywordPickerSheet(
    keywords: List<Keyword>,
    selectedIds: Set<Long>,
    onToggle: (Long) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Opens fully expanded: the filter field and the first keywords are visible at once.
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    // The sheet's default window insets include the keyboard, so the filter field and the list
    // stay above it.
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        KeywordPickerContent(keywords = keywords, selectedIds = selectedIds, onToggle = onToggle, onClear = onClear)
    }
}

/**
 * The keyword picker as a side panel on expanded windows (plan 6.5: "filter som sidopanel i
 * stället för bottenark"): beside the recipes, or in place of them when the list pane is narrow.
 * The close button and system back close it; the list keeps its scroll position meanwhile.
 */
@Composable
fun KeywordPickerPanel(
    keywords: List<Keyword>,
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
        KeywordPickerContent(
            keywords = keywords,
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

/** Heading with "Clear" (and a close button in the side panel), the filter field and the check list. */
@Composable
private fun KeywordPickerContent(
    keywords: List<Keyword>,
    selectedIds: Set<Long>,
    onToggle: (Long) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
) {
    val spacing = TandemTheme.spacing
    var filter by rememberSaveable { mutableStateOf("") }
    val shown = remember(keywords, filter) {
        val key = filter.searchKey()
        if (key.isBlank()) keywords else keywords.filter { key in it.name.searchKey() }
    }
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = spacing.xl, end = if (onClose != null) spacing.xs else spacing.xl),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.recipes_keywords_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() },
            )
            if (selectedIds.isNotEmpty()) {
                TandemTextButton(text = stringResource(R.string.recipes_keywords_clear), onClick = onClear)
            }
            if (onClose != null) {
                IconButton(onClick = onClose) {
                    Icon(imageVector = TandemIcons.Close, contentDescription = stringResource(R.string.recipes_keywords_close))
                }
            }
        }
        OutlinedTextField(
            value = filter,
            onValueChange = { filter = it },
            singleLine = true,
            leadingIcon = { Icon(TandemIcons.Search, contentDescription = null) },
            placeholder = { Text(stringResource(R.string.recipes_keywords_filter_hint)) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.xl, vertical = spacing.sm),
        )
        if (keywords.isEmpty()) {
            Text(
                text = stringResource(R.string.recipes_keywords_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = spacing.xl, vertical = spacing.lg),
            )
        }
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(shown, key = { it.id }) { keyword ->
                val checked = keyword.id in selectedIds
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = TandemTheme.sizes.minTouchTarget)
                        .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle(keyword.id) })
                        .padding(horizontal = spacing.lg),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    Checkbox(checked = checked, onCheckedChange = null)
                    Text(text = keyword.name, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

private val combiningMarks = Regex("\\p{Mn}+")

/** Lower case without diacritics: "Äkta" -> "akta". */
private fun String.searchKey(): String = Normalizer.normalize(this, Normalizer.Form.NFD).replace(combiningMarks, "").lowercase()
