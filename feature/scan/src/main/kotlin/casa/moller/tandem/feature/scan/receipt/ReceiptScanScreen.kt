package casa.moller.tandem.feature.scan.receipt

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import casa.moller.tandem.core.designsystem.component.InfoCard
import casa.moller.tandem.core.designsystem.component.TandemButtonSize
import casa.moller.tandem.core.designsystem.component.TandemLoadingIndicator
import casa.moller.tandem.core.designsystem.component.TandemPrimaryButton
import casa.moller.tandem.core.designsystem.component.TandemSecondaryButton
import casa.moller.tandem.core.designsystem.component.TandemTextButton
import casa.moller.tandem.core.designsystem.component.TandemTone
import casa.moller.tandem.core.designsystem.component.TandemTopAppBar
import casa.moller.tandem.core.designsystem.icon.Delete
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.ui.readonly.ReadOnlyBanner
import casa.moller.tandem.feature.scan.R
import casa.moller.tandem.feature.scan.barcode.ScanCameraAction
import casa.moller.tandem.feature.scan.barcode.ScanCameraUi
import casa.moller.tandem.feature.scan.camera.CameraAccess
import casa.moller.tandem.feature.scan.ui.CameraAccessPanel
import casa.moller.tandem.feature.scan.ui.CameraPlaceholder
import casa.moller.tandem.feature.scan.ui.ScanIcons

/** Test tag of "Ta foto". */
const val RECEIPT_SHUTTER_TAG = "scan:receipt:shutter"

/** Test tag of "Läs kvittot". */
const val RECEIPT_DONE_TAG = "scan:receipt:done"

/**
 * The receipt scanner (#44, plan §8.8), stateless: the camera (or why it is needed and the photo
 * picker), "Ta foto" for each part of a long receipt, how many photos are read, "Läs kvittot", and
 * what went wrong (no text, no item lines). The photo is read on the phone and not kept (said on
 * the screen). Camera and panel side by side in landscape and on tablets.
 *
 * @param camera the permission (the torch is not used for receipts).
 * @param onCamera ALLOW, PICK_IMAGE; [onTakePhoto] takes a photo with the preview's camera.
 */
