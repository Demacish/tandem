package casa.moller.tandem.feature.shopping.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import casa.moller.tandem.core.designsystem.component.TandemTopAppBar
import casa.moller.tandem.core.designsystem.icon.Add
import casa.moller.tandem.core.designsystem.icon.History
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.shopping.ShoppingHistoryItem
import casa.moller.tandem.core.ui.EmptyState
import casa.moller.tandem.core.ui.LoadingState
import casa.moller.tandem.feature.shopping.R
import casa.moller.tandem.feature.shopping.ShoppingFormat
import casa.moller.tandem.feature.shopping.ShoppingHistoryUiState
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * "Köpt tidigare" (plan 8.2 "Lokal historik"), stateless: what aged out of Tandoor's list, by the
 * day it was checked, newest first; "+" puts a food back on the list (not for guests).
 */
@Composable
fun ShoppingHistoryScreen(
    state: ShoppingHistoryUiState,
    onAddAgain: (ShoppingHistoryItem) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val locale = LocalConfiguration.current.locales[0]
    val format = remember(locale) { ShoppingFormat(locale) }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TandemTopAppBar(title = stringResource(R.string.shopping_history_title), onNavigateBack = onBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        when {
            state.loading -> LoadingState(modifier = Modifier.fillMaxSize().padding(padding))

            state.items.isEmpty() -> EmptyState(
                icon = TandemIcons.History,
                title = stringResource(R.string.shopping_history_empty_title),
                body = stringResource(R.string.shopping_history_empty_body),
                modifier = Modifier.fillMaxSize().padding(padding),
            )

            else -> {
                val days = remember(state.items, locale) { byDay(state.items, locale) }
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(vertical = TandemTheme.spacing.sm),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    days.forEach { (day, items) ->
                        if (day.isNotEmpty()) {
                            item(key = "day-$day") {
                                Text(
                                    text = day,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .widthIn(max = TandemTheme.sizes.maxReadingWidth)
                                        .fillMaxWidth()
                                        .padding(horizontal = TandemTheme.spacing.screenHorizontal, vertical = TandemTheme.spacing.sm)
                                        .semantics { heading() },
                                )
                            }
                        }
                        items(items, key = { it.entryId }) { item ->
                            HistoryRow(item = item, format = format, onAddAgain = if (state.readOnly) null else ({ onAddAgain(item) }))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(item: ShoppingHistoryItem, format: ShoppingFormat, onAddAgain: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .widthIn(max = TandemTheme.sizes.maxReadingWidth)
            .fillMaxWidth()
            .heightIn(min = TandemTheme.sizes.minTouchTarget)
            .padding(start = TandemTheme.spacing.screenHorizontal, end = TandemTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TandemTheme.spacing.md),
    ) {
        Text(text = format.history(item), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (onAddAgain != null) {
            IconButton(onClick = onAddAgain) {
                Icon(TandemIcons.Add, contentDescription = stringResource(R.string.shopping_history_add_again, item.foodName))
            }
        }
    }
}

/** The items by local day of [ShoppingHistoryItem.completedAt] ("Fredag 9 oktober"), unknown days last. */
private fun byDay(items: List<ShoppingHistoryItem>, locale: Locale): List<Pair<String, List<ShoppingHistoryItem>>> {
    val zone = ZoneId.systemDefault()
    val formatter = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", locale)
    return items
        .sortedByDescending { it.completedAt }
        .groupBy { item ->
            item.completedAt?.let { instant ->
                java.time.Instant.ofEpochMilli(instant.toEpochMilliseconds()).atZone(zone).toLocalDate().format(formatter)
                    .replaceFirstChar { it.titlecase(locale) }
            }.orEmpty()
        }
        .toList()
}
