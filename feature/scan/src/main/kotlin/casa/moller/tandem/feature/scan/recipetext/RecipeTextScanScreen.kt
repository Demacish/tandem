package casa.moller.tandem.feature.scan.recipetext

import androidx.annotation.StringRes
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
import casa.moller.tandem.feature.scan.ui.rememberBringIntoView

/**
 * "Fota recept" (Fas 4, textigenkänning av recept): the camera (or the gallery), the pages read
 * so far with a preview of the text, and "Använd texten", which hands the text to "Importera
 * recept". Laid out like the receipt scanner: the camera beside the panel in landscape and on
 * wide windows, above it on a phone.
 */
@Composable
fun RecipeTextScanScreen(
    state: RecipeTextScanUiState,
    camera: ScanCameraUi,
    onAction: (RecipeTextScanAction) -> Unit,
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
        topBar = { TandemTopAppBar(title = stringResource(R.string.scan_recipe_text_title), onNavigateBack = onBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(padding)) {
            val width = maxWidth
            val height = maxHeight
            if (width > height || width >= WIDE) {
                Row(modifier = Modifier.fillMaxSize()) {
                    CameraArea(camera, onCamera, cameraPreview, Modifier.weight(1f).fillMaxHeight())
                    Panel(state, camera, onAction, onCamera, onTakePhoto, Modifier.width(minOf(PANEL_WIDTH, width / 2)).fillMaxHeight())
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    val cameraHeight = maxOf(MIN_CAMERA_HEIGHT, height * CAMERA_SHARE)
                    CameraArea(camera, onCamera, cameraPreview, Modifier.fillMaxWidth().height(cameraHeight))
                    Panel(state, camera, onAction, onCamera, onTakePhoto, Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun CameraArea(
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
            rationale = R.string.scan_camera_recipe_text_rationale,
            onAllow = { onCamera(ScanCameraAction.ALLOW) },
            modifier = modifier,
        )
    }
}

@Composable
private fun Panel(
    state: RecipeTextScanUiState,
    camera: ScanCameraUi,
    onAction: (RecipeTextScanAction) -> Unit,
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
                Text(
                    text = if (state.pages.isEmpty()) {
                        stringResource(R.string.scan_recipe_text_none_yet)
                    } else {
                        pluralStringResource(R.plurals.scan_recipe_text_pages, state.pages.size, state.pages.size)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics {
                        heading()
                        liveRegion = LiveRegionMode.Polite
                    },
                )
                Text(
                    text = stringResource(R.string.scan_recipe_text_tips),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.scan_recipe_text_privacy),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (state.preview.isNotEmpty()) {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = state.preview,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(spacing.md).testTag(RECIPE_TEXT_PREVIEW_TAG),
                        )
                    }
                }
                state.problem?.let { problem ->
                    InfoCard(
                        title = stringResource(problem.title()),
                        body = stringResource(problem.body()),
                        tone = TandemTone.Warning,
                        modifier = rememberBringIntoView(problem),
                    )
                }
                if (state.reading) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(spacing.md),
                        modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                    ) {
                        TandemLoadingIndicator(modifier = Modifier.height(LOADING))
                        Text(text = stringResource(R.string.scan_recipe_text_reading), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                if (state.pages.isNotEmpty() && !state.reading) {
                    TandemTextButton(
                        text = stringResource(R.string.scan_recipe_text_remove_last),
                        onClick = { onAction(RecipeTextScanAction.RemoveLastPage) },
                        leadingIcon = TandemIcons.Delete,
                    )
                }
            }
            Actions(state, camera, onAction, onCamera, onTakePhoto)
        }
    }
}

/** "Använd texten", "Ta foto"/"Ta ett foto till" and the gallery, pinned below the panel. */
@Composable
private fun Actions(
    state: RecipeTextScanUiState,
    camera: ScanCameraUi,
    onAction: (RecipeTextScanAction) -> Unit,
    onCamera: (ScanCameraAction) -> Unit,
    onTakePhoto: () -> Unit,
) {
    val spacing = TandemTheme.spacing
    val granted = camera.access == CameraAccess.GRANTED
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = spacing.screenHorizontal, vertical = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            if (state.pages.isNotEmpty()) {
                TandemPrimaryButton(
                    text = stringResource(R.string.scan_recipe_text_done),
                    onClick = { onAction(RecipeTextScanAction.Done) },
                    enabled = !state.reading && !state.readOnly,
                    size = TandemButtonSize.Kitchen,
                    modifier = Modifier.fillMaxWidth().testTag(RECIPE_TEXT_DONE_TAG),
                )
            }
            if (granted) {
                val shutter = if (state.pages.isEmpty()) R.string.scan_recipe_text_take_photo else R.string.scan_recipe_text_take_another
                if (state.pages.isEmpty()) {
                    TandemPrimaryButton(
                        text = stringResource(shutter),
                        onClick = onTakePhoto,
                        enabled = !state.reading && !state.readOnly,
                        leadingIcon = ScanIcons.Camera,
                        size = TandemButtonSize.Kitchen,
                        modifier = Modifier.fillMaxWidth().testTag(RECIPE_TEXT_SHUTTER_TAG),
                    )
                } else {
                    TandemSecondaryButton(
                        text = stringResource(shutter),
                        onClick = onTakePhoto,
                        enabled = !state.reading && !state.readOnly,
                        leadingIcon = ScanIcons.AddPhoto,
                        modifier = Modifier.fillMaxWidth().testTag(RECIPE_TEXT_SHUTTER_TAG),
                    )
                }
                TandemTextButton(
                    text = stringResource(R.string.scan_pick_image),
                    onClick = { onCamera(ScanCameraAction.PICK_IMAGE) },
                    enabled = !state.reading && !state.readOnly,
                    leadingIcon = ScanIcons.Gallery,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            } else {
                TandemSecondaryButton(
                    text = stringResource(R.string.scan_pick_image),
                    onClick = { onCamera(ScanCameraAction.PICK_IMAGE) },
                    enabled = !state.reading && !state.readOnly,
                    leadingIcon = ScanIcons.Gallery,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@StringRes
internal fun RecipeTextProblem.title(): Int = when (this) {
    RecipeTextProblem.NO_TEXT -> R.string.scan_recipe_text_no_text_title
    RecipeTextProblem.UNREADABLE -> R.string.scan_image_unreadable
}

@StringRes
internal fun RecipeTextProblem.body(): Int = when (this) {
    RecipeTextProblem.NO_TEXT -> R.string.scan_recipe_text_no_text_body
    RecipeTextProblem.UNREADABLE -> R.string.scan_recipe_text_unreadable_body
}

const val RECIPE_TEXT_DONE_TAG: String = "recipe-text-done"
const val RECIPE_TEXT_SHUTTER_TAG: String = "recipe-text-shutter"
const val RECIPE_TEXT_PREVIEW_TAG: String = "recipe-text-preview"

private val WIDE = 840.dp
private val PANEL_WIDTH = 440.dp
private val MIN_CAMERA_HEIGHT = 180.dp
private val LOADING = 32.dp
private const val CAMERA_SHARE = 0.45f
