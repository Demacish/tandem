package casa.moller.tandem.feature.scan.fridge

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import casa.moller.tandem.core.designsystem.component.CalloutCard
import casa.moller.tandem.core.designsystem.component.InfoCard
import casa.moller.tandem.core.designsystem.component.StatusChip
import casa.moller.tandem.core.designsystem.component.TandemButtonSize
import casa.moller.tandem.core.designsystem.component.TandemLoadingIndicator
import casa.moller.tandem.core.designsystem.component.TandemPrimaryButton
import casa.moller.tandem.core.designsystem.component.TandemSecondaryButton
import casa.moller.tandem.core.designsystem.component.TandemTextButton
import casa.moller.tandem.core.designsystem.component.TandemTone
import casa.moller.tandem.core.designsystem.component.TandemTopAppBar
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.ui.readonly.ReadOnlyBanner
import casa.moller.tandem.feature.scan.R
import casa.moller.tandem.feature.scan.aisettings.label
import casa.moller.tandem.feature.scan.barcode.ScanCameraAction
import casa.moller.tandem.feature.scan.barcode.ScanCameraUi
import casa.moller.tandem.feature.scan.camera.CameraAccess
import casa.moller.tandem.feature.scan.ui.CameraAccessPanel
import casa.moller.tandem.feature.scan.ui.CameraPlaceholder
import casa.moller.tandem.feature.scan.ui.ScanIcons
import casa.moller.tandem.feature.scan.ui.rememberBringIntoView

/** Test tag of "Ta foto". */
const val FRIDGE_SHUTTER_TAG = "scan:fridge:shutter"

/** Test tag of "Jag förstår" in the experimental warning. */
const val FRIDGE_WARNING_ACCEPT_TAG = "scan:fridge:warning:accept"

/** Test tag of "Öppna AI-inställningarna". */
const val FRIDGE_SETTINGS_TAG = "scan:fridge:settings"

/**
 * The fridge photo (#45, plan §8.8, B22), stateless and EXPERIMENTAL (said in the title, a chip and
 * the warning): the camera (or why it is needed) beside a panel with what happens to the photo (it
 * goes to the chosen service, costs land on the user's own account, nothing is kept), the progress,
 * what went wrong, and "Ta foto" / "Välj en bild" pinned below. Without a key the page points to
 * Settings → AI; in demo mode it explains why it is off. The first use shows the warning that the
 * AI can miss items or see things that are not there; it must be acknowledged.
 */
@Composable
fun FridgeScanScreen(
    state: FridgeScanUiState,
    camera: ScanCameraUi,
    onAction: (FridgeScanAction) -> Unit,
    onCamera: (ScanCameraAction) -> Unit,
    onTakePhoto: () -> Unit,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    preview: @Composable (Modifier) -> Unit = { CameraPlaceholder(it) },
) {
    val cameraPreview = remember(preview) { movableContentOf(preview) }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TandemTopAppBar(title = stringResource(R.string.scan_fridge_title), onNavigateBack = onBack) },
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
            val withCamera = state.available && !state.demo
            val panel = @Composable { panelModifier: Modifier ->
                FridgePanel(
                    state = state,
                    camera = camera,
                    onAction = onAction,
                    onCamera = onCamera,
                    onTakePhoto = onTakePhoto,
                    onOpenSettings = onOpenSettings,
                    modifier = panelModifier,
                )
            }
            when {
                !withCamera -> panel(Modifier.fillMaxSize())

                width > height || width >= WIDE -> Row(modifier = Modifier.fillMaxSize()) {
                    FridgeCameraArea(camera, onCamera, cameraPreview, Modifier.weight(1f).fillMaxHeight())
                    panel(Modifier.width(minOf(PANEL_WIDTH, width / 2)).fillMaxHeight())
                }

                else -> Column(modifier = Modifier.fillMaxSize()) {
                    val cameraHeight = maxOf(MIN_CAMERA_HEIGHT, height * CAMERA_SHARE)
                    FridgeCameraArea(camera, onCamera, cameraPreview, Modifier.fillMaxWidth().height(cameraHeight))
                    panel(Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
    }
    if (state.showWarning) ExperimentalWarningDialog(onAccept = { onAction(FridgeScanAction.AcknowledgeWarning) }, onDecline = onBack)
}

@Composable
private fun FridgeCameraArea(
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
            rationale = R.string.fridge_camera_rationale,
            onAllow = { onCamera(ScanCameraAction.ALLOW) },
            modifier = modifier,
        )
    }
}

@Composable
private fun FridgePanel(
    state: FridgeScanUiState,
    camera: ScanCameraUi,
    onAction: (FridgeScanAction) -> Unit,
    onCamera: (ScanCameraAction) -> Unit,
    onTakePhoto: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = TandemTheme.spacing
    // With an own base URL the photo and the key go to that host, so the texts name it (fix-ui).
    val service = state.customHost ?: state.provider?.let { stringResource(it.label()) } ?: stringResource(R.string.fridge_service_fallback)
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = spacing.screenHorizontal, vertical = spacing.lg),
                verticalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                StatusChip(label = stringResource(R.string.fridge_experimental), icon = TandemIcons.Science, tone = TandemTone.Warning)
                when {
                    state.loading -> Unit

                    state.demo -> InfoCard(
                        title = stringResource(R.string.scan_demo_title),
                        body = stringResource(R.string.fridge_demo_body),
                    )

                    !state.available -> CalloutCard(
                        title = stringResource(R.string.fridge_no_key_title),
                        body = stringResource(R.string.fridge_no_key_body),
                        icon = TandemIcons.Key,
                    ) {
                        TandemPrimaryButton(
                            text = stringResource(R.string.fridge_open_settings),
                            onClick = onOpenSettings,
                            leadingIcon = TandemIcons.Settings,
                            modifier = Modifier.testTag(FRIDGE_SETTINGS_TAG),
                        )
                    }

                    else -> FridgeInstructions(state = state, service = service, onAction = onAction)
                }
            }
            if (state.available && !state.demo) {
                FridgeActions(state = state, camera = camera, onCamera = onCamera, onTakePhoto = onTakePhoto)
            }
        }
    }
}

