package casa.moller.tandem.feature.scan.camera

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageAnalysis
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner

// Fas S (skanning lane): the camera parts of the scanners (CameraX through camera-view's
// LifecycleCameraController, plan §8.8, B21). The screens themselves never touch the camera: they
// get the preview as a slot, so the screenshot tests draw a placeholder instead.

/** Whether the scanners may use the camera (asked in context, never at start). */
enum class CameraAccess {
    /** Allowed: the preview runs. */
    GRANTED,

    /** Not asked yet: the screen explains why before Android asks. */
    NOT_ASKED,

    /** Refused once: the screen explains again and offers the photo picker. */
    DENIED,

    /** Refused for good (Android no longer asks): only Settings can allow it; the picker still works. */
    BLOCKED,

    /** The device has no camera: only the photo picker. */
    UNAVAILABLE,
}

/** [access] and how to ask for it. */
class CameraPermission(val access: CameraAccess, val request: () -> Unit)

/** The camera permission, re-checked when the screen resumes (the user may have changed it in Settings). */
@Composable
fun rememberCameraPermission(): CameraPermission {
    val context = LocalContext.current
    val hasCamera = remember(context) { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }
    var granted by remember(context) { mutableStateOf(context.hasCameraPermission()) }
    var asked by rememberSaveable { mutableStateOf(false) }
    var blocked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        granted = allowed
        asked = true
        blocked = !allowed && context.findActivity()?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) == false
    }
    LifecycleResumeEffect(context) {
        granted = context.hasCameraPermission()
        onPauseOrDispose { }
    }
    val access = when {
        !hasCamera -> CameraAccess.UNAVAILABLE
        granted -> CameraAccess.GRANTED
        blocked -> CameraAccess.BLOCKED
        asked -> CameraAccess.DENIED
        else -> CameraAccess.NOT_ASKED
    }
    return CameraPermission(access) { launcher.launch(Manifest.permission.CAMERA) }
}

/** Opens the app's page in Android's settings (to allow the camera after "Don't ask again"). */
fun Context.openAppSettings() {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
    try {
        startActivity(intent)
    } catch (ignored: ActivityNotFoundException) {
        // A device without the page: nothing to open; the photo picker still works.
    }
}

/** The system photo picker for one image (no storage permission); [onPicked] gets its uri. */
@Composable
fun rememberPhotoPicker(onPicked: (Uri) -> Unit): () -> Unit {
    val current by rememberUpdatedState(onPicked)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { current(it) } }
    return { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
}

/**
 * A CameraX controller bound to the screen's lifecycle, for [useCases] (`CameraController.IMAGE_ANALYSIS`
 * for barcodes, `IMAGE_CAPTURE` for receipt photos), with [analyzer] when set. Unbound when it leaves.
 */
@Composable
fun rememberCameraController(useCases: Int, analyzer: ImageAnalysis.Analyzer? = null): LifecycleCameraController {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val controller = remember(context) { LifecycleCameraController(context) }
    DisposableEffect(controller, owner, useCases, analyzer) {
        controller.setEnabledUseCases(useCases)
        if (analyzer != null && useCases and CameraController.IMAGE_ANALYSIS != 0) {
            controller.imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
            controller.setImageAnalysisAnalyzer(context.mainExecutor, analyzer)
        }
        controller.bindToLifecycle(owner)
        onDispose {
            controller.clearImageAnalysisAnalyzer()
            controller.unbind()
        }
    }
    return controller
}

/** The live camera picture of [controller] (decorative for TalkBack: the screen says what to do). */
@Composable
fun CameraPreview(controller: LifecycleCameraController, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { context ->
            PreviewView(context).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
                this.controller = controller
            }
        },
        update = { view -> view.controller = controller },
        modifier = modifier,
    )
}

private fun Context.hasCameraPermission(): Boolean =
    checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
