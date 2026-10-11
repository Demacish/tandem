package casa.moller.tandem.feature.recipes.list

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
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
    val spacing = TandemTheme.spacing
    var filter by rememberSaveable { mutableStateOf("") }
    val shown = remember(keywords, filter) {
        val key = filter.searchKey()
        if (key.isBlank()) keywords else keywords.filter { key in it.name.searchKey() }
    }
    // Opens fully expanded: the filter field and the first keywords are visible at once.
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.xl),
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
