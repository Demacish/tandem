package casa.moller.tandem.feature.scan.barcode

import android.content.Context
import android.net.Uri
import androidx.camera.core.TorchState
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import casa.moller.tandem.feature.scan.R
import casa.moller.tandem.feature.scan.camera.CameraAccess
import casa.moller.tandem.feature.scan.camera.CameraPreview
import casa.moller.tandem.feature.scan.camera.rememberCameraController
import casa.moller.tandem.feature.scan.camera.rememberCameraPermission
import casa.moller.tandem.feature.scan.camera.rememberPhotoPicker
import casa.moller.tandem.feature.scan.engine.BarcodeEngine
import casa.moller.tandem.feature.scan.engine.ScanImages
import casa.moller.tandem.feature.scan.navigation.BarcodeScanKey
import casa.moller.tandem.feature.scan.navigation.ScanReviewKey
import casa.moller.tandem.feature.scan.ui.scanErrorText
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch

/**
 * The barcode scanner (#27) for [key]: the ViewModel, the camera permission (asked in context),
 * CameraX with ML Kit's bundled barcode model, the torch, the system photo picker, haptic feedback
 * for every new code, the snackbars and the review.
 */
@Composable
fun BarcodeScanRoute(key: BarcodeScanKey, onNavigate: (NavKey) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel = hiltViewModel<BarcodeScanViewModel, BarcodeScanViewModel.Factory>(
        key = "barcode-scan-${key.locationId}",
        creationCallback = { factory -> factory.create(key) },
    )
    BarcodeScanRoute(viewModel = viewModel, onNavigate = onNavigate, onBack = onBack, modifier = modifier)
}

@Composable
internal fun BarcodeScanRoute(
    viewModel: BarcodeScanViewModel,
    onNavigate: (NavKey) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val engine = remember(context) { BarcodeEngine(context) }
    DisposableEffect(engine) { onDispose { engine.close() } }
    val permission = rememberCameraPermission()
    // Fas S review (fix-ui): the toggle shows the torch as CameraX reports it. CameraX turns it off
    // when the app goes to the background, and a flag of our own then claimed it was still on.
    var torchOn by remember { mutableStateOf(false) }
    var torchAvailable by remember { mutableStateOf(false) }
    var torchCamera by remember { mutableStateOf<LifecycleCameraController?>(null) }
    val currentNavigate by rememberUpdatedState(onNavigate)
    val appContext = context.applicationContext

    val picker = rememberPhotoPicker { uri ->
        scope.launch { viewModel.onAction(readCodes(appContext, engine, uri)) }
    }
    LaunchedEffect(viewModel, snackbarHostState, resources) {
        viewModel.effects.collect { effect ->
            val message = when (effect) {
                BarcodeScanEffect.Scanned -> {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    null
                }

                is BarcodeScanEffect.OpenReview -> {
                    currentNavigate(ScanReviewKey(effect.sessionId))
                    null
                }

                is BarcodeScanEffect.Saved -> if (effect.place != null) {
                    resources.getQuantityString(R.plurals.scan_saved_to, effect.count, effect.count, effect.place)
                } else {
                    resources.getQuantityString(R.plurals.scan_saved, effect.count, effect.count)
                }

                is BarcodeScanEffect.AddedToList -> resources.getQuantityString(R.plurals.scan_added_to_list, effect.count, effect.count)

                BarcodeScanEffect.NoCodeInImage -> resources.getString(R.string.scan_barcode_none_in_image)

                BarcodeScanEffect.ImageUnreadable -> resources.getString(R.string.scan_image_unreadable)

                is BarcodeScanEffect.Failed -> resources.scanErrorText(effect.error)
            }
            if (message != null) {
                launch {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    snackbarHostState.showSnackbar(message)
                }
            }
        }
    }

    val granted = permission.access == CameraAccess.GRANTED
    BarcodeScanScreen(
        state = state,
        camera = ScanCameraUi(access = permission.access, torchAvailable = torchAvailable, torchOn = torchOn),
        onAction = viewModel::onAction,
        onCamera = { action ->
            when (action) {
                ScanCameraAction.ALLOW -> permission.request()
                ScanCameraAction.PICK_IMAGE -> picker()
                ScanCameraAction.TOGGLE_TORCH -> torchCamera?.enableTorch(!torchOn)
            }
        },
        onBack = onBack,
        modifier = modifier,
        snackbarHostState = snackbarHostState,
        preview = { previewModifier ->
            if (granted) {
                val analyzer = remember(engine, viewModel) { engine.analyzer { viewModel.onAction(BarcodeScanAction.CodeScanned(it)) } }
                val controller = rememberCameraController(CameraController.IMAGE_ANALYSIS, analyzer)
                LaunchedEffect(controller) {
                    controller.initializationFuture.addListener(
                        { torchAvailable = controller.cameraInfo?.hasFlashUnit() == true },
                        context.mainExecutor,
                    )
                }
                val owner = LocalLifecycleOwner.current
                DisposableEffect(controller, owner) {
                    torchCamera = controller
                    val observer = Observer<Int> { torchOn = it == TorchState.ON }
                    controller.torchState.observe(owner, observer)
                    onDispose {
                        controller.torchState.removeObserver(observer)
                        torchCamera = null
                        torchOn = false
                    }
                }
                CameraPreview(controller = controller, modifier = previewModifier)
            }
        },
    )
}

/**
 * The codes in the picked photo (read on the device, the photo is not kept), decoded off the main
 * thread and downsampled (a cloud item of the picker or a 50 MP photo blocked the UI before).
 */
@Suppress("TooGenericExceptionCaught") // ML Kit and the decoder fail a broken or unsupported picked file with any Exception.
private suspend fun readCodes(context: Context, engine: BarcodeEngine, uri: Uri): BarcodeScanAction = try {
    BarcodeScanAction.ImageRead(engine.read(ScanImages.fromUri(context, uri, ScanImages.BARCODE_MAX_EDGE)))
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    BarcodeScanAction.ImageFailed
} catch (e: OutOfMemoryError) {
    BarcodeScanAction.ImageFailed
}
