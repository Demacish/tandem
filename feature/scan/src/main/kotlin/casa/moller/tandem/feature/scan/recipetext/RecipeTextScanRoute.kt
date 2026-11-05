package casa.moller.tandem.feature.scan.recipetext

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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import casa.moller.tandem.feature.scan.barcode.ScanCameraAction
import casa.moller.tandem.feature.scan.barcode.ScanCameraUi
import casa.moller.tandem.feature.scan.camera.CameraAccess
import casa.moller.tandem.feature.scan.camera.CameraPreview
import casa.moller.tandem.feature.scan.camera.rememberCameraController
import casa.moller.tandem.feature.scan.camera.rememberCameraPermission
import casa.moller.tandem.feature.scan.camera.rememberPhotoPicker
import casa.moller.tandem.feature.scan.engine.ReceiptTextEngine
import casa.moller.tandem.feature.scan.engine.ScanImages

/**
 * Fas 4 (textigenkänning av recept): "Fota recept". The photo is read on the device with the
 * receipt's text recognizer (ML Kit, bundled; nothing leaves the phone and the photo is not
 * kept) and the text handed to [onText], which the shell turns into "Importera recept" with
 * the text prefilled (feature:recipes reads it with its on-device text parser).
 */
@Composable
fun RecipeTextScanRoute(onText: (String) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel: RecipeTextScanViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val snackbarHostState = remember { SnackbarHostState() }
    val engine = remember(viewModel) { viewModel.engine(context) }
    val permission = rememberCameraPermission()
    val currentOnText by rememberUpdatedState(onText)
    var controller by remember { mutableStateOf<LifecycleCameraController?>(null) }
    val appContext = context.applicationContext

    val picker = rememberPhotoPicker { uri ->
        viewModel.onAction(RecipeTextScanAction.ReadPage { engine.read(ScanImages.fromUri(appContext, uri, ScanImages.RECEIPT_MAX_EDGE)) })
    }
    LaunchedEffect(viewModel) {
        viewModel.onAction(RecipeTextScanAction.Shown)
        viewModel.effects.collect { effect ->
            when (effect) {
                is RecipeTextScanEffect.TextRead -> currentOnText(effect.text)
            }
        }
    }
    // A page that was read: the same tick as a scanned barcode. Not for a removed page.
    val haptics = LocalHapticFeedback.current
    var pagesRead by rememberSaveable { mutableIntStateOf(state.pages.size) }
    LaunchedEffect(state.pages.size) {
        if (state.pages.size > pagesRead) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        pagesRead = state.pages.size
    }
    LaunchedEffect(state.problem, snackbarHostState, resources) {
        val problem = state.problem ?: return@LaunchedEffect
        snackbarHostState.currentSnackbarData?.dismiss()
        snackbarHostState.showSnackbar(resources.getString(problem.title()))
    }

    RecipeTextScanScreen(
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
                viewModel.onAction(RecipeTextScanAction.Reading)
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

/** One photo: the JPEG bytes are copied and the frame closed at once; the ViewModel decodes and reads off the main thread. */
private fun takePhoto(
    controller: LifecycleCameraController,
    context: Context,
    engine: ReceiptTextEngine,
    onResult: (RecipeTextScanAction) -> Unit,
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
                    RecipeTextScanAction.ReadPage {
                        engine.read(ScanImages.fromJpeg(appContext, bytes, rotation, ScanImages.RECEIPT_MAX_EDGE))
                    },
                )
            }

            override fun onError(exception: ImageCaptureException) {
                onResult(RecipeTextScanAction.PageFailed)
            }
        },
    )
}
