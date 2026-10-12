package casa.moller.tandem.feature.shell

import androidx.annotation.StringRes
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/** One-off results of actions in the shell that the app shows as a toast. */
enum class ShellMessage(@StringRes val text: Int) {
    LOGGED_OUT(R.string.shell_logged_out),
    LOGGED_OUT_TOKEN_NOT_REVOKED(R.string.shell_logged_out_token_not_revoked),
    LOGGED_OUT_PASTED_TOKEN(R.string.shell_logged_out_pasted_token),
    LOGOUT_FAILED(R.string.shell_logout_failed),
    SETTINGS_NOT_SAVED(R.string.shell_settings_not_saved),
    DEMO_DATA_RESET(R.string.shell_demo_data_reset),
    DEMO_DATA_RESET_FAILED(R.string.shell_demo_data_reset_failed),
}

/**
 * App-wide queue for [ShellMessage]s. A logout ends the shell (the app switches to the intro and
 * the shell's ViewModel is cleared), so its result cannot be shown by the shell itself; the app
 * level collects [messages] instead. Messages wait in the buffer while nobody collects (e.g.
 * during an activity recreation).
 */
@Singleton
class ShellMessageQueue @Inject constructor() {
    private val channel = Channel<ShellMessage>(capacity = Channel.BUFFERED)

    /** Each message is delivered to one collector, once. */
    val messages: Flow<ShellMessage> = channel.receiveAsFlow()

    fun post(message: ShellMessage) {
        channel.trySend(message)
    }
}
