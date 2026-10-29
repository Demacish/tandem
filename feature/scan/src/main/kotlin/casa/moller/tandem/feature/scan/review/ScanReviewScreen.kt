package casa.moller.tandem.feature.scan.review

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import casa.moller.tandem.core.designsystem.component.InfoCard
import casa.moller.tandem.core.designsystem.component.StatusChip
import casa.moller.tandem.core.designsystem.component.TandemButtonSize
import casa.moller.tandem.core.designsystem.component.TandemPrimaryButton
import casa.moller.tandem.core.designsystem.component.TandemTextButton
import casa.moller.tandem.core.designsystem.component.TandemTextField
import casa.moller.tandem.core.designsystem.component.TandemTone
import casa.moller.tandem.core.designsystem.component.TandemTopAppBar
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.scan.ScanReviewItem
import casa.moller.tandem.core.model.scan.ScanSource
import casa.moller.tandem.core.ui.EmptyState
import casa.moller.tandem.core.ui.LoadingState
import casa.moller.tandem.core.ui.readonly.ReadOnlyBanner
import casa.moller.tandem.feature.scan.R
import casa.moller.tandem.feature.scan.ui.ExpiryChips
import casa.moller.tandem.feature.scan.ui.FoodPicker
import casa.moller.tandem.feature.scan.ui.LocationChips
import casa.moller.tandem.feature.scan.ui.ScanFormat
import casa.moller.tandem.feature.scan.ui.ScanIcons
import casa.moller.tandem.feature.scan.ui.ScanRowStatus
import casa.moller.tandem.feature.scan.ui.ScanSectionTitle
import casa.moller.tandem.feature.scan.ui.rememberScanFormat

/** Test tag of "Spara n varor i skafferiet". */
const val SCAN_REVIEW_SAVE_TAG = "scan:review:save"

/** Test tag of the edit's "Klar". */
const val SCAN_REVIEW_APPLY_TAG = "scan:review:apply"

/** Test tag of one review row. */
fun scanReviewRowTag(key: String): String = "scan:review:row:$key"

/**
 * The shared review list (plan §8.8), stateless and public so every scanner ends here (barcode,
 * receipt, and the ai-foto lane's fridge photo; docs/BYGGE.md "Skanning"). The rows to check first
 * (no food yet), the ready ones and the skipped ones; each row is one TalkBack item ("Grädde, 5 dl,
 * Kyl, går ut 5 nov., förslag") that opens the edit (food, amount, unit, place, expiry, "lär dig").
 * "Spara n varor i skafferiet" stays pinned below; it is the only step that writes. The edit is a
 * bottom sheet on phones and a pane beside the list on wide windows (plan §6.5 list-detail).
 */
