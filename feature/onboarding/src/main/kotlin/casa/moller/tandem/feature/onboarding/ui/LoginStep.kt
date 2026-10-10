package casa.moller.tandem.feature.onboarding.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import casa.moller.tandem.core.data.server.ServerInfo
import casa.moller.tandem.core.designsystem.component.CalloutCard
import casa.moller.tandem.core.designsystem.component.InfoCard
import casa.moller.tandem.core.designsystem.component.TandemPrimaryButton
import casa.moller.tandem.core.designsystem.component.TandemSecondaryButton
import casa.moller.tandem.core.designsystem.component.TandemTextButton
import casa.moller.tandem.core.designsystem.component.TandemTextField
import casa.moller.tandem.core.designsystem.component.TandemTextFieldType
import casa.moller.tandem.core.designsystem.component.TandemTone
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.ui.AppStatus
import casa.moller.tandem.core.ui.AppStatusChip
import casa.moller.tandem.core.ui.toErrorText
import casa.moller.tandem.feature.onboarding.LoginError
import casa.moller.tandem.feature.onboarding.LoginMethod
import casa.moller.tandem.feature.onboarding.OnboardingActions
import casa.moller.tandem.feature.onboarding.OnboardingUiState
import casa.moller.tandem.feature.onboarding.R
import kotlin.math.ceil
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

internal const val USERNAME_FIELD_TAG = "onboarding_username"
internal const val PASSWORD_FIELD_TAG = "onboarding_password"
internal const val TOKEN_FIELD_TAG = "onboarding_token"

/**
 * Step 3: username and password (autofill, show/hide), or a pasted API token, with "Try demo
 * mode instead" below the login button (design README corrections). The password is never
 * saved; only this device's own token is (plan 4.2).
 */
