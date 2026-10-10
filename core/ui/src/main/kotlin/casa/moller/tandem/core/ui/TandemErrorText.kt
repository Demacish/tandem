package casa.moller.tandem.core.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.model.error.InvalidUrlReason
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.error.TlsErrorKind

/**
 * What the user sees for a [TandemError]: a localized title and explanation, an icon and the
 * technical details for "Visa detaljer". Generic texts that fit most screens; a screen with a
 * better explanation for its context (e.g. wrong password on the login step) shows its own.
 */
@Immutable
data class TandemErrorText(
    val title: String,
    val body: String,
    val icon: ImageVector,
    /** Type and fields of the error. TandemError never contains tokens or passwords. */
    val details: String,
)

/** Localized texts and icon for this error (plan 5.4). */
@Composable
fun TandemError.toErrorText(): TandemErrorText {
    val spec = spec()
    return TandemErrorText(
        title = stringResource(spec.title),
        body = stringResource(spec.body),
        icon = spec.icon,
        details = toString(),
    )
}

/**
 * [ErrorState] for a [TandemError], with its localized title, body, icon and details.
 *
 * @param onRetry shows "Try again" when set. Leave it out where retrying cannot help, e.g.
 *   [TandemError.Throttled] on login (10 attempts per day).
 */
@Composable
fun ErrorState(
    error: TandemError,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    secondaryAction: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val text = error.toErrorText()
    ErrorState(
        title = text.title,
        body = text.body,
        modifier = modifier,
        icon = text.icon,
        details = text.details,
        onRetry = onRetry,
        secondaryAction = secondaryAction,
    )
}

private class ErrorSpec(@StringRes val title: Int, @StringRes val body: Int, val icon: ImageVector)

@Composable
private fun TandemError.spec(): ErrorSpec = when (this) {
    TandemError.NetworkUnavailable ->
        ErrorSpec(R.string.ui_error_network_title, R.string.ui_error_network_body, TandemIcons.WifiOff)

    TandemError.UnknownHost ->
        ErrorSpec(R.string.ui_error_unknown_host_title, R.string.ui_error_unknown_host_body, TandemIcons.WifiOff)

    TandemError.Timeout ->
        ErrorSpec(R.string.ui_error_timeout_title, R.string.ui_error_timeout_body, TandemIcons.Schedule)

    is TandemError.TlsError -> when (kind) {
        TlsErrorKind.UNTRUSTED_CERTIFICATE ->
            ErrorSpec(R.string.ui_error_tls_untrusted_title, R.string.ui_error_tls_untrusted_body, TandemIcons.Lock)

        TlsErrorKind.HOSTNAME_MISMATCH, TlsErrorKind.HANDSHAKE_FAILED ->
            ErrorSpec(R.string.ui_error_tls_title, R.string.ui_error_tls_body, TandemIcons.Lock)
    }

    is TandemError.InvalidUrl -> ErrorSpec(
        R.string.ui_error_invalid_url_title,
        if (reason == InvalidUrlReason.CLEARTEXT_NOT_ALLOWED) {
            R.string.ui_error_invalid_url_cleartext_body
        } else {
            R.string.ui_error_invalid_url_body
        },
        TandemIcons.Dns,
    )

    is TandemError.NotTandoor ->
        ErrorSpec(R.string.ui_error_not_tandoor_title, R.string.ui_error_not_tandoor_body, TandemIcons.Dns)

    is TandemError.Unauthorized ->
        ErrorSpec(R.string.ui_error_unauthorized_title, R.string.ui_error_unauthorized_body, TandemIcons.Key)

    TandemError.Blocked ->
        ErrorSpec(R.string.ui_error_blocked_title, R.string.ui_error_blocked_body, TandemIcons.Shield)

    TandemError.NotFound ->
        ErrorSpec(R.string.ui_error_not_found_title, R.string.ui_error_not_found_body, TandemIcons.Info)

    is TandemError.BadRequest ->
        ErrorSpec(R.string.ui_error_bad_request_title, R.string.ui_error_bad_request_body, TandemIcons.Warning)

    is TandemError.Throttled ->
        ErrorSpec(R.string.ui_error_throttled_title, R.string.ui_error_throttled_body, TandemIcons.Schedule)

    is TandemError.ServerError ->
        ErrorSpec(R.string.ui_error_server_title, R.string.ui_error_server_body, TandemIcons.CloudOff)

    is TandemError.Decode ->
        ErrorSpec(R.string.ui_error_decode_title, R.string.ui_error_decode_body, TandemIcons.Error)

    is TandemError.Unknown ->
        ErrorSpec(R.string.ui_error_unknown_title, R.string.ui_error_unknown_body, TandemIcons.Error)
}
