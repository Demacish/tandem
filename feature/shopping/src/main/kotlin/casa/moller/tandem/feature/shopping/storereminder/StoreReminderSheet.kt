package casa.moller.tandem.feature.shopping.storereminder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import casa.moller.tandem.core.designsystem.component.TandemLoadingIndicator
import casa.moller.tandem.core.designsystem.component.TandemPrimaryButton
import casa.moller.tandem.core.designsystem.component.TandemSecondaryButton
import casa.moller.tandem.core.designsystem.component.TandemTextButton
import casa.moller.tandem.core.designsystem.component.TandemTextField
import casa.moller.tandem.core.designsystem.component.TandemTextFieldType
import casa.moller.tandem.core.designsystem.component.TandemTonalButton
import casa.moller.tandem.core.designsystem.icon.Delete
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.domain.storereminder.StoreReminderRadius
import casa.moller.tandem.core.model.shopping.StoreSpotSource
import casa.moller.tandem.feature.shopping.R
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.time.Instant

/** Test tag of the open store's own switch. */
const val STORE_REMINDER_SHEET_SWITCH_TAG = "shopping:store-reminder:sheet-switch"

/** Test tag of "Jag är i butiken nu – använd min position". */
const val STORE_REMINDER_USE_LOCATION_TAG = "shopping:store-reminder:use-location"

/** Test tag of the place field. */
const val STORE_REMINDER_PASTE_TAG = "shopping:store-reminder:paste"

/** Test tag of "Spara platsen". */
const val STORE_REMINDER_SAVE_PASTED_TAG = "shopping:store-reminder:save-pasted"

/** Test tag of the distance slider. */
const val STORE_REMINDER_RADIUS_TAG = "shopping:store-reminder:radius"

