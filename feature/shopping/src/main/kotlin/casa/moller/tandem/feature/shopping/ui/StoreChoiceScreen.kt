package casa.moller.tandem.feature.shopping.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import casa.moller.tandem.core.designsystem.component.InfoCard
import casa.moller.tandem.core.designsystem.component.TandemTopAppBar
import casa.moller.tandem.core.designsystem.icon.ChevronRight
import casa.moller.tandem.core.designsystem.icon.NotificationsActive
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.ui.LoadingState
import casa.moller.tandem.feature.shopping.R
import casa.moller.tandem.feature.shopping.StoreChoiceUiState

/**
 * Choosing the store the list is sorted by (#10, Stitch butiksl_ge), stateless: "Ingen butik"
 * (aisles by name) and Tandoor's stores with how many aisles they have.
 *
 * @param onOpenReminder Fas 5 (butik lane, plan §7 #34): "Påminn mig nära en butik" below the
 *   stores, opening "Påminnelse nära butiken"; null shows no row.
 */
@Composable
fun StoreChoiceScreen(
    state: StoreChoiceUiState,
    onChoose: (Long?) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    onOpenReminder: (() -> Unit)? = null,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TandemTopAppBar(title = stringResource(R.string.shopping_list_choice_title), onNavigateBack = onBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (state.loading) {
            LoadingState(modifier = Modifier.fillMaxSize().padding(padding))
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .selectableGroup(),
            contentPadding = PaddingValues(vertical = TandemTheme.spacing.sm),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item(key = "none") {
                ChoiceRow(
                    title = stringResource(R.string.shopping_store_none_title),
                    body = stringResource(R.string.shopping_store_none_body),
                    selected = state.selectedId == null,
                    onClick = { onChoose(null) },
                )
            }
            items(state.stores, key = { it.id }) { store ->
                ChoiceRow(
                    title = store.name,
                    body = pluralStringResource(R.plurals.shopping_store_aisles, store.aisles.size, store.aisles.size),
                    selected = state.selectedId == store.id,
                    onClick = { onChoose(store.id) },
                )
            }
            // Fas 5 (butik lane): the reminder near the store, per store (a row of its own, not a choice).
            if (onOpenReminder != null && state.stores.isNotEmpty()) {
                item(key = "reminder") { ReminderRow(onClick = onOpenReminder) }
            }
            if (state.stores.isEmpty()) {
                item(key = "empty") {
                    InfoCard(
                        title = stringResource(R.string.shopping_store_heading),
                        body = stringResource(R.string.shopping_store_empty),
                        modifier = Modifier
                            .widthIn(max = TandemTheme.sizes.maxReadingWidth)
                            .padding(TandemTheme.spacing.screenHorizontal),
                    )
                }
            }
        }
    }
}

@Composable
private fun ChoiceRow(title: String, body: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .widthIn(max = TandemTheme.sizes.maxReadingWidth)
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(horizontal = TandemTheme.spacing.screenHorizontal, vertical = TandemTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TandemTheme.spacing.md),
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(text = body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** "Påminn mig nära en butik": one button with its explanation (outside the radio group's choices). */
@Composable
private fun ReminderRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .widthIn(max = TandemTheme.sizes.maxReadingWidth)
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = TandemTheme.spacing.screenHorizontal, vertical = TandemTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TandemTheme.spacing.md),
    ) {
        Icon(imageVector = TandemIcons.NotificationsActive, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(modifier = Modifier.weight(1f)) {
            Text(text = stringResource(R.string.store_reminder_choice_row), style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(R.string.store_reminder_more_supporting),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(imageVector = TandemIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