@Composable
fun ReceiptScanScreen(
    state: ReceiptScanUiState,
    camera: ScanCameraUi,
    onAction: (ReceiptScanAction) -> Unit,
    onCamera: (ScanCameraAction) -> Unit,
    onTakePhoto: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    preview: @Composable (Modifier) -> Unit = { CameraPlaceholder(it) },
) {
    val cameraPreview = remember(preview) { movableContentOf(preview) }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TandemTopAppBar(title = stringResource(R.string.scan_receipt_title), onNavigateBack = onBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            val width = maxWidth
            val height = maxHeight
            val sideBySide = width > height || width >= WIDE
            if (sideBySide) {
                Row(modifier = Modifier.fillMaxSize()) {
                    ReceiptCameraArea(
                        camera = camera,
                        onCamera = onCamera,
                        preview = cameraPreview,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                    ReceiptPanel(
                        state = state,
                        camera = camera,
                        onAction = onAction,
                        onCamera = onCamera,
                        onTakePhoto = onTakePhoto,
                        modifier = Modifier.width(minOf(PANEL_WIDTH, width / 2)).fillMaxHeight(),
                    )
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    ReceiptCameraArea(
                        camera = camera,
                        onCamera = onCamera,
                        preview = cameraPreview,
                        modifier = Modifier.fillMaxWidth().height(maxOf(MIN_CAMERA_HEIGHT, height * CAMERA_SHARE)),
                    )
                    ReceiptPanel(
                        state = state,
                        camera = camera,
                        onAction = onAction,
                        onCamera = onCamera,
                        onTakePhoto = onTakePhoto,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun ReceiptCameraArea(
    camera: ScanCameraUi,
    onCamera: (ScanCameraAction) -> Unit,
    preview: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (camera.access == CameraAccess.GRANTED) {
        Box(modifier = modifier) { preview(Modifier.fillMaxSize()) }
    } else {
        CameraAccessPanel(
            access = camera.access,
            rationale = R.string.scan_camera_receipt_rationale,
            onAllow = { onCamera(ScanCameraAction.ALLOW) },
            modifier = modifier,
        )
    }
}

@Composable
private fun ReceiptPanel(
    state: ReceiptScanUiState,
    camera: ScanCameraUi,
    onAction: (ReceiptScanAction) -> Unit,
    onCamera: (ScanCameraAction) -> Unit,
    onTakePhoto: () -> Unit,
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
                    InfoCard(title = stringResource(R.string.scan_demo_title), body = stringResource(R.string.scan_receipt_demo_body))
                }
                Text(
                    text = if (state.pages == 0) {
                        stringResource(R.string.scan_receipt_none_yet)
                    } else {
                        pluralStringResource(R.plurals.scan_receipt_pages, state.pages, state.pages)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics {
                        heading()
                        liveRegion = LiveRegionMode.Polite
                    },
                )
                Text(
                    text = stringResource(R.string.scan_receipt_tips),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.scan_receipt_privacy),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                state.problem?.let { problem ->
                    InfoCard(
                        title = stringResource(problem.title()),
                        body = stringResource(problem.body()),
                        tone = TandemTone.Warning,
                    )
                }
                if (state.busy) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(spacing.md),
                        modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                    ) {
                        TandemLoadingIndicator(modifier = Modifier.height(LOADING))
                        Text(
                            text = stringResource(if (state.matching) R.string.scan_receipt_matching else R.string.scan_receipt_reading),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
                if (state.pages > 0 && !state.busy) {
                    TandemTextButton(
                        text = stringResource(R.string.scan_receipt_remove_last),
                        onClick = { onAction(ReceiptScanAction.RemoveLastPage) },
                        leadingIcon = TandemIcons.Delete,
                    )
                }
            }
            ReceiptActions(state = state, camera = camera, onAction = onAction, onCamera = onCamera, onTakePhoto = onTakePhoto)
        }
    }
}

/** "Ta foto" / "Ta ett foto till", "Välj bild från galleriet" and "Läs kvittot", pinned below the panel. */
@Composable
private fun ReceiptActions(
    state: ReceiptScanUiState,
    camera: ScanCameraUi,
    onAction: (ReceiptScanAction) -> Unit,
    onCamera: (ScanCameraAction) -> Unit,
    onTakePhoto: () -> Unit,
) {
    val spacing = TandemTheme.spacing
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = spacing.screenHorizontal, vertical = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            if (state.pages > 0) {
                TandemPrimaryButton(
                    text = pluralStringResource(R.plurals.scan_receipt_done, state.pages, state.pages),
                    onClick = { onAction(ReceiptScanAction.Done) },
                    enabled = !state.busy,
                    size = TandemButtonSize.Kitchen,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(RECEIPT_DONE_TAG),
                )
            }
            if (camera.access == CameraAccess.GRANTED) {
                val shutter = if (state.pages == 0) R.string.scan_receipt_take_photo else R.string.scan_receipt_take_another
                if (state.pages == 0) {
                    TandemPrimaryButton(
                        text = stringResource(shutter),
                        onClick = onTakePhoto,
                        enabled = !state.busy,
                        leadingIcon = ScanIcons.Camera,
                        size = TandemButtonSize.Kitchen,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(RECEIPT_SHUTTER_TAG),
                    )
                } else {
                    TandemSecondaryButton(
                        text = stringResource(shutter),
                        onClick = onTakePhoto,
                        enabled = !state.busy,
                        leadingIcon = ScanIcons.AddPhoto,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(RECEIPT_SHUTTER_TAG),
                    )
                }
            }
            if (camera.access == CameraAccess.GRANTED) {
                TandemTextButton(
                    text = stringResource(R.string.scan_pick_image),
                    onClick = { onCamera(ScanCameraAction.PICK_IMAGE) },
                    enabled = !state.busy,
                    leadingIcon = ScanIcons.Gallery,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            } else {
                // Without the camera the photo picker is the way in (CameraAccessPanel no longer repeats it).
                TandemSecondaryButton(
                    text = stringResource(R.string.scan_pick_image),
                    onClick = { onCamera(ScanCameraAction.PICK_IMAGE) },
                    enabled = !state.busy,
                    leadingIcon = ScanIcons.Gallery,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private fun ReceiptProblem.title(): Int = when (this) {
    ReceiptProblem.NO_TEXT -> R.string.scan_receipt_no_text_title
    ReceiptProblem.NO_ITEMS -> R.string.scan_receipt_no_items_title
    ReceiptProblem.UNREADABLE -> R.string.scan_image_unreadable
}

private fun ReceiptProblem.body(): Int = when (this) {
    ReceiptProblem.NO_TEXT -> R.string.scan_receipt_no_text_body
    ReceiptProblem.NO_ITEMS -> R.string.scan_receipt_no_items_body
    ReceiptProblem.UNREADABLE -> R.string.scan_receipt_unreadable_body
}

private val WIDE = 840.dp
private val PANEL_WIDTH = 440.dp
private val MIN_CAMERA_HEIGHT = 180.dp
private val LOADING = 32.dp
private const val CAMERA_SHARE = 0.45f
