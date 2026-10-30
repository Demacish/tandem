package casa.moller.tandem.feature.scan.receipt

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
import casa.moller.tandem.feature.scan.engine.ReceiptTextEngine
import casa.moller.tandem.feature.scan.engine.ScanImages
import casa.moller.tandem.feature.scan.navigation.ReceiptScanKey
import casa.moller.tandem.feature.scan.navigation.ScanReviewKey

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
    val resources = LocalResources.current
    val snackbarHostState = remember { SnackbarHostState() }
    // Owned by the ViewModel (Fas S review, fix-ui): a read survives a rotation, fold or resize.
    val engine = remember(viewModel) { viewModel.engine(context) }
    val permission = rememberCameraPermission()
    val currentNavigate by rememberUpdatedState(onNavigate)
    val currentBack by rememberUpdatedState(onBack)
    var controller by remember { mutableStateOf<LifecycleCameraController?>(null) }
    val appContext = context.applicationContext

    val picker = rememberPhotoPicker { uri ->
        viewModel.onAction(ReceiptScanAction.ReadPage { engine.read(ScanImages.fromUri(appContext, uri, ScanImages.RECEIPT_MAX_EDGE)) })
    }
    LaunchedEffect(viewModel) {
        // A capture of the camera this page had before a recreation never answers: not "reading" for ever.
        viewModel.onAction(ReceiptScanAction.Shown)
        viewModel.effects.collect { effect ->
            when (effect) {
                is ReceiptScanEffect.OpenReview -> currentNavigate(ScanReviewKey(effect.sessionId))
                ReceiptScanEffect.Finished -> currentBack()
            }
        }
    }
    // Fas S review (fix-ui): a failed photo is announced (TalkBack heard nothing before), like the barcode scanner.
    LaunchedEffect(state.problem, snackbarHostState, resources) {
        val problem = state.problem ?: return@LaunchedEffect
        snackbarHostState.currentSnackbarData?.dismiss()
        snackbarHostState.showSnackbar(resources.getString(problem.title()))
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
                takePhoto(camera, context, engine) { viewModel.onAction(it) }
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

/**
 * Takes one photo: its JPEG bytes are copied and the frame closed at once (nothing depends on a
 * scope that a recreation may cancel), then the ViewModel decodes and reads it off the main thread.
 */
private fun takePhoto(
    controller: LifecycleCameraController,
    context: Context,
    engine: ReceiptTextEngine,
    onResult: (ReceiptScanAction) -> Unit,
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
                val appContext = context.applicationContext
                onResult(
                    ReceiptScanAction.ReadPage {
                        engine.read(ScanImages.fromJpeg(appContext, bytes, rotation, ScanImages.RECEIPT_MAX_EDGE))
                    },
                )
            }

            override fun onError(exception: ImageCaptureException) {
                onResult(ReceiptScanAction.PageFailed)
            }
        },
    )
}
