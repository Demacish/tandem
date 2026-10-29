package casa.moller.tandem.feature.scan.receipt

import android.content.Context
import android.net.Uri
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
import casa.moller.tandem.feature.scan.engine.ReceiptTextEngine
import casa.moller.tandem.feature.scan.navigation.ReceiptScanKey
import casa.moller.tandem.feature.scan.navigation.ScanReviewKey
import com.google.mlkit.vision.common.InputImage
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The receipt scanner (#44) for [key]: the ViewModel, the camera permission (asked in context),
 * CameraX photos read by ML Kit's bundled Latin text model on the device, the system photo picker,
 * and the review. A photo is read in memory and closed at once: it is never written anywhere.
 */
@Composable
fun ReceiptScanRoute(key: ReceiptScanKey, onNavigate: (NavKey) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel = hiltViewModel<ReceiptScanViewModel, ReceiptScanViewModel.Factory>(
        key = "receipt-scan-${key.locationId}",
        creationCallback = { factory -> factory.create(key) },
    )
    ReceiptScanRoute(viewModel = viewModel, onNavigate = onNavigate, onBack = onBack, modifier = modifier)
}

@Composable
internal fun ReceiptScanRoute(
    viewModel: ReceiptScanViewModel,
    onNavigate: (NavKey) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val engine = remember { ReceiptTextEngine() }
    DisposableEffect(engine) { onDispose { engine.close() } }
    val permission = rememberCameraPermission()
    val currentNavigate by rememberUpdatedState(onNavigate)
    val currentBack by rememberUpdatedState(onBack)
    var controller by remember { mutableStateOf<LifecycleCameraController?>(null) }

    val picker = rememberPhotoPicker { uri ->
        viewModel.onAction(ReceiptScanAction.Reading)
        scope.launch { viewModel.onAction(readPicked(context, engine, uri)) }
    }
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is ReceiptScanEffect.OpenReview -> currentNavigate(ScanReviewKey(effect.sessionId))
                ReceiptScanEffect.Finished -> currentBack()
            }
        }
    }

    ReceiptScanScreen(
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
                viewModel.onAction(ReceiptScanAction.Reading)
                takePhoto(camera, context, scope, engine) { viewModel.onAction(it) }
            }
        },
        onBack = onBack,
        modifier = modifier,
        snackbarHostState = snackbarHostState,
        preview = { previewModifier ->
            if (permission.access == CameraAccess.GRANTED) {
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

/** Takes one photo and reads its text in memory; the frame is closed right after. */
private fun takePhoto(
    controller: LifecycleCameraController,
    context: Context,
    scope: CoroutineScope,
    engine: ReceiptTextEngine,
    onResult: (ReceiptScanAction) -> Unit,
) {
    controller.takePicture(
        context.mainExecutor,
        object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                scope.launch { onResult(readCaptured(engine, image)) }
            }

            override fun onError(exception: ImageCaptureException) {
                onResult(ReceiptScanAction.PageFailed)
            }
        },
    )
}

@Suppress("TooGenericExceptionCaught") // ML Kit fails an unreadable frame with any Exception.
private suspend fun readCaptured(engine: ReceiptTextEngine, image: ImageProxy): ReceiptScanAction = try {
    ReceiptScanAction.PageRead(engine.read(image))
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    ReceiptScanAction.PageFailed
}

@Suppress("TooGenericExceptionCaught") // ML Kit fails a broken or unsupported picked file with any Exception.
private suspend fun readPicked(context: Context, engine: ReceiptTextEngine, uri: Uri): ReceiptScanAction = try {
    ReceiptScanAction.PageRead(engine.read(InputImage.fromFilePath(context, uri)))
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    ReceiptScanAction.PageFailed
}