@Composable
fun ScanReviewScreen(
    state: ScanReviewUiState,
    onAction: (ScanReviewAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val format = rememberScanFormat()
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TandemTopAppBar(
                title = stringResource(R.string.scan_review_title),
                subtitle = if (state.missing || state.loading) null else stringResource(state.source.label()),
                onNavigateBack = onBack,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = { if (!state.missing && !state.loading) ReviewSaveBar(state = state, onAction = onAction) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        when {
            state.loading -> LoadingState(modifier = Modifier.fillMaxSize().padding(padding))

            state.missing -> EmptyState(
                icon = ScanIcons.Barcode,
                title = stringResource(R.string.scan_review_missing_title),
                body = stringResource(R.string.scan_review_missing_body),
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                TandemPrimaryButton(
                    text = stringResource(R.string.scan_review_scan_again),
                    onClick = onBack,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            else -> BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding(),
            ) {
                val edit = state.edit
                if (maxWidth >= WIDE && edit != null) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        // The pane has its own place chips: the list's default ones wait until the edit is done.
                        ReviewList(
                            state = state,
                            format = format,
                            onAction = onAction,
                            showDefaultLocation = false,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.width(PANE_WIDTH).fillMaxHeight(),
                        ) {
                            ReviewEditLayout(
                                edit = edit,
                                state = state,
                                onAction = onAction,
                                modifier = Modifier.padding(
                                    horizontal = TandemTheme.spacing.screenHorizontal,
                                    vertical = TandemTheme.spacing.lg,
                                ),
                            )
                        }
                    }
                } else {
                    ReviewList(state = state, format = format, onAction = onAction, modifier = Modifier.fillMaxSize())
                    if (edit != null) ReviewEditSheet(edit = edit, state = state, onAction = onAction)
                }
            }
        }
    }
}

@Composable
private fun ReviewList(
    state: ScanReviewUiState,
    format: ScanFormat,
    onAction: (ScanReviewAction) -> Unit,
    modifier: Modifier = Modifier,
    showDefaultLocation: Boolean = true,
) {
    val spacing = TandemTheme.spacing
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = spacing.screenHorizontal, vertical = spacing.lg),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (state.readOnly) item(key = "read-only") { ReadOnlyBanner(modifier = Modifier.widthIn(max = TandemTheme.sizes.maxReadingWidth)) }
        if (state.source == ScanSource.FRIDGE) {
            item(key = "ai-warning") {
                InfoCard(
                    title = stringResource(R.string.scan_review_ai_title),
                    body = stringResource(R.string.scan_review_ai_body),
                    tone = TandemTone.Warning,
                    modifier = Modifier.widthIn(max = TandemTheme.sizes.maxReadingWidth),
                )
            }
        }
        if (showDefaultLocation && !state.readOnly && state.locations.isNotEmpty()) {
            item(key = "default-location") {
                Column(
                    modifier = Modifier
                        .widthIn(max = TandemTheme.sizes.maxReadingWidth)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(spacing.xs),
                ) {
                    ScanSectionTitle(stringResource(R.string.scan_review_default_location))
                    LocationChips(
                        locations = state.locations,
                        selectedId = state.defaultLocationId,
                        onSelect = { onAction(ScanReviewAction.SetDefaultLocation(it)) },
                    )
                }
            }
        }
        if (state.rows.isEmpty()) {
            item(key = "empty") {
                Text(
                    text = stringResource(R.string.scan_review_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(vertical = spacing.xl),
                )
            }
        }
        section("check", R.string.scan_review_to_check, state.toCheck, state, format, onAction)
        section("ready", R.string.scan_review_ready, state.ready, state, format, onAction)
        section("skipped", R.string.scan_review_skipped, state.skipped, state, format, onAction)
    }
}

private fun LazyListScope.section(
    key: String,
    title: Int,
    rows: List<ScanReviewItem>,
    state: ScanReviewUiState,
    format: ScanFormat,
    onAction: (ScanReviewAction) -> Unit,
) {
    if (rows.isEmpty()) return
    item(key = "title-$key") {
        Text(
            text = stringResource(title, rows.size),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .widthIn(max = TandemTheme.sizes.maxReadingWidth)
                .fillMaxWidth()
                .padding(top = TandemTheme.spacing.sm)
                .semantics { heading() },
        )
    }
    items(rows, key = { it.key }) { item ->
        ReviewRow(
            item = item,
            place = state.locationOf(item)?.name,
            format = format,
            selected = state.edit?.key == item.key,
            onAction = onAction,
            modifier = Modifier.widthIn(max = TandemTheme.sizes.maxReadingWidth),
        )
    }
}

