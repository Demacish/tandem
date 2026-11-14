package casa.moller.tandem.feature.shopping.storereminder

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import casa.moller.tandem.core.model.shopping.GeoPoint
import java.util.Locale
import kotlinx.coroutines.launch

/** ACCESS_FINE_LOCATION with ACCESS_COARSE_LOCATION: Android 12+ ignores a request for the precise one alone. */
internal val LocationPermissions: Array<String> =
    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

/**
 * "Påminnelse nära butiken" (Fas 5, butik lane, plan §7 #34): the page the shell opens from Mer →
 * Inställningar and from Handla's store chooser. [onBack] closes it.
 */
@Composable
fun StoreReminderRoute(onBack: () -> Unit, modifier: Modifier = Modifier) {
    StoreReminderRoute(viewModel = hiltViewModel<StoreReminderViewModel>(), onBack = onBack, modifier = modifier)
}

/** [StoreReminderRoute] with a given [viewModel] (tests pass one built on fakes). */
@Composable
internal fun StoreReminderRoute(viewModel: StoreReminderViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val snackbarHostState = remember { SnackbarHostState() }
    val location = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        // Only "Precise" counts: an approximate position cannot place a 200 m circle.
        val precise = results[Manifest.permission.ACCESS_FINE_LOCATION] == true
        viewModel.onAction(StoreReminderAction.PermissionResult(PermissionStep.LOCATION, precise))
    }
    // Android 11+ opens the app's location page in the system settings for this one.
    val background = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.onAction(StoreReminderAction.PermissionResult(PermissionStep.BACKGROUND, granted))
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.onAction(StoreReminderAction.PermissionResult(PermissionStep.NOTIFICATIONS, granted))
    }
    // Back from the system settings (or anywhere): read the permissions and the location setting again.
    LifecycleResumeEffect(viewModel) {
        viewModel.onAction(StoreReminderAction.Resumed)
        onPauseOrDispose { }
    }
    LaunchedEffect(viewModel, snackbarHostState, resources) {
        viewModel.effect.collect { effect ->
            when (effect) {
                is StoreReminderEffect.RequestPermission -> when (effect.step) {
                    PermissionStep.LOCATION -> location.launch(LocationPermissions)
                    PermissionStep.BACKGROUND -> background.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    PermissionStep.NOTIFICATIONS -> notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                }

                is StoreReminderEffect.Message -> launch {
                    val text = effect.arg?.let { resources.getString(effect.text, it) } ?: resources.getString(effect.text)
                    snackbarHostState.showSnackbar(text)
                }
            }
        }
    }
    val canOpenMaps = remember(context) { context.opensGeo() }
    StoreReminderScreen(
        state = state.copy(canOpenMaps = canOpenMaps),
        onAction = { action ->
            when (action) {
                StoreReminderAction.ShowOnMap -> state.sheet?.let { sheet ->
                    sheet.spot?.point?.let { context.startSafely(Intent(Intent.ACTION_VIEW, geoUri(it, sheet.name).toUri())) }
                }

                StoreReminderAction.OpenAppSettings -> context.startSafely(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                )

                StoreReminderAction.OpenLocationSettings -> context.startSafely(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))

                else -> viewModel.onAction(action)
            }
        },
        onBack = onBack,
        modifier = modifier,
        snackbarHostState = snackbarHostState,
    )
}

/** `geo:<lat>,<lon>?q=<lat>,<lon>(<name>)`: map apps put a pin with the store's name there. */
internal fun geoUri(point: GeoPoint, name: String): String {
    val lat = String.format(Locale.ROOT, "%.6f", point.latitude)
    val lon = String.format(Locale.ROOT, "%.6f", point.longitude)
    return "geo:$lat,$lon?q=$lat,$lon(${Uri.encode(name)})"
}

/** True when an app on the phone shows `geo:` places (needs the `<queries>` entry in the manifest). */
private fun Context.opensGeo(): Boolean {
    val intent = Intent(Intent.ACTION_VIEW, "geo:0,0?q=0,0".toUri())
    val flags = PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
    return packageManager.queryIntentActivities(intent, flags).isNotEmpty()
}

private fun Context.startSafely(intent: Intent) {
    try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        // Nothing on the phone opens it: nothing to show.
    }
}