@Composable
private fun FridgeInstructions(state: FridgeScanUiState, service: String, onAction: (FridgeScanAction) -> Unit) {
    val spacing = TandemTheme.spacing
    if (state.readOnly) ReadOnlyBanner(compact = true)
    Text(
        text = stringResource(R.string.fridge_heading),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() },
    )
    Text(
        text = stringResource(R.string.fridge_tips),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        text = stringResource(R.string.fridge_privacy, service),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface,
    )
    state.problem?.let { problem ->
        CalloutCard(
            title = stringResource(problem.title()),
            body = stringResource(problem.body(), service),
            icon = TandemIcons.Warning,
            tone = TandemTone.Warning,
            onDismiss = { onAction(FridgeScanAction.DismissProblem) },
            // Below the tips under the camera on a phone: scrolled into view when it appears (fix-ui).
            modifier = rememberBringIntoView(problem),
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
                text = when (state.phase) {
                    FridgePhase.ASKING -> stringResource(R.string.fridge_asking, service)
                    FridgePhase.MATCHING -> stringResource(R.string.fridge_matching)
                    else -> stringResource(R.string.fridge_preparing)
                },
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

/** "Ta foto" and "Välj en bild från galleriet", pinned below the panel (reachable at any font size). */
@Composable
private fun FridgeActions(state: FridgeScanUiState, camera: ScanCameraUi, onCamera: (ScanCameraAction) -> Unit, onTakePhoto: () -> Unit) {
    val spacing = TandemTheme.spacing
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = spacing.screenHorizontal, vertical = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            if (camera.access == CameraAccess.GRANTED) {
                TandemPrimaryButton(
                    text = stringResource(R.string.fridge_take_photo),
                    onClick = onTakePhoto,
                    enabled = state.canScan,
                    leadingIcon = ScanIcons.Camera,
                    size = TandemButtonSize.Kitchen,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(FRIDGE_SHUTTER_TAG),
                )
                TandemTextButton(
                    text = stringResource(R.string.scan_pick_image),
                    onClick = { onCamera(ScanCameraAction.PICK_IMAGE) },
                    enabled = state.canScan,
                    leadingIcon = ScanIcons.Gallery,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            } else {
                // Without the camera the photo picker is the way in.
                TandemSecondaryButton(
                    text = stringResource(R.string.scan_pick_image),
                    onClick = { onCamera(ScanCameraAction.PICK_IMAGE) },
                    enabled = state.canScan,
                    leadingIcon = ScanIcons.Gallery,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * The experimental warning before the first fridge photo (user requirement, B22): the AI can miss
 * items or believe something is there that is not, so the list must be checked before saving.
 * "Jag förstår" stores the acknowledgement; "Inte nu" leaves the page.
 */
@Composable
internal fun ExperimentalWarningDialog(onAccept: () -> Unit, onDecline: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDecline,
        // The surface Veckan's and Hemma's dialogs use (ATF contrast of the buttons).
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        icon = { Icon(TandemIcons.Science, contentDescription = null) },
        title = { Text(stringResource(R.string.fridge_warning_title)) },
        text = {
            Text(
                text = stringResource(R.string.fridge_warning_body),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            TextButton(onClick = onAccept, modifier = Modifier.testTag(FRIDGE_WARNING_ACCEPT_TAG)) {
                Text(stringResource(R.string.fridge_warning_accept))
            }
        },
        dismissButton = { TextButton(onClick = onDecline) { Text(stringResource(R.string.fridge_warning_decline)) } },
    )
}

internal fun FridgeProblem.title(): Int = when (this) {
    FridgeProblem.INVALID_KEY -> R.string.fridge_problem_key_title
    FridgeProblem.MODEL_UNAVAILABLE -> R.string.fridge_problem_model_title
    FridgeProblem.RATE_LIMITED -> R.string.fridge_problem_rate_title
    FridgeProblem.REFUSAL -> R.string.fridge_problem_refusal_title
    FridgeProblem.NETWORK -> R.string.fridge_problem_network_title
    FridgeProblem.UNPARSEABLE -> R.string.fridge_problem_unparseable_title
    FridgeProblem.NOTHING_FOUND -> R.string.fridge_problem_nothing_title
    FridgeProblem.UNREADABLE -> R.string.scan_image_unreadable
}

/** The body; every one takes the service's name as %1$s (some do not use it). */
private fun FridgeProblem.body(): Int = when (this) {
    FridgeProblem.INVALID_KEY -> R.string.fridge_problem_key_body
    FridgeProblem.MODEL_UNAVAILABLE -> R.string.fridge_problem_model_body
    FridgeProblem.RATE_LIMITED -> R.string.fridge_problem_rate_body
    FridgeProblem.REFUSAL -> R.string.fridge_problem_refusal_body
    FridgeProblem.NETWORK -> R.string.fridge_problem_network_body
    FridgeProblem.UNPARSEABLE -> R.string.fridge_problem_unparseable_body
    FridgeProblem.NOTHING_FOUND -> R.string.fridge_problem_nothing_body
    FridgeProblem.UNREADABLE -> R.string.fridge_problem_unreadable_body
}

private val WIDE = 840.dp
private val PANEL_WIDTH = 440.dp
private val MIN_CAMERA_HEIGHT = 180.dp
private val LOADING = 32.dp
private const val CAMERA_SHARE = 0.45f