/** One row: ONE TalkBack item ("Grädde, 5 dl, Kyl, går ut 5 nov., förslag, kvittorad GRÄDDE 36% 5DL"); tap to edit. */
@Composable
private fun ReviewRow(
    item: ScanReviewItem,
    place: String?,
    format: ScanFormat,
    selected: Boolean,
    onAction: (ScanReviewAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = TandemTheme.spacing
    val status = ScanRowStatus.of(item)
    val statusText = stringResource(status.label)
    val defaultUnit = stringResource(R.string.scan_unit_default)
    val title = item.food?.name ?: item.text
    val details = listOfNotNull(
        format.amount(item.amount, item.unit, defaultUnit),
        place,
        item.expires?.let { stringResource(R.string.scan_expires_on, format.date(it)) },
    ).joinToString(" · ")
    val source = item.barcode ?: item.text.takeIf { item.food != null && !it.equals(item.food?.name, ignoreCase = true) }
    val description = listOfNotNull(
        title,
        details.replace(" · ", ", "),
        statusText,
        source?.let { stringResource(item.source.sourceLabel(), it) },
    ).joinToString(", ")
    val editLabel = stringResource(R.string.scan_review_edit)
    val toggleLabel = stringResource(if (item.include) R.string.scan_review_skip else R.string.scan_review_include)
    val removeLabel = stringResource(R.string.scan_remove)
    val stateText = stringResource(if (item.include) R.string.scan_review_included else R.string.scan_status_skipped)
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .testTag(scanReviewRowTag(item.key))
                .clickable(onClickLabel = editLabel) { onAction(ScanReviewAction.Edit(item.key)) }
                .clearAndSetSemantics {
                    contentDescription = description
                    stateDescription = stateText
                    onClick(label = editLabel) {
                        onAction(ScanReviewAction.Edit(item.key))
                        true
                    }
                    customActions = listOf(
                        CustomAccessibilityAction(toggleLabel) {
                            onAction(ScanReviewAction.ToggleInclude(item.key))
                            true
                        },
                        CustomAccessibilityAction(removeLabel) {
                            onAction(ScanReviewAction.Remove(item.key))
                            true
                        },
                    )
                }
                .heightIn(min = TandemTheme.sizes.kitchenTouchTarget)
                .padding(horizontal = spacing.sm, vertical = spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Checkbox(checked = item.include, onCheckedChange = { onAction(ScanReviewAction.ToggleInclude(item.key)) })
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge)
                if (details.isNotBlank()) {
                    Text(text = details, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (source != null) {
                    Text(
                        text = source,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                StatusChip(label = statusText, tone = status.tone)
            }
        }
    }
}

/** "n rader saknar livsmedel" and "Spara n varor i skafferiet", pinned below the list. */
@Composable
private fun ReviewSaveBar(state: ScanReviewUiState, onAction: (ScanReviewAction) -> Unit) {
    val spacing = TandemTheme.spacing
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .padding(horizontal = spacing.screenHorizontal, vertical = spacing.md)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (state.unmatchedCount > 0) {
                Text(
                    text = pluralStringResource(R.plurals.scan_review_unmatched, state.unmatchedCount, state.unmatchedCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!state.readOnly) {
                TandemPrimaryButton(
                    text = if (state.readyCount > 0) {
                        pluralStringResource(R.plurals.scan_review_save, state.readyCount, state.readyCount)
                    } else {
                        stringResource(R.string.scan_review_save_none)
                    },
                    onClick = { onAction(ScanReviewAction.Save) },
                    enabled = state.readyCount > 0 && !state.saving,
                    leadingIcon = TandemIcons.CheckCircle,
                    size = TandemButtonSize.Kitchen,
                    modifier = Modifier
                        .widthIn(max = TandemTheme.sizes.maxReadingWidth)
                        .fillMaxWidth()
                        .testTag(SCAN_REVIEW_SAVE_TAG),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewEditSheet(edit: ReviewEdit, state: ScanReviewUiState, onAction: (ScanReviewAction) -> Unit) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    ModalBottomSheet(onDismissRequest = { onAction(ScanReviewAction.CancelEdit) }, sheetState = sheetState) {
        ReviewEditLayout(
            edit = edit,
            state = state,
            onAction = onAction,
            modifier = Modifier
                .padding(horizontal = TandemTheme.spacing.screenHorizontal)
                .padding(bottom = TandemTheme.spacing.xl)
                .imePadding(),
        )
    }
}

/**
 * The edit of one row: the food (suggestions, search, "Skapa"), amount and unit, place, expiry,
 * "Kom ihåg raden" and "Hoppa över"; "Klar" and "Avbryt" stay pinned below the scrolling part
 * (like the recipe's shopping sheet, so the buttons are always reachable).
 */
@Composable
fun ReviewEditLayout(edit: ReviewEdit, state: ScanReviewUiState, onAction: (ScanReviewAction) -> Unit, modifier: Modifier = Modifier) {
    val spacing = TandemTheme.spacing
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing.md)) {
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            ReviewEditFields(edit = edit, state = state, onAction = onAction)
        }
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            TandemPrimaryButton(
                text = stringResource(R.string.scan_review_apply),
                onClick = { onAction(ScanReviewAction.ApplyEdit) },
                enabled = !edit.amountError,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SCAN_REVIEW_APPLY_TAG),
            )
            TandemTextButton(
                text = stringResource(R.string.scan_cancel),
                onClick = { onAction(ScanReviewAction.CancelEdit) },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}

@Composable
private fun ReviewEditFields(edit: ReviewEdit, state: ScanReviewUiState, onAction: (ScanReviewAction) -> Unit) {
    val spacing = TandemTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
        Text(
            text = stringResource(R.string.scan_review_edit_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = edit.barcode ?: edit.text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    ScanSectionTitle(stringResource(R.string.scan_review_food))
    Text(
        text = edit.food?.name ?: stringResource(R.string.scan_status_unknown),
        style = MaterialTheme.typography.titleMedium,
    )
    if (!state.readOnly) {
        FoodPicker(
            picker = edit.picker,
            onQuery = { onAction(ScanReviewAction.EditQuery(it)) },
            onPick = { onAction(ScanReviewAction.EditPick(it)) },
            onCreate = { onAction(ScanReviewAction.EditCreate(it)) },
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm), modifier = Modifier.fillMaxWidth()) {
        TandemTextField(
            value = edit.amount,
            onValueChange = { onAction(ScanReviewAction.EditAmount(it)) },
            label = stringResource(R.string.scan_review_amount),
            placeholder = "1",
            isError = edit.amountError,
            supportingText = if (edit.amountError) stringResource(R.string.scan_review_amount_error) else null,
            modifier = Modifier.weight(1f),
        )
        TandemTextField(
            value = edit.unit,
            onValueChange = { onAction(ScanReviewAction.EditUnit(it)) },
            label = stringResource(R.string.scan_review_unit),
            placeholder = stringResource(R.string.scan_unit_default),
            modifier = Modifier.weight(1f),
        )
    }
    if (state.locations.isNotEmpty()) {
        val location = state.locations.firstOrNull { it.id == (edit.locationId ?: state.defaultLocationId) }
        ScanSectionTitle(stringResource(R.string.scan_where))
        LocationChips(
            locations = state.locations,
            selectedId = location?.id,
            onSelect = { onAction(ScanReviewAction.EditLocation(it)) },
        )
        ScanSectionTitle(stringResource(R.string.scan_best_before))
        ExpiryChips(
            location = location,
            selected = edit.expires,
            today = state.today,
            onSelect = { onAction(ScanReviewAction.EditExpiry(it)) },
        )
    }
    if (edit.canLearn && edit.food != null) {
        SwitchRow(
            title = stringResource(R.string.scan_review_learn),
            body = stringResource(R.string.scan_review_learn_body, edit.barcode ?: edit.text),
            checked = edit.learn,
            onChange = { onAction(ScanReviewAction.EditLearn(it)) },
        )
    }
    SwitchRow(
        title = stringResource(R.string.scan_review_include_row),
        body = null,
        checked = edit.include,
        onChange = { onAction(ScanReviewAction.EditInclude(it)) },
    )
}

/** A switch with its text as one toggle (one TalkBack item, the whole row is the target). */
@Composable
private fun SwitchRow(title: String, body: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TandemTheme.sizes.minTouchTarget)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TandemTheme.spacing.md),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (body != null) {
                Text(text = body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** The subtitle of the review for [this] source. */
private fun ScanSource.label(): Int = when (this) {
    ScanSource.BARCODE -> R.string.scan_review_source_barcode
    ScanSource.RECEIPT -> R.string.scan_review_source_receipt
    ScanSource.FRIDGE -> R.string.scan_review_source_fridge
}

/** "streckkod %s" / "kvittorad %s" / "AI:n såg %s" for TalkBack. */
private fun ScanSource.sourceLabel(): Int = when (this) {
    ScanSource.BARCODE -> R.string.scan_review_from_barcode
    ScanSource.RECEIPT -> R.string.scan_review_from_receipt
    ScanSource.FRIDGE -> R.string.scan_review_from_fridge
}

private val WIDE = 840.dp
private val PANE_WIDTH = 420.dp
