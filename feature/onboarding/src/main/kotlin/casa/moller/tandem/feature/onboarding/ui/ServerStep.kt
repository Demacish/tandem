package casa.moller.tandem.feature.onboarding.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import casa.moller.tandem.core.data.server.ServerCompatibility
import casa.moller.tandem.core.designsystem.R as DesignR
import casa.moller.tandem.core.designsystem.component.CalloutCard
import casa.moller.tandem.core.designsystem.component.InfoCard
import casa.moller.tandem.core.designsystem.component.TandemButtonSize
import casa.moller.tandem.core.designsystem.component.TandemLoadingIndicator
import casa.moller.tandem.core.designsystem.component.TandemPrimaryButton
import casa.moller.tandem.core.designsystem.component.TandemTextButton
import casa.moller.tandem.core.designsystem.component.TandemTextField
import casa.moller.tandem.core.designsystem.component.TandemTextFieldType
import casa.moller.tandem.core.designsystem.component.TandemTonalButton
import casa.moller.tandem.core.designsystem.component.TandemTone
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.error.TlsErrorKind
import casa.moller.tandem.core.ui.R as UiR
import casa.moller.tandem.core.ui.toErrorText
import casa.moller.tandem.feature.onboarding.OnboardingActions
import casa.moller.tandem.feature.onboarding.OnboardingUiState
import casa.moller.tandem.feature.onboarding.R
import casa.moller.tandem.feature.onboarding.ServerCheck

/** Tandoor's official installation guide (the upstream project's docs, not a hosting offer). */
internal const val TANDOOR_INSTALL_DOCS_URL = "https://docs.tandoor.dev/install/docker/"

/** Test tag of the server address field. */
internal const val SERVER_URL_FIELD_TAG = "onboarding_server_url"

/**
 * Step 2: the server address, probe with progress, inline errors with what to do about them,
 * the compatibility warning (safe mode) or block, and "Don't have a Tandoor server?".
 */
@Composable
internal fun ColumnScope.ServerStep(state: OnboardingUiState, actions: OnboardingActions) {
    var showGetTandoor by rememberSaveable { mutableStateOf(false) }
    val check = state.serverCheck

    StepHeader(step = 2, onBack = actions::onBack)
    StepTitle(stringResource(R.string.onboarding_server_title))
    StepBody(stringResource(R.string.onboarding_server_body))

    TandemTextField(
        value = state.serverUrl,
        onValueChange = actions::onServerUrlChange,
        label = stringResource(R.string.onboarding_server_label),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(SERVER_URL_FIELD_TAG),
        type = TandemTextFieldType.Url,
        placeholder = stringResource(R.string.onboarding_server_placeholder),
        supportingText = stringResource(R.string.onboarding_server_hint),
        isError = check is ServerCheck.Failed,
        imeAction = ImeAction.Go,
        keyboardActions = KeyboardActions(onGo = { actions.onConnect() }),
    )

    when (check) {
        ServerCheck.Idle -> Unit

        ServerCheck.Checking -> CheckingRow()

        is ServerCheck.Failed -> ServerErrorCard(check.error)

        is ServerCheck.NeedsConfirmation -> CalloutCard(
            title = if (check.server.compatibility == ServerCompatibility.UNRECOGNIZED) {
                stringResource(R.string.onboarding_unrecognized_title, check.server.version)
            } else {
                stringResource(R.string.onboarding_untested_title, check.server.version)
            },
            body = stringResource(R.string.onboarding_untested_body),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            icon = TandemIcons.Warning,
            tone = TandemTone.Warning,
            actions = {
                TandemPrimaryButton(
                    text = stringResource(R.string.onboarding_continue_safe_mode),
                    onClick = actions::onContinueInSafeMode,
                    trailingIcon = TandemIcons.ArrowForward,
                    size = TandemButtonSize.Small,
                )
            },
        )

        is ServerCheck.TooOld -> CalloutCard(
            title = stringResource(R.string.onboarding_too_old_title, check.server.version),
            body = stringResource(R.string.onboarding_too_old_body),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            icon = TandemIcons.Error,
            tone = TandemTone.Error,
        )
    }

    TandemPrimaryButton(
        text = stringResource(
            if (check == ServerCheck.Checking) R.string.onboarding_checking_button else R.string.onboarding_connect,
        ),
        onClick = actions::onConnect,
        modifier = Modifier.fillMaxWidth(),
        enabled = state.canConnect,
        trailingIcon = TandemIcons.ArrowForward,
    )

    CalloutCard(
        title = stringResource(R.string.onboarding_no_tandoor_title),
        body = stringResource(R.string.onboarding_no_tandoor_body),
        icon = TandemIcons.Dns,
        tone = TandemTone.Neutral,
        actions = {
            TandemTonalButton(
                text = stringResource(R.string.onboarding_try_demo),
                onClick = actions::onStartDemo,
                leadingIcon = TandemIcons.Science,
                size = TandemButtonSize.Small,
            )
            TandemTextButton(
                text = stringResource(R.string.onboarding_get_tandoor),
                onClick = { showGetTandoor = true },
            )
        },
    )

    if (showGetTandoor) {
        GetTandoorSheet(onDismiss = { showGetTandoor = false })
    }
}

