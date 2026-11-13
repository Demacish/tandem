package casa.moller.tandem.feature.mealplan.calendar

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PersistableBundle
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import casa.moller.tandem.feature.mealplan.R
import kotlinx.coroutines.launch

/** READ_CALENDAR and WRITE_CALENDAR, asked together when the phone's calendar is turned on. */
internal val CalendarPermissions: Array<String> = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

/**
 * "Matsedeln i kalendern" (Fas 5, kalender lane, plan §7 #36): the page the shell opens from
 * Veckan's menu and from Mer → Inställningar. [onBack] closes it.
 */
@Composable
fun MealCalendarRoute(onBack: () -> Unit, modifier: Modifier = Modifier) {
    MealCalendarRoute(viewModel = hiltViewModel<MealCalendarViewModel>(), onBack = onBack, modifier = modifier)
}

/** [MealCalendarRoute] with a given [viewModel] (tests pass one built on fakes). */
@Composable
internal fun MealCalendarRoute(viewModel: MealCalendarViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        viewModel.onAction(MealCalendarAction.PermissionResult(results.isNotEmpty() && results.values.all { it }))
    }
    LaunchedEffect(viewModel, snackbarHostState, resources) {
        viewModel.effect.collect { effect ->
            when (effect) {
                MealCalendarEffect.RequestPermission -> permissions.launch(CalendarPermissions)

                is MealCalendarEffect.Message -> launch {
                    val arg = effect.arg?.let(resources::getString)
                    val text = if (arg != null) resources.getString(effect.text, arg) else resources.getString(effect.text)
                    snackbarHostState.showSnackbar(text)
                }
            }
        }
    }
    val url = state.link.url
    val webcal = state.link.webcalUrl
    val canOpen = remember(webcal) { webcal != null && context.opensWebcal(webcal) }
    MealCalendarScreen(
        state = state.copy(canOpenInCalendarApp = canOpen),
        onAction = { action ->
            when (action) {
                // Android shows its own confirmation of a copy; the address is marked sensitive (hidden in the preview).
                MealCalendarAction.CopyLink -> if (url != null) {
                    val clip = ClipData.newPlainText(resources.getString(R.string.calendar_link_address), url).apply {
                        description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
                    }
                    scope.launch { clipboard.setClipEntry(ClipEntry(clip)) }
                }

                MealCalendarAction.ShareLink -> if (url != null) {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
                    context.startSafely(Intent.createChooser(send, resources.getString(R.string.calendar_link_share_chooser)))
                }

                MealCalendarAction.OpenInCalendarApp -> if (webcal != null) context.startSafely(Intent(Intent.ACTION_VIEW, webcal.toUri()))

                MealCalendarAction.OpenAppSettings -> context.startSafely(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                )

                else -> viewModel.onAction(action)
            }
        },
        onBack = onBack,
        modifier = modifier,
        snackbarHostState = snackbarHostState,
    )
}

/** True when an app on the phone opens [webcal] (needs the `<queries>` entry in the manifest). */
private fun Context.opensWebcal(webcal: String): Boolean {
    val intent = Intent(Intent.ACTION_VIEW, webcal.toUri())
    val flags = PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
    return packageManager.queryIntentActivities(intent, flags).isNotEmpty()
}

private fun Context.startSafely(intent: Intent) {
    try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        // The app went away between the check and the tap: nothing to open.
    }
}