/** The open store's sheet (phones and tablets alike); "Klar" stays below the scrolling part. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StoreReminderSheet(sheet: StoreReminderSheetState, canOpenMaps: Boolean, onAction: (StoreReminderAction) -> Unit) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    ModalBottomSheet(onDismissRequest = { onAction(StoreReminderAction.CloseStore) }, sheetState = sheetState) {
        StoreReminderSheetLayout(
            sheet = sheet,
            canOpenMaps = canOpenMaps,
            onAction = onAction,
            modifier = Modifier
                .padding(horizontal = TandemTheme.spacing.screenHorizontal)
                .padding(bottom = TandemTheme.spacing.xl)
                .imePadding(),
        )
    }
}

/** The sheet's layout: the content scrolls, "Klar" is pinned below it (reachable at font scale 2). */
@Composable
fun StoreReminderSheetLayout(
    sheet: StoreReminderSheetState,
    canOpenMaps: Boolean,
    onAction: (StoreReminderAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = TandemTheme.spacing
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing.md)) {
        StoreReminderSheetContent(
            sheet = sheet,
            canOpenMaps = canOpenMaps,
            onAction = onAction,
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
        )
        TandemPrimaryButton(
            text = stringResource(R.string.store_reminder_done),
            onClick = { onAction(StoreReminderAction.CloseStore) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * The store's name; with a place: the store's own switch, when and how the place was set, the
 * distance (100–500 m) and "Visa på kartan"/"Ta bort platsen"; always: "Jag är i butiken nu – använd
 * min position" and a field for a pasted geo: link, map link or coordinates.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StoreReminderSheetContent(
    sheet: StoreReminderSheetState,
    canOpenMaps: Boolean,
    onAction: (StoreReminderAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = TandemTheme.spacing
    val spot = sheet.spot
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing.md)) {
        Text(text = sheet.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        if (spot != null) {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column {
                    SwitchRow(
                        title = stringResource(R.string.store_reminder_sheet_switch),
                        body = stringResource(R.string.store_reminder_sheet_switch_body, sheet.radius),
                        checked = spot.enabled,
                        enabled = true,
                        onChange = { onAction(StoreReminderAction.SetStoreEnabled(it)) },
                        modifier = Modifier.testTag(STORE_REMINDER_SHEET_SWITCH_TAG),
                    )
                    GroupDivider()
                    Radius(radius = sheet.radius, onAction = onAction)
                }
            }
            spot.savedAt?.let { saved ->
                val text = if (spot.source == StoreSpotSource.CURRENT_LOCATION) {
                    stringResource(R.string.store_reminder_saved_here, date(saved))
                } else {
                    stringResource(R.string.store_reminder_saved_pasted, date(saved))
                }
                Text(text = text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                if (canOpenMaps) {
                    TandemTonalButton(
                        text = stringResource(R.string.store_reminder_show_map),
                        onClick = { onAction(StoreReminderAction.ShowOnMap) },
                        leadingIcon = TandemIcons.OpenInNew,
                    )
                }
                TandemTextButton(
                    text = stringResource(R.string.store_reminder_forget),
                    onClick = { onAction(StoreReminderAction.ForgetPlace) },
                    leadingIcon = TandemIcons.Delete,
                )
            }
        }
        Text(
            text = stringResource(if (spot == null) R.string.store_reminder_where else R.string.store_reminder_where_change),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        TandemTonalButton(
            text = stringResource(R.string.store_reminder_use_location),
            onClick = { onAction(StoreReminderAction.UseCurrentLocation) },
            enabled = !sheet.locating,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(STORE_REMINDER_USE_LOCATION_TAG),
        )
        if (sheet.locating) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm), itemVerticalAlignment = Alignment.CenterVertically) {
                TandemLoadingIndicator()
                Text(
                    text = stringResource(R.string.store_reminder_locating),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
        Text(
            text = stringResource(R.string.store_reminder_or_paste),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TandemTextField(
            value = sheet.pasteText,
            onValueChange = { onAction(StoreReminderAction.PasteChanged(it)) },
            label = stringResource(R.string.store_reminder_paste_label),
            type = TandemTextFieldType.Url,
            leadingIcon = TandemIcons.ContentPaste,
            supportingText = stringResource(
                if (sheet.pasteError) R.string.store_reminder_paste_error else R.string.store_reminder_paste_hint,
            ),
            isError = sheet.pasteError,
            imeAction = ImeAction.Done,
            keyboardActions = KeyboardActions(onDone = { onAction(StoreReminderAction.SavePasted) }),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(STORE_REMINDER_PASTE_TAG),
        )
        TandemSecondaryButton(
            text = stringResource(R.string.store_reminder_paste_save),
            onClick = { onAction(StoreReminderAction.SavePasted) },
            enabled = sheet.pasteText.isNotBlank(),
            modifier = Modifier.testTag(STORE_REMINDER_SAVE_PASTED_TAG),
        )
    }
}

/** "Avstånd: 200 m" and the slider (100–500 m in 50 m steps); stored when the slider is let go. */
@Composable
private fun Radius(radius: Int, onAction: (StoreReminderAction) -> Unit) {
    val spacing = TandemTheme.spacing
    val label = stringResource(R.string.store_reminder_radius_label)
    val value = stringResource(R.string.store_reminder_radius_value, radius)
    Column(modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.md)) {
        Text(text = stringResource(R.string.store_reminder_radius, radius), style = MaterialTheme.typography.titleSmall)
        Slider(
            value = radius.toFloat(),
            onValueChange = { onAction(StoreReminderAction.RadiusChanged(it)) },
            onValueChangeFinished = { onAction(StoreReminderAction.RadiusDone) },
            valueRange = StoreReminderRadius.MIN_METERS.toFloat()..StoreReminderRadius.MAX_METERS.toFloat(),
            steps = StoreReminderRadius.SLIDER_STEPS,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(STORE_REMINDER_RADIUS_TAG)
                .semantics {
                    contentDescription = label
                    stateDescription = value
                },
        )
    }
}

/** "14 nov. 2026" in the app's language and the phone's zone. */
@Composable
private fun date(instant: Instant): String {
    val locale = LocalConfiguration.current.locales[0]
    return DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        .withLocale(locale)
        .format(java.time.Instant.ofEpochMilli(instant.toEpochMilliseconds()).atZone(ZoneId.systemDefault()))
}