@Composable
private fun CheckingRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TandemTheme.spacing.md),
    ) {
        TandemLoadingIndicator(modifier = Modifier.size(40.dp))
        Text(
            text = stringResource(R.string.onboarding_checking),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Inline error of the probe: the shared text for the error type plus, where it helps, what the
 * user can do about it (install a CA certificate, open paths in a firewall, add the sub-path).
 */
@Composable
private fun ServerErrorCard(error: TandemError) {
    val text = error.toErrorText()
    // On the public settings endpoint a 401 means a login portal in front of Tandoor, not an
    // expired login, so the generic "Log in again" text would be wrong here.
    val proxyLogin = error is TandemError.Unauthorized
    var showDetails by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(TandemTheme.spacing.sm),
    ) {
        CalloutCard(
            title = if (proxyLogin) stringResource(R.string.onboarding_proxy_login_title) else text.title,
            body = if (proxyLogin) stringResource(R.string.onboarding_hint_blocked) else text.body,
            icon = text.icon,
            tone = TandemTone.Error,
        )
        serverErrorHint(error)?.let { hint ->
            InfoCard(
                title = stringResource(R.string.onboarding_hint_title),
                body = hint,
                icon = TandemIcons.Info,
            )
        }
        TandemTextButton(
            text = stringResource(if (showDetails) UiR.string.ui_error_hide_details else UiR.string.ui_error_show_details),
            onClick = { showDetails = !showDetails },
        )
        if (showDetails) {
            SelectionContainer {
                Text(
                    text = text.details,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun serverErrorHint(error: TandemError): String? = when (error) {
    is TandemError.TlsError -> if (error.kind == TlsErrorKind.UNTRUSTED_CERTIFICATE) {
        stringResource(R.string.onboarding_hint_tls)
    } else {
        null
    }

    TandemError.Blocked -> stringResource(R.string.onboarding_hint_blocked)

    is TandemError.NotTandoor -> stringResource(R.string.onboarding_hint_not_tandoor)

    TandemError.Timeout -> stringResource(R.string.onboarding_hint_timeout)

    else -> null
}

/** "How to get started with Tandoor": a short explanation and the official install guide. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GetTandoorSheet(onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = TandemTheme.spacing.xl)
                .padding(bottom = TandemTheme.spacing.xl),
            verticalArrangement = Arrangement.spacedBy(TandemTheme.spacing.lg),
        ) {
            Text(
                text = stringResource(R.string.onboarding_get_tandoor_title),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = stringResource(R.string.onboarding_get_tandoor_body),
                style = MaterialTheme.typography.bodyLarge,
            )
            TandemPrimaryButton(
                text = stringResource(R.string.onboarding_open_install_guide),
                onClick = { runCatching { uriHandler.openUri(TANDOOR_INSTALL_DOCS_URL) } },
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = TandemIcons.OpenInNew,
            )
            TandemTextButton(
                text = stringResource(DesignR.string.tandem_action_close),
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}
