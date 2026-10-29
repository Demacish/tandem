package casa.moller.tandem.feature.pantry.hemma

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.ui.pantry.foodTitle
import casa.moller.tandem.core.ui.recipe.currentLocale
import casa.moller.tandem.feature.pantry.R

/** Hemma's dialogs: "Flytta till …", adding or renaming a place, and "Ta bort platsen?". */
@Composable
internal fun PantryDialogs(state: PantryUiState, onAction: (PantryAction) -> Unit) {
    state.moving?.let { row -> MoveDialog(row = row, state = state, onAction = onAction) }
    state.locationEditor?.let { editor -> LocationEditorDialog(editor = editor, onAction = onAction) }
    state.confirmDelete?.let { location ->
        AlertDialog(
            onDismissRequest = { onAction(PantryAction.ConfirmDeleteLocation(false)) },
            title = { Text(stringResource(R.string.pantry_location_delete_title, location.name)) },
            text = { Text(stringResource(R.string.pantry_location_delete_body)) },
            confirmButton = {
                TextButton(onClick = { onAction(PantryAction.ConfirmDeleteLocation(true)) }) {
                    Text(stringResource(R.string.pantry_location_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { onAction(PantryAction.ConfirmDeleteLocation(false)) }) {
                    Text(stringResource(R.string.pantry_cancel))
                }
            },
        )
    }
}

/** "Flytta grädde till …": every place but the current one, one tap moves (with "Ångra"). */
@Composable
private fun MoveDialog(row: PantryRow, state: PantryUiState, onAction: (PantryAction) -> Unit) {
    val name = foodTitle(row.entry.food.name, currentLocale())
    AlertDialog(
        onDismissRequest = { onAction(PantryAction.CancelMove) },
        title = { Text(stringResource(R.string.pantry_move_title, name)) },
        text = {
            Column(modifier = Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
                state.locations.forEach { location ->
                    val current = location.id == row.entry.locationId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = TandemTheme.sizes.minTouchTarget)
                            .selectable(
                                selected = current,
                                role = Role.RadioButton,
                                onClick = { onAction(PantryAction.MoveTo(location.id)) },
                            )
                            .padding(horizontal = TandemTheme.spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(TandemTheme.spacing.md),
                    ) {
                        RadioButton(selected = current, onClick = null)
                        Text(text = location.name, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = { onAction(PantryAction.CancelMove) }) { Text(stringResource(R.string.pantry_cancel)) }
        },
    )
}

/** A new place, or a new name (and freezer or not) for one. */
@Composable
private fun LocationEditorDialog(editor: LocationEditor, onAction: (PantryAction) -> Unit) {
    AlertDialog(
        onDismissRequest = { onAction(PantryAction.CloseLocationEditor) },
        title = { Text(stringResource(if (editor.id == null) R.string.pantry_location_new else R.string.pantry_location_rename)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(TandemTheme.spacing.md)) {
                OutlinedTextField(
                    value = editor.name,
                    onValueChange = { onAction(PantryAction.LocationName(it)) },
                    label = { Text(stringResource(R.string.pantry_location_name)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onAction(PantryAction.SaveLocation) }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = TandemTheme.sizes.minTouchTarget)
                        .toggleable(
                            value = editor.isFreezer,
                            role = Role.Switch,
                            onValueChange = { onAction(PantryAction.LocationFreezer(it)) },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(TandemTheme.spacing.md),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = stringResource(R.string.pantry_location_freezer), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = stringResource(R.string.pantry_location_freezer_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = editor.isFreezer, onCheckedChange = null)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAction(PantryAction.SaveLocation) }, enabled = editor.canSave) {
                Text(stringResource(R.string.pantry_save))
            }
        },
        dismissButton = {
            TextButton(onClick = { onAction(PantryAction.CloseLocationEditor) }) { Text(stringResource(R.string.pantry_cancel)) }
        },
    )
}
