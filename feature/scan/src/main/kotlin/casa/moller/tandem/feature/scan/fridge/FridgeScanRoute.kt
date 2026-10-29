package casa.moller.tandem.feature.scan.fridge

import android.content.Context
import android.net.Uri
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
    val scope = rememberCoroutineScope()
    val permission = rememberCameraPermission()
    val currentNavigate by rememberUpdatedState(onNavigate)
    val currentBack by rememberUpdatedState(onBack)
    var controller by remember { mutableStateOf<LifecycleCameraController?>(null) }

    val picker = rememberPhotoPicker { uri ->
        viewModel.onAction(FridgeScanAction.Preparing)
        scope.launch { viewModel.onAction(preparePicked(context, uri)) }
    }
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is FridgeScanEffect.OpenReview -> currentNavigate(ScanReviewKey(effect.sessionId))
                FridgeScanEffect.Finished -> currentBack()
            }
        }
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
                takePhoto(camera, context, scope) { viewModel.onAction(it) }
            }
        },
        onOpenSettings = { onNavigate(AiSettingsKey) },
        onBack = onBack,
        modifier = modifier,
        preview = { previewModifier ->
            if (permission.access == CameraAccess.GRANTED && state.canScan) {
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

/** Takes one photo; its bytes are copied, the frame closed, then scaled off the main thread. */
private fun takePhoto(
    controller: LifecycleCameraController,
    context: Context,
    scope: CoroutineScope,
    onResult: (FridgeScanAction) -> Unit,
) {
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
                scope.launch { onResult(prepare { FridgePhoto.fromCameraJpeg(bytes, rotation) }) }
            }

            override fun onError(exception: ImageCaptureException) {
                onResult(FridgeScanAction.PhotoFailed)
            }
        },
    )
}

private suspend fun preparePicked(context: Context, uri: Uri): FridgeScanAction = prepare { FridgePhoto.fromUri(context, uri) }

@Suppress("TooGenericExceptionCaught") // A broken, huge or unsupported image fails with any Exception (or OOM).
private suspend fun prepare(block: () -> ByteArray): FridgeScanAction = try {
    FridgeScanAction.PhotoReady(withContext(Dispatchers.Default) { block() })
} catch (e: CancellationException) {
    throw e
} catch (ignored: Exception) {
    FridgeScanAction.PhotoFailed
} catch (ignored: OutOfMemoryError) {
    FridgeScanAction.PhotoFailed
}