@Composable
internal fun ColumnScope.LoginStep(state: OnboardingUiState, actions: OnboardingActions) {
    val server = state.server ?: return
    val busy = state.loginInProgress

    StepHeader(step = 3, onBack = if (busy) null else actions::onBack)
    ConnectedServerCard(server)
    StepTitle(stringResource(R.string.onboarding_login_title))

    when (state.loginMethod) {
        LoginMethod.PASSWORD -> {
            StepBody(stringResource(R.string.onboarding_login_body))
            TandemTextField(
                value = state.username,
                onValueChange = actions::onUsernameChange,
                label = stringResource(R.string.onboarding_username),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(USERNAME_FIELD_TAG)
                    .semantics { contentType = ContentType.Username },
                type = TandemTextFieldType.Username,
                enabled = !busy,
                imeAction = ImeAction.Next,
            )
            TandemTextField(
                value = state.password,
                onValueChange = actions::onPasswordChange,
                label = stringResource(R.string.onboarding_password),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(PASSWORD_FIELD_TAG)
                    .semantics { contentType = ContentType.Password },
                type = TandemTextFieldType.Password,
                enabled = !busy,
                imeAction = ImeAction.Done,
                keyboardActions = KeyboardActions(onDone = { actions.onLogin() }),
            )
        }

        LoginMethod.TOKEN -> {
            StepBody(stringResource(R.string.onboarding_token_body))
            TandemTextField(
                value = state.token,
                onValueChange = actions::onTokenChange,
                label = stringResource(R.string.onboarding_token_label),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TOKEN_FIELD_TAG),
                type = TandemTextFieldType.Password,
                placeholder = stringResource(R.string.onboarding_token_placeholder),
                enabled = !busy,
                leadingIcon = TandemIcons.Key,
                imeAction = ImeAction.Done,
                keyboardActions = KeyboardActions(onDone = { actions.onLogin() }),
            )
            InfoCard(
                title = stringResource(R.string.onboarding_token_howto_title),
                body = stringResource(R.string.onboarding_token_howto_body),
                icon = TandemIcons.Key,
            )
        }
    }

    state.loginError?.let { LoginErrorCard(it) }

    if (state.loginMethod == LoginMethod.PASSWORD) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(TandemTheme.spacing.sm),
        ) {
            Icon(
                imageVector = TandemIcons.Lock,
                contentDescription = null,
                modifier = Modifier.size(TandemTheme.sizes.iconSmall),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(R.string.onboarding_login_privacy),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    TandemPrimaryButton(
        text = stringResource(if (busy) R.string.onboarding_logging_in else R.string.onboarding_log_in),
        onClick = actions::onLogin,
        modifier = Modifier.fillMaxWidth(),
        enabled = state.canLogIn,
        trailingIcon = TandemIcons.ArrowForward,
    )
    TandemTextButton(
        text = stringResource(
            if (state.loginMethod == LoginMethod.PASSWORD) R.string.onboarding_use_token else R.string.onboarding_use_password,
        ),
        onClick = {
            actions.onLoginMethodChange(
                if (state.loginMethod == LoginMethod.PASSWORD) LoginMethod.TOKEN else LoginMethod.PASSWORD,
            )
        },
        enabled = !busy,
        leadingIcon = if (state.loginMethod == LoginMethod.PASSWORD) TandemIcons.ContentPaste else TandemIcons.Lock,
    )
    TandemSecondaryButton(
        text = stringResource(R.string.onboarding_try_demo_instead),
        onClick = actions::onStartDemo,
        modifier = Modifier.fillMaxWidth(),
        enabled = !busy,
        leadingIcon = TandemIcons.Science,
    )
}

/** "Connected to <server> · Tandoor <version>" at the top of the login step. */
@Composable
private fun ConnectedServerCard(server: ServerInfo) {
    CalloutCard(
        title = stringResource(R.string.onboarding_server_connected, server.displayAddress),
        body = stringResource(R.string.onboarding_server_version, server.version),
        icon = TandemIcons.CheckCircle,
        tone = TandemTone.Success,
        actions = if (server.compatibility.isSafeMode) {
            { AppStatusChip(AppStatus.SafeMode) }
        } else {
            null
        },
    )
}

@Composable
private fun LoginErrorCard(error: LoginError) {
    val (title, body) = when (error) {
        LoginError.WrongCredentials -> stringResource(R.string.onboarding_error_wrong_credentials_title) to
            stringResource(R.string.onboarding_error_wrong_credentials_body)

        is LoginError.Throttled -> stringResource(R.string.onboarding_error_throttled_title) to
            listOfNotNull(
                stringResource(R.string.onboarding_error_throttled_body),
                error.retryAfter?.let { retryAfterText(it) },
            ).joinToString(separator = " ")

        LoginError.InvalidToken -> stringResource(R.string.onboarding_error_invalid_token_title) to
            stringResource(R.string.onboarding_error_invalid_token_body)

        LoginError.NoSpace -> stringResource(R.string.onboarding_error_no_space_title) to
            stringResource(R.string.onboarding_error_no_space_body)

        LoginError.StorageFailed -> stringResource(R.string.onboarding_error_storage_title) to
            stringResource(R.string.onboarding_error_storage_body)

        is LoginError.Other -> error.error.toErrorText().let { it.title to it.body }
    }
    CalloutCard(
        title = title,
        body = body,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        icon = if (error is LoginError.Throttled) TandemIcons.Schedule else TandemIcons.Error,
        tone = TandemTone.Error,
    )
}

/** "You can try again in about 5 hours." Rounded up; at least one minute. */
@Composable
private fun retryAfterText(retryAfter: Duration): String = if (retryAfter >= 1.hours) {
    val hours = ceil(retryAfter.inWholeMinutes / 60.0).toInt()
    pluralStringResource(R.plurals.onboarding_retry_hours, hours, hours)
} else {
    val minutes = ceil(retryAfter.inWholeSeconds / 60.0).toInt().coerceAtLeast(1)
    pluralStringResource(R.plurals.onboarding_retry_minutes, minutes, minutes)
}
