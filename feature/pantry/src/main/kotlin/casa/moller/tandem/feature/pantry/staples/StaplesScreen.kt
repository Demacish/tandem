package casa.moller.tandem.feature.pantry.staples

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import casa.moller.tandem.core.designsystem.component.InfoCard
import casa.moller.tandem.core.designsystem.component.TandemPrimaryButton
import casa.moller.tandem.core.designsystem.component.TandemTextButton
import casa.moller.tandem.core.designsystem.component.TandemTopAppBar
import casa.moller.tandem.core.designsystem.icon.Add
import casa.moller.tandem.core.designsystem.icon.Check
import casa.moller.tandem.core.designsystem.icon.Delete
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.ui.LoadingState
import casa.moller.tandem.core.ui.pantry.foodTitle
import casa.moller.tandem.core.ui.recipe.currentLocale
import casa.moller.tandem.feature.pantry.R

/** Test tag of "Spara" on Basvaror. */
const val STAPLES_SAVE_TAG = "staples:save"

/** Test tag of one staple. */
fun stapleRowTag(name: String): String = "staples:row:${name.lowercase()}"

/**
 * "Basvaror" (plan §8.8), stateless: why ("annars saknas nästan alltid salt och olja"), the
 * checklist (ticked = har hemma), "Lägg till en egen", and "Spara" with "Inte nu" pinned below.
 * A guest sees the list without checkboxes.
 */
@Composable
fun StaplesScreen(
    state: StaplesUiState,
    onAction: (StaplesAction) -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TandemTopAppBar(
                title = stringResource(R.string.pantry_staples_title),
                onNavigateBack = { onAction(StaplesAction.Close) },
            )
        },
        bottomBar = { if (!state.loading && !state.readOnly) StaplesActions(state = state, onAction = onAction) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (state.loading) {
            LoadingState(modifier = Modifier.fillMaxSize().padding(padding))
            return@Scaffold
        }
        val spacing = TandemTheme.spacing
        val width = Modifier
            .widthIn(max = TandemTheme.sizes.maxReadingWidth)
            .fillMaxWidth()
            .padding(horizontal = spacing.screenHorizontal)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + spacing.sm,
                bottom = padding.calculateBottomPadding() + spacing.xl,
            ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            item(key = "intro") {
                Column(modifier = width, verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    if (state.readOnly) {
                        InfoCard(
                            title = stringResource(R.string.pantry_guest_title),
                            body = stringResource(R.string.pantry_guest_body),
                            icon = TandemIcons.Lock,
                        )
                    }
                    Text(text = stringResource(R.string.pantry_staples_intro), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = pluralStringResource(R.plurals.pantry_staples_count, state.atHomeCount, state.atHomeCount),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            item(key = "list") {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = width,
                ) {
                    Column(modifier = Modifier.padding(vertical = spacing.xs)) {
                        state.rows.forEachIndexed { index, row ->
                            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            StapleItem(row = row, readOnly = state.readOnly, onAction = onAction)
                        }
                    }
                }
            }
            if (!state.readOnly) {
                item(key = "add") { AddStaple(text = state.text, onAction = onAction, modifier = width) }
            }
        }
    }
}

/** One staple: the checkbox (har hemma) and, for the household's own, "Ta bort från basvarorna". */
@Composable
private fun StapleItem(row: StapleRow, readOnly: Boolean, onAction: (StaplesAction) -> Unit) {
    val spacing = TandemTheme.spacing
    val title = foodTitle(row.name, currentLocale())
    Row(
        modifier = Modifier
            .testTag(stapleRowTag(row.name))
            .fillMaxWidth()
            .heightIn(min = TandemTheme.sizes.minTouchTarget + spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = TandemTheme.sizes.minTouchTarget + spacing.sm)
                .toggleable(
                    value = row.atHome,
                    role = Role.Checkbox,
                    enabled = !readOnly,
                    onValueChange = { onAction(StaplesAction.Toggle(row.name)) },
                )
                .padding(horizontal = spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Checkbox(checked = row.atHome, onCheckedChange = null, enabled = !readOnly)
            Text(text = title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        }
        if (row.known && !readOnly) {
            IconButton(onClick = { onAction(StaplesAction.Remove(row.name)) }) {
                Icon(TandemIcons.Delete, contentDescription = stringResource(R.string.pantry_staples_remove, title))
            }
        }
    }
}

@Composable
private fun AddStaple(text: String, onAction: (StaplesAction) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TandemTheme.spacing.sm),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { onAction(StaplesAction.TypedText(it)) },
            label = { Text(stringResource(R.string.pantry_staples_add_label)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onAction(StaplesAction.AddTyped) }),
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { onAction(StaplesAction.AddTyped) }, enabled = text.isNotBlank()) {
            Icon(TandemIcons.Add, contentDescription = stringResource(R.string.pantry_staples_add))
        }
    }
}

/** "Spara" and "Inte nu", pinned below the list (and above the keyboard). */
@Composable
private fun StaplesActions(state: StaplesUiState, onAction: (StaplesAction) -> Unit) {
    val spacing = TandemTheme.spacing
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = spacing.screenHorizontal, vertical = spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            TandemPrimaryButton(
                text = stringResource(R.string.pantry_save),
                onClick = { onAction(StaplesAction.Save) },
                enabled = state.canSave,
                leadingIcon = TandemIcons.Check,
                modifier = Modifier
                    .widthIn(max = TandemTheme.sizes.maxReadingWidth)
                    .fillMaxWidth()
                    .heightIn(min = TandemTheme.sizes.kitchenTouchTarget)
                    .testTag(STAPLES_SAVE_TAG),
            )
            TandemTextButton(
                text = stringResource(R.string.pantry_staples_not_now),
                onClick = { onAction(StaplesAction.Close) },
                modifier = Modifier.heightIn(min = TandemTheme.sizes.minTouchTarget),
            )
        }
    }
}
