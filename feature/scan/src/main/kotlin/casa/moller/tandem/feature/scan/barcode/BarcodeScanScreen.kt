package casa.moller.tandem.feature.scan.barcode

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import casa.moller.tandem.core.designsystem.component.InfoCard
import casa.moller.tandem.core.designsystem.component.StatusChip
import casa.moller.tandem.core.designsystem.component.TandemPrimaryButton
import casa.moller.tandem.core.designsystem.component.TandemSecondaryButton
import casa.moller.tandem.core.designsystem.component.TandemTextButton
import casa.moller.tandem.core.designsystem.component.TandemTopAppBar
import casa.moller.tandem.core.designsystem.icon.Delete
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.ui.readonly.ReadOnlyBanner
import casa.moller.tandem.feature.scan.R
import casa.moller.tandem.feature.scan.camera.CameraAccess
import casa.moller.tandem.feature.scan.ui.CameraAccessPanel
import casa.moller.tandem.feature.scan.ui.CameraPlaceholder
import casa.moller.tandem.feature.scan.ui.ExpiryChips
import casa.moller.tandem.feature.scan.ui.FoodPicker
import casa.moller.tandem.feature.scan.ui.LocationChips
import casa.moller.tandem.feature.scan.ui.ScanIcons
import casa.moller.tandem.feature.scan.ui.ScanRowStatus
import casa.moller.tandem.feature.scan.ui.ScanSectionTitle
import casa.moller.tandem.feature.scan.ui.rememberScanFormat

/** The camera as the scan screens see it: the permission, and the torch when the camera has one. */
data class ScanCameraUi(val access: CameraAccess, val torchAvailable: Boolean = false, val torchOn: Boolean = false)

/** What the scan screens ask of the camera (handled by the route, not the ViewModel). */
enum class ScanCameraAction {
    ALLOW,
    PICK_IMAGE,
    TOGGLE_TORCH,
}

/** Test tag of the "Lägg i …" / "Granska n varor" button. */
const val BARCODE_PRIMARY_TAG = "scan:barcode:primary"

/** Test tag of the "Lägg på inköpslistan" button. */
const val BARCODE_SHOPPING_TAG = "scan:barcode:shopping"

/** Test tag of the selected package's card. */
const val BARCODE_CARD_TAG = "scan:barcode:card"

/** Test tag of an earlier package in the session. */
fun barcodeRowTag(key: String): String = "scan:barcode:row:$key"

/**
 * The barcode scanner (#27, plan §8.8), stateless: the camera (or why it is needed and the photo
 * picker) with a frame to aim, and the panel with the package just scanned: its food (or the
 * suggestions and the picker), the place and expiry quick picks, "Lägg i Kyl" or "Lägg på
 * inköpslistan", and the earlier packages of the session ("Granska n varor" with several). Camera
 * and panel side by side in landscape and on tablets, stacked on a phone in portrait.
 *
 * @param preview the live camera picture (the route's CameraX preview; a placeholder in tests).
 */
