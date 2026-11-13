package casa.moller.tandem.feature.shell.watch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import casa.moller.tandem.core.designsystem.component.InfoCard
import casa.moller.tandem.core.designsystem.component.TandemPrimaryButton
import casa.moller.tandem.core.designsystem.component.TandemSecondaryButton
import casa.moller.tandem.core.designsystem.component.TandemTextButton
import casa.moller.tandem.core.designsystem.component.TandemTone
import casa.moller.tandem.core.designsystem.icon.Link
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.icon.Watch
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.watch.WatchAvailability
import casa.moller.tandem.core.model.watch.WatchBlock
import casa.moller.tandem.core.model.watch.WatchState
import casa.moller.tandem.core.ui.toErrorText
import casa.moller.tandem.feature.shell.R
import casa.moller.tandem.feature.shell.settings.GroupHeading
import casa.moller.tandem.feature.shell.settings.SettingText
import casa.moller.tandem.feature.shell.settings.SettingsPage
import casa.moller.tandem.feature.shell.settings.formatDateTime
import casa.moller.tandem.feature.shell.ui.SettingsGroup

/** Test tag of "Koppla klockan" / "Koppla igen". */
internal const val WATCH_PAIR_TAG = "watch:pair"

/** Test tag of "Koppla från klockan". */
internal const val WATCH_UNPAIR_TAG = "watch:unpair"

/**
 * Mer → **Klockan** (Fas 5, wear lane; plan §7 #33, §3.1): what Tandem on the watch does, the
 * watch the phone found (or why none), the pairing with its account and the key's expiry, "Koppla
 * klockan" / "Koppla från klockan", the installation guide and what is sent where. Demo mode,
 * guests and logged-out show only why it is not possible. Stateless; [WatchViewModel] drives it.
 */
@Composable
internal fun WatchScreen(
    state: WatchUiState,
    onAction: (WatchAction) -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    SettingsPage(
        title = stringResource(R.string.watch_title),
        onNavigateBack = onNavigateBack,
        modifier = modifier,
        snackbarHostState = snackbarHostState,
    ) {
        val watch = state.watch
        InfoCard(
            title = stringResource(R.string.watch_intro_title),
            body = stringResource(R.string.watch_intro_body),
            icon = TandemIcons.Watch,
        )
        val blocked = when (watch.block) {
            WatchBlock.DEMO -> R.string.watch_demo
            WatchBlock.GUEST -> R.string.watch_guest
            WatchBlock.NO_ACCOUNT -> R.string.watch_no_account
            null -> null
        }
        blocked?.let { InfoCard(title = stringResource(R.string.watch_title), body = stringResource(it), tone = TandemTone.Warning) }
        Status(watch)
        state.error?.let { error ->
            val text = error.toErrorText()
            InfoCard(title = text.title, body = text.body, icon = text.icon, tone = TandemTone.Warning)
        }
        Buttons(watch, onAction)
        Text(
            text = stringResource(R.string.watch_privacy),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = TandemTheme.spacing.lg),
        )
    }
}

/** The watch and the pairing, one TalkBack item that announces changes. */
@Composable
private fun Status(watch: WatchState) {
    val spacing = TandemTheme.spacing
    val name = watch.watchName.orEmpty()
    val paired = watch.paired
    val (title, body) = when {
        paired != null -> stringResource(R.string.watch_paired, paired.watchName) to listOfNotNull(
            stringResource(R.string.watch_paired_body, paired.accountLabel, formatDateTime(paired.tokenExpires)),
            if (watch.pairedForAnotherAccount) stringResource(R.string.watch_other_account) else null,
        ).joinToString("\n")

        else -> when (watch.availability) {
            WatchAvailability.CHECKING -> stringResource(R.string.watch_checking) to null
            WatchAvailability.UNAVAILABLE -> stringResource(R.string.watch_unavailable) to stringResource(R.string.watch_unavailable_body)
            WatchAvailability.NO_WATCH -> stringResource(R.string.watch_no_watch) to stringResource(R.string.watch_no_watch_body)
            WatchAvailability.NO_APP -> stringResource(R.string.watch_no_app, name) to stringResource(R.string.watch_no_app_body)
            WatchAvailability.READY -> stringResource(R.string.watch_ready, name) to stringResource(R.string.watch_ready_body)
        }
    }
    SettingsGroup {
        GroupHeading(text = stringResource(R.string.watch_status_heading), icon = TandemIcons.Watch)
        SettingText(
            title = title,
            body = body,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = spacing.lg, end = spacing.lg, bottom = spacing.lg)
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        )
    }
}

@Composable
private fun Buttons(watch: WatchState, onAction: (WatchAction) -> Unit) {
    val spacing = TandemTheme.spacing
    val uriHandler = LocalUriHandler.current
    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        if (watch.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        if (watch.block == null && watch.availability != WatchAvailability.UNAVAILABLE) {
            TandemPrimaryButton(
                text = stringResource(if (watch.paired == null) R.string.watch_pair else R.string.watch_pair_again),
                onClick = { onAction(WatchAction.Pair) },
                enabled = watch.canPair,
                leadingIcon = TandemIcons.Watch,
                modifier = Modifier.fillMaxWidth().testTag(WATCH_PAIR_TAG),
            )
        }
        if (watch.paired != null) {
            TandemSecondaryButton(
                text = stringResource(R.string.watch_unpair),
                onClick = { onAction(WatchAction.Unpair) },
                enabled = !watch.busy,
                modifier = Modifier.fillMaxWidth().testTag(WATCH_UNPAIR_TAG),
            )
        }
        if (watch.block == null && watch.availability != WatchAvailability.UNAVAILABLE) {
            TandemTextButton(
                text = stringResource(R.string.watch_search_again),
                onClick = { onAction(WatchAction.Refresh) },
                enabled = !watch.busy,
            )
        }
        TandemTextButton(
            text = stringResource(R.string.watch_guide),
            onClick = { runCatching { uriHandler.openUri(WEAR_GUIDE_URL) } },
            leadingIcon = TandemIcons.Link,
        )
    }
}

/** The project's public guide (docs/WEAR.md): installing on the watch, pairing and unpairing. */
private const val WEAR_GUIDE_URL = "https://github.com/Demacish/tandem/blob/main/docs/WEAR.md"
