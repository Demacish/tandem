package casa.moller.tandem.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import casa.moller.tandem.R
import casa.moller.tandem.core.model.settings.AppLockStatus
import casa.moller.tandem.core.ui.security.DeviceAuthResult
import casa.moller.tandem.core.ui.security.DeviceLockAvailability
import casa.moller.tandem.core.ui.security.rememberDeviceAuthenticator
import casa.moller.tandem.core.ui.security.screenLockSettingsIntent
import casa.moller.tandem.lock.AppLockAction
import casa.moller.tandem.lock.AppLockViewModel
import casa.moller.tandem.navigation.AppUiState

/**
 * Fas 5 (las lane, plan §5.5): Mer → Lås appen in front of the whole app (docs/BYGGE.md "Lås appen").
 *
 * [content] stays composed while the app is locked, so nothing of where the user was is lost (the
 * tabs' back stacks, an open sheet, cook mode's step): it is only not drawn, not read by TalkBack
 * and not touchable. In front of it:
 * - a plain cover in the app's own window, so the first frame after coming back never shows the
 *   content (and Recents has no picture of it: `setRecentsScreenshotEnabled(false)` in MainActivity);
 * - [lockScreen] in a window of its own on top of everything the content opened (a dialog or a
 *   bottom sheet is a window above the app's own), so an open sheet is covered too.
 *
 * [content] is told whether it is unlocked: what opens a place in the app (a notification tap, a
 * deep link, a share, a shortcut) waits for the unlock ([forLock]) and goes on to its destination
 * after it. While [status] is [AppLockStatus.UNKNOWN] (the setting is being read at a cold start)
 * the splash screen is still up and nothing is drawn.
 */
@Composable
internal fun AppLockGate(
    status: AppLockStatus,
    lockScreen: @Composable () -> Unit,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (unlocked: Boolean) -> Unit,
) {
    val unlocked = status.allowsContent
    Box(modifier = modifier.fillMaxSize()) {
        Box(modifier = if (unlocked) Modifier.fillMaxSize() else Modifier.fillMaxSize().hiddenContent()) {
            content(unlocked)
        }
        if (!unlocked) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .swallowTouches(),
            )
            if (status == AppLockStatus.LOCKED) {
                Dialog(
                    onDismissRequest = onLeave,
                    properties = DialogProperties(
                        dismissOnClickOutside = false,
                        usePlatformDefaultWidth = false,
                        decorFitsSystemWindows = false,
                    ),
                ) {
                    LockWindowBars()
                    lockScreen()
                }
            }
        }
    }
}

/**
 * The requests that open a place in the app wait while it is locked: [AppUiState] keeps them
 * (saved across process death) and the shell opens them once the app is unlocked.
 */
internal fun AppUiState.forLock(unlocked: Boolean): AppUiState =
    if (unlocked) this else copy(timerRequest = null, importRequest = null, openRequest = null)

/**
 * The lock screen with the system's prompt: it opens by itself when the lock screen comes up and
 * every time the app comes back to the front locked; "Lås upp" opens it again.
 */
@Composable
internal fun AppLockRoute(viewModel: AppLockViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val authenticator = rememberDeviceAuthenticator()
    val context = LocalContext.current
    val title = stringResource(R.string.app_lock_prompt_title)
    var cancelPrompt by remember { mutableStateOf<(() -> Unit)?>(null) }
    val unlock: () -> Unit = {
        when {
            viewModel.uiState.value.prompting -> Unit

            authenticator.availability() == DeviceLockAvailability.NO_SCREEN_LOCK -> viewModel.onAuthResult(DeviceAuthResult.NoScreenLock)

            else -> {
                viewModel.onPromptShown()
                cancelPrompt = authenticator.authenticate(title, null) { result ->
                    cancelPrompt = null
                    viewModel.onAuthResult(result)
                }
            }
        }
    }
    val currentUnlock by rememberUpdatedState(unlock)
    // Once when the lock screen comes up, and again after every trip to the background.
    var askOnResume by remember { mutableStateOf(true) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { askOnResume = true }
    LifecycleResumeEffect(viewModel, authenticator) {
        // Back from the system settings with a screen lock set up: unlock with it as usual.
        if (authenticator.availability() == DeviceLockAvailability.AVAILABLE) viewModel.onScreenLockAvailable()
        if (askOnResume) {
            askOnResume = false
            currentUnlock()
        }
        onPauseOrDispose { }
    }
    // The lock screen goes away with the prompt open (rotation): the prompt closes, and "Lås upp" works again.
    DisposableEffect(viewModel) {
        onDispose {
            cancelPrompt?.let { cancel ->
                cancel()
                viewModel.onAuthResult(DeviceAuthResult.Cancelled)
            }
        }
    }
    AppLockScreen(
        state = state,
        onAction = { action ->
            when (action) {
                AppLockAction.Unlock -> unlock()
                AppLockAction.OpenScreenLockSettings -> openScreenLockSettings(context)
                AppLockAction.ContinueWithoutLock -> viewModel.continueWithoutLock()
            }
        },
    )
}

/** System back on the lock screen leaves the app (the app stays as it was, locked). */
internal fun Context.leaveApp() {
    findActivity()?.moveTaskToBack(true)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun openScreenLockSettings(context: Context) {
    try {
        context.startActivity(screenLockSettingsIntent())
    } catch (e: ActivityNotFoundException) {
        // No settings app (never expected): the lock screen stays as it is.
    }
}

/** The lock's own window draws behind the bars; their icons follow the app's theme like the app's window. */
@Composable
private fun LockWindowBars() {
    val view = LocalView.current
    val window = (view.parent as? DialogWindowProvider)?.window ?: return
    val lightBars = MaterialTheme.colorScheme.background.luminance() > HALF
    SideEffect {
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = lightBars
        controller.isAppearanceLightNavigationBars = lightBars
        // No dimming behind it and no fade: the lock is simply there.
        window.setDimAmount(0f)
        window.setWindowAnimations(0)
    }
}

/** Not drawn, not read by TalkBack: the content behind the lock. */
private fun Modifier.hiddenContent(): Modifier = clearAndSetSemantics { }.drawWithContent { }

/** Takes every touch before it reaches the content behind the lock. */
private fun Modifier.swallowTouches(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }
}

private const val HALF = 0.5f