@Composable
fun BarcodeScanScreen(
    state: BarcodeScanUiState,
    camera: ScanCameraUi,
    onAction: (BarcodeScanAction) -> Unit,
    onCamera: (ScanCameraAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    preview: @Composable (Modifier) -> Unit = { CameraPlaceholder(it) },
) {
    // One preview whichever layout shows it (a phone turned to landscape keeps its camera view).
    val cameraPreview = remember(preview) { movableContentOf(preview) }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TandemTopAppBar(
                title = stringResource(R.string.scan_barcode_title),
                onNavigateBack = onBack,
                actions = {
                    if (camera.access == CameraAccess.GRANTED && camera.torchAvailable) {
                        IconButton(onClick = { onCamera(ScanCameraAction.TOGGLE_TORCH) }) {
                            Icon(
                                imageVector = if (camera.torchOn) ScanIcons.TorchOff else ScanIcons.TorchOn,
                                contentDescription = stringResource(
                                    if (camera.torchOn) R.string.scan_torch_off else R.string.scan_torch_on,
                                ),
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            val width = maxWidth
            val height = maxHeight
            val sideBySide = width > height || width >= WIDE
            if (sideBySide) {
                Row(modifier = Modifier.fillMaxSize()) {
                    CameraArea(
                        camera = camera,
                        onCamera = onCamera,
                        preview = cameraPreview,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                    BarcodePanel(
                        state = state,
                        cameraOn = camera.access == CameraAccess.GRANTED,
                        onAction = onAction,
                        onCamera = onCamera,
                        modifier = Modifier.width(minOf(PANEL_WIDTH, width / 2)).fillMaxHeight(),
                    )
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    CameraArea(
                        camera = camera,
                        onCamera = onCamera,
                        preview = cameraPreview,
                        modifier = Modifier.fillMaxWidth().height(maxOf(MIN_CAMERA_HEIGHT, height * CAMERA_SHARE)),
                    )
                    BarcodePanel(
                        state = state,
                        cameraOn = camera.access == CameraAccess.GRANTED,
                        onAction = onAction,
                        onCamera = onCamera,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/** The preview with a frame to aim at, or the permission panel. */
@Composable
private fun CameraArea(
    camera: ScanCameraUi,
    onCamera: (ScanCameraAction) -> Unit,
    preview: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (camera.access != CameraAccess.GRANTED) {
        CameraAccessPanel(
            access = camera.access,
            rationale = R.string.scan_camera_barcode_rationale,
            onAllow = { onCamera(ScanCameraAction.ALLOW) },
            modifier = modifier,
        )
        return
    }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        preview(Modifier.fillMaxSize())
        Box(
            modifier = Modifier
                .fillMaxWidth(VIEWFINDER_WIDTH)
                .height(VIEWFINDER_HEIGHT)
                .border(BorderStroke(3.dp, MaterialTheme.colorScheme.inversePrimary), MaterialTheme.shapes.large),
        )
        Surface(
            color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.85f),
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(TandemTheme.spacing.md),
        ) {
            Text(
                text = stringResource(R.string.scan_barcode_aim),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = TandemTheme.spacing.md, vertical = TandemTheme.spacing.sm),
            )
        }
    }
}

/**
 * The panel: the selected package, the earlier ones, and the buttons pinned below. Without the
 * camera ([cameraOn] false) the photo picker is the way in, so it is a full button.
 */
@Composable
private fun BarcodePanel(
    state: BarcodeScanUiState,
    cameraOn: Boolean,
    onAction: (BarcodeScanAction) -> Unit,
    onCamera: (ScanCameraAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = TandemTheme.spacing
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = spacing.screenHorizontal, vertical = spacing.lg),
                verticalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                if (state.readOnly) ReadOnlyBanner(compact = true)
                if (state.demo) {
                    InfoCard(title = stringResource(R.string.scan_demo_title), body = stringResource(R.string.scan_barcode_demo_body))
                }
                val selected = state.selected
                if (selected == null) {
                    EmptyPanel()
                } else {
                    SelectedCard(row = selected, state = state, onAction = onAction)
                    val others = state.rows.filter { it.item.key != selected.item.key }
                    if (others.isNotEmpty()) {
                        ScanSectionTitle(pluralStringResource(R.plurals.scan_barcode_scanned, state.rows.size, state.rows.size))
                        others.forEach { row ->
                            OtherRow(row = row, state = state, onSelect = { onAction(BarcodeScanAction.Select(row.item.key)) })
                        }
                    }
                }
                if (cameraOn) {
                    TandemTextButton(
                        text = stringResource(R.string.scan_pick_image),
                        onClick = { onCamera(ScanCameraAction.PICK_IMAGE) },
                        leadingIcon = ScanIcons.Gallery,
                    )
                } else {
                    TandemSecondaryButton(
                        text = stringResource(R.string.scan_pick_image),
                        onClick = { onCamera(ScanCameraAction.PICK_IMAGE) },
                        leadingIcon = ScanIcons.Gallery,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (state.rows.isNotEmpty() && !state.readOnly) {
                BarcodeActions(state = state, onAction = onAction)
            }
        }
    }
}

@Composable
private fun EmptyPanel() {
    Column(verticalArrangement = Arrangement.spacedBy(TandemTheme.spacing.sm)) {
        Text(
            text = stringResource(R.string.scan_barcode_empty_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(R.string.scan_barcode_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The package just scanned (or chosen): what it is, then where it goes. */
@Composable
private fun SelectedCard(row: BarcodeRow, state: BarcodeScanUiState, onAction: (BarcodeScanAction) -> Unit) {
    val spacing = TandemTheme.spacing
    val item = row.item
    val key = item.key
    val location = state.locationOf(row)
    Surface(
        shape = MaterialTheme.shapes.large,
        // Lowest (not Highest): primary-coloured text buttons need >= 4.5:1 (core:designsystem Color.kt).
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(BARCODE_CARD_TAG),
    ) {
        Column(modifier = Modifier.padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.md)) {
            val title = item.food?.name ?: row.productName ?: stringResource(R.string.scan_barcode_unknown_item)
            val subtitle = listOfNotNull(row.productName?.takeIf { item.food != null }, item.barcode).joinToString(" · ")
            val status = stringResource(row.status.label)
            // One TalkBack item, read again when the lookup finishes ("Grädde, känd vara").
            Column(
                modifier = Modifier.clearAndSetSemantics {
                    contentDescription = listOf(title, subtitle, status).filter { it.isNotBlank() }.joinToString(", ")
                    liveRegion = LiveRegionMode.Polite
                },
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                Text(text = title, style = MaterialTheme.typography.titleLarge)
                if (subtitle.isNotBlank()) {
                    Text(text = subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StatusChip(label = status, tone = row.status.tone)
            }
            val picker = state.picker
            when {
                state.readOnly -> Unit

                picker != null -> {
                    FoodPicker(
                        picker = picker,
                        onQuery = { onAction(BarcodeScanAction.PickerQuery(it)) },
                        onPick = { onAction(BarcodeScanAction.ChooseFood(key, it)) },
                        onCreate = { onAction(BarcodeScanAction.CreateFood(key, it)) },
                        current = item.food?.name,
                    )
                    TandemTextButton(text = stringResource(R.string.scan_cancel), onClick = { onAction(BarcodeScanAction.ClosePicker) })
                }

                row.status != ScanRowStatus.LOOKING_UP -> {
                    if (item.food == null && row.suggestions.isNotEmpty()) {
                        ScanSectionTitle(stringResource(R.string.scan_food_suggestions))
                        row.suggestions.forEach { food ->
                            TandemSecondaryButton(
                                text = food.name,
                                onClick = { onAction(BarcodeScanAction.ChooseFood(key, food)) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    TandemTextButton(
                        text = stringResource(if (item.food == null) R.string.scan_choose_food else R.string.scan_change_food),
                        onClick = { onAction(BarcodeScanAction.OpenPicker(key)) },
                        leadingIcon = ScanIcons.Edit,
                    )
                }
            }
            if (!state.readOnly && state.locations.isNotEmpty()) {
                ScanSectionTitle(stringResource(R.string.scan_where))
                LocationChips(
                    locations = state.locations,
                    selectedId = location?.id,
                    onSelect = { onAction(BarcodeScanAction.SetLocation(key, it)) },
                )
                ScanSectionTitle(stringResource(R.string.scan_best_before))
                ExpiryChips(
                    location = location,
                    selected = item.expires,
                    today = state.today,
                    onSelect = { onAction(BarcodeScanAction.SetExpiry(key, it)) },
                )
            }
            if (!state.readOnly) {
                TandemTextButton(
                    text = stringResource(R.string.scan_remove),
                    onClick = { onAction(BarcodeScanAction.Remove(key)) },
                    leadingIcon = TandemIcons.Delete,
                )
            }
        }
    }
}

/** An earlier package of the session: one TalkBack item, tap to show it in the card. */
@Composable
private fun OtherRow(row: BarcodeRow, state: BarcodeScanUiState, onSelect: () -> Unit) {
    val format = rememberScanFormat()
    val title = row.item.food?.name ?: row.productName ?: row.item.barcode.orEmpty()
    val place = state.locationOf(row)?.name
    val status = stringResource(row.status.label)
    val description = listOfNotNull(title, place, row.item.expires?.let { format.date(it) }, status).joinToString(", ")
    val selectLabel = stringResource(R.string.scan_show_item)
    Row(
        modifier = Modifier
            .testTag(barcodeRowTag(row.item.key))
            .fillMaxWidth()
            .heightIn(min = TandemTheme.sizes.minTouchTarget)
            .clickable(onClickLabel = selectLabel, onClick = onSelect)
            .clearAndSetSemantics { contentDescription = description }
            .padding(vertical = TandemTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TandemTheme.spacing.md),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (place != null) {
                Text(text = place, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        StatusChip(label = status, tone = row.status.tone)
    }
}

/** "Lägg i Kyl" (one package) or "Granska n varor" (several), and "Lägg på inköpslistan", pinned below the panel. */
@Composable
private fun BarcodeActions(state: BarcodeScanUiState, onAction: (BarcodeScanAction) -> Unit) {
    val spacing = TandemTheme.spacing
    val selected = state.selected
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = spacing.screenHorizontal, vertical = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            val single = state.rows.size == 1
            val place = state.locationOf(state.rows.first())?.name ?: stringResource(R.string.scan_pantry)
            TandemPrimaryButton(
                text = if (single) {
                    stringResource(R.string.scan_barcode_save_to, place)
                } else {
                    pluralStringResource(R.plurals.scan_barcode_review, state.rows.size, state.rows.size)
                },
                onClick = { onAction(if (single) BarcodeScanAction.SaveToPantry else BarcodeScanAction.Review) },
                enabled = !state.saving && (!single || state.rows.first().item.ready),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(BARCODE_PRIMARY_TAG),
            )
            TandemTextButton(
                text = stringResource(R.string.scan_to_shopping_list),
                onClick = { onAction(BarcodeScanAction.AddToShoppingList) },
                enabled = !state.saving && selected?.item?.ready == true,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .testTag(BARCODE_SHOPPING_TAG),
            )
        }
    }
}

private val WIDE = 840.dp
private val PANEL_WIDTH = 440.dp
private val MIN_CAMERA_HEIGHT = 180.dp
private const val CAMERA_SHARE = 0.4f
private const val VIEWFINDER_WIDTH = 0.75f
private val VIEWFINDER_HEIGHT = 140.dp
