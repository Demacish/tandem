package casa.moller.tandem.core.ui.security

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/**
 * Fas 5 (las lane, plan §5.5): the phone's own screen lock, asked through the system's
 * `BiometricPrompt` with a strong biometric or the PIN, pattern or password
 * (`BIOMETRIC_STRONG or DEVICE_CREDENTIAL`). The app never sees a fingerprint, a face or a PIN: the
 * system only says whether it was confirmed.
 *
 * Screens get it from [rememberDeviceAuthenticator]; tests provide a fake through
 * [LocalDeviceAuthenticator].
 */
@Stable
interface DeviceAuthenticator {
    /** Whether the phone has a screen lock to confirm (read again every time: the user can change it in Settings). */
    fun availability(): DeviceLockAvailability

    /**
     * Shows the system's prompt with [title] and [subtitle]; [onResult] is called once, on the main
     * thread. The returned function cancels a prompt that is still open (the screen went away).
     */
    fun authenticate(title: String, subtitle: String?, onResult: (DeviceAuthResult) -> Unit): () -> Unit
}

/** Whether the phone can confirm its owner. */
enum class DeviceLockAvailability {
    /** A screen lock (PIN, pattern, password; a strong biometric on top) is set. */
    AVAILABLE,

    /** No screen lock: the app lock cannot be turned on (the user is sent to the system settings). */
    NO_SCREEN_LOCK,
}

/** How a prompt ended. */
sealed interface DeviceAuthResult {
    /** The screen lock was confirmed. */
    data object Succeeded : DeviceAuthResult

    /** The user closed the prompt (back, outside, the app left the screen). Nothing to say. */
    data object Cancelled : DeviceAuthResult

    /** The phone has no screen lock (any more). */
    data object NoScreenLock : DeviceAuthResult

    /**
     * Anything else the system said, e.g. too many attempts. [message] is the system's own text
     * (already in the phone's language), null when it gave none.
     */
    data class Failed(val message: String?) : DeviceAuthResult
}

/** A fake in tests; null in the app, where [rememberDeviceAuthenticator] uses the system's prompt. */
val LocalDeviceAuthenticator = staticCompositionLocalOf<DeviceAuthenticator?> { null }

/** The authenticator for this screen: [LocalDeviceAuthenticator] when a test set one, otherwise the system's. */
@Composable
fun rememberDeviceAuthenticator(): DeviceAuthenticator {
    val provided = LocalDeviceAuthenticator.current
    val context = LocalContext.current
    return remember(provided, context) { provided ?: SystemDeviceAuthenticator(context) }
}

/** The system settings where a screen lock is set up (the security page; it exists on every phone). */
fun screenLockSettingsIntent(): Intent = Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/**
 * [DeviceAuthenticator] on the platform's [BiometricPrompt] (API 30+ takes the allowed
 * authenticators; minSdk is 34). Needs `USE_BIOMETRIC` (normal permission, in the app's manifest).
 */
internal class SystemDeviceAuthenticator(private val context: Context) : DeviceAuthenticator {
    override fun availability(): DeviceLockAvailability {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        return if (keyguard?.isDeviceSecure == true) DeviceLockAvailability.AVAILABLE else DeviceLockAvailability.NO_SCREEN_LOCK
    }

    override fun authenticate(title: String, subtitle: String?, onResult: (DeviceAuthResult) -> Unit): () -> Unit {
        if (availability() == DeviceLockAvailability.NO_SCREEN_LOCK) {
            onResult(DeviceAuthResult.NoScreenLock)
            return {}
        }
        val cancellation = CancellationSignal()
        var answered = false
        val answer: (DeviceAuthResult) -> Unit = { result ->
            if (!answered) {
                answered = true
                onResult(result)
            }
        }
        val prompt = BiometricPrompt.Builder(context)
            .setTitle(title)
            .apply { if (subtitle != null) setSubtitle(subtitle) }
            .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
            .setConfirmationRequired(false)
            .build()
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = answer(DeviceAuthResult.Succeeded)

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = answer(errorCode.toResult(errString))

            // A finger that did not match: the prompt stays open and says so itself.
            override fun onAuthenticationFailed() = Unit
        }
        try {
            prompt.authenticate(cancellation, context.mainExecutor, callback)
        } catch (e: SecurityException) {
            answer(DeviceAuthResult.Failed(null))
        } catch (e: IllegalArgumentException) {
            answer(DeviceAuthResult.Failed(null))
        }
        return {
            answered = true
            cancellation.cancel()
        }
    }

    private fun Int.toResult(message: CharSequence): DeviceAuthResult = when (this) {
        BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED,
        BiometricPrompt.BIOMETRIC_ERROR_CANCELED,
        -> DeviceAuthResult.Cancelled

        BiometricPrompt.BIOMETRIC_ERROR_NO_DEVICE_CREDENTIAL -> DeviceAuthResult.NoScreenLock

        else -> DeviceAuthResult.Failed(message.toString().takeIf { it.isNotBlank() })
    }
}
