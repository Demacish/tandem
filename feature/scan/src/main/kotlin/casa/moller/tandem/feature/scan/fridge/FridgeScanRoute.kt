package casa.moller.tandem.feature.scan.fridge

import android.content.Context
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import casa.moller.tandem.feature.scan.barcode.ScanCameraAction
import casa.moller.tandem.feature.scan.barcode.ScanCameraUi
import casa.moller.tandem.feature.scan.camera.CameraAccess
import casa.moller.tandem.feature.scan.camera.CameraPreview
import casa.moller.tandem.feature.scan.camera.rememberCameraController
import casa.moller.tandem.feature.scan.camera.rememberCameraPermission
import casa.moller.tandem.feature.scan.camera.rememberPhotoPicker
import casa.moller.tandem.feature.scan.navigation.AiSettingsKey
import casa.moller.tandem.feature.scan.navigation.FridgeScanKey
import casa.moller.tandem.feature.scan.navigation.ScanReviewKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The fridge photo (#45, EXPERIMENTAL) for [key]: the ViewModel, the camera permission (asked in
 * context), a CameraX photo or the system photo picker, the scaling to 1600 px without metadata
 * (in memory, off the main thread), the AI call and the review. The photo is never written to a
 * file and is dropped after the call.
 */
@Composable
fun FridgeScanRoute(key: FridgeScanKey, onNavigate: (NavKey) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel = hiltViewModel<FridgeScanViewModel, FridgeScanViewModel.Factory>(
        key = "fridge-scan-${key.locationId}",
        creationCallback = { factory -> factory.create(key) },
    )
    FridgeScanRoute(viewModel = viewModel, onNavigate = onNavigate, onBack = onBack, modifier = modifier)
}

@Composable
internal fun FridgeScanRoute(
    viewModel: FridgeScanViewModel,
    onNavigate: (NavKey) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val snackbarHostState = remember { SnackbarHostState() }
    val permission = rememberCameraPermission()
    val currentNavigate by rememberUpdatedState(onNavigate)
    val currentBack by rememberUpdatedState(onBack)
    var controller by remember { mutableStateOf<LifecycleCameraController?>(null) }
    val appContext = context.applicationContext

    val picker = rememberPhotoPicker { uri ->
        viewModel.onAction(FridgeScanAction.Prepare { withContext(Dispatchers.IO) { FridgePhoto.fromUri(appContext, uri) } })
    }
    LaunchedEffect(viewModel) {
        // A capture of the camera this page had before a recreation never answers: not PREPARING for ever.
        viewModel.onAction(FridgeScanAction.Shown)
        viewModel.effects.collect { effect ->
            when (effect) {
                is FridgeScanEffect.OpenReview -> currentNavigate(ScanReviewKey(effect.sessionId))
                FridgeScanEffect.Finished -> currentBack()
            }
        }
    }
    // Fas S review (fix-ui): after a 10–30 s call TalkBack heard nothing when it failed; now it does.
    LaunchedEffect(state.problem, snackbarHostState, resources) {
        val problem = state.problem ?: return@LaunchedEffect
        snackbarHostState.currentSnackbarData?.dismiss()
        snackbarHostState.showSnackbar(resources.getString(problem.title()))
    }

    FridgeScanScreen(
        state = state,
        camera = ScanCameraUi(access = permission.access),
        onAction = viewModel::onAction,
        onCamera = { action ->
            when (action) {
                ScanCameraAction.ALLOW -> permission.request()
                ScanCameraAction.PICK_IMAGE -> picker()
                ScanCameraAction.TOGGLE_TORCH -> Unit
            }
        },
        onTakePhoto = {
            controller?.let { camera ->
                viewModel.onAction(FridgeScanAction.Preparing)
                takePhoto(camera, context) { viewModel.onAction(it) }
            }
        },
        onOpenSettings = { onNavigate(AiSettingsKey) },
        onBack = onBack,
        modifier = modifier,
        snackbarHostState = snackbarHostState,
        preview = { previewModifier ->
            // Not gated on "busy": unbinding the camera while it takes the photo aborts the capture.
            if (permission.access == CameraAccess.GRANTED && state.cameraWanted) {
                val bound = rememberCameraController(CameraController.IMAGE_CAPTURE)
                DisposableEffect(bound) {
                    controller = bound
                    onDispose { controller = null }
                }
                CameraPreview(controller = bound, modifier = previewModifier)
            }
        },
    )
}

/** Takes one photo; its bytes are copied and the frame closed at once, then the ViewModel scales it off the main thread. */
private fun takePhoto(controller: LifecycleCameraController, context: Context, onResult: (FridgeScanAction) -> Unit) {
    controller.takePicture(
        context.mainExecutor,
        object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val rotation = image.imageInfo.rotationDegrees
                val bytes = try {
                    val buffer = image.planes[0].buffer
                    ByteArray(buffer.remaining()).also { buffer.get(it) }
                } finally {
                    image.close()
                }
                onResult(FridgeScanAction.Prepare { withContext(Dispatchers.Default) { FridgePhoto.fromCameraJpeg(bytes, rotation) } })
            }

            override fun onError(exception: ImageCaptureException) {
                onResult(FridgeScanAction.PhotoFailed)
            }
        },
    )
}
