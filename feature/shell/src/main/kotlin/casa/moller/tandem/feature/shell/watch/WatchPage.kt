package casa.moller.tandem.feature.shell.watch

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.icon.Watch
import casa.moller.tandem.core.model.watch.WatchAvailability
import casa.moller.tandem.core.model.watch.WatchState
import casa.moller.tandem.feature.shell.R
import casa.moller.tandem.feature.shell.ui.SettingsGroup
import casa.moller.tandem.feature.shell.ui.SettingsRow

// Fas 5 (wear lane): Mer → Klockan as the shell shows it. Kept apart from SettingsPages (like
// FastPushPage) so the shell's own tests show the stateless screen and the Mer row without Hilt.

/** The watch row in Mer and its page. Production: [RouteWatchPage]; [NoWatchPage] shows nothing. */
@Stable
internal interface WatchPage {
    /** The row in Mer, only when there is a watch (or a pairing to undo). */
    @Composable
    fun MoreRow(selected: Boolean, onOpen: () -> Unit)

    @Composable
    fun Watch(onNavigateBack: () -> Unit)
}

/** No watch row and no page (the shell's tests that are not about the watch). */
internal object NoWatchPage : WatchPage {
    @Composable
    override fun MoreRow(selected: Boolean, onOpen: () -> Unit) = Unit

    @Composable
    override fun Watch(onNavigateBack: () -> Unit) = Unit
}

/** The real row and page with their [WatchViewModel]s. */
internal object RouteWatchPage : WatchPage {
    @Composable
    override fun MoreRow(selected: Boolean, onOpen: () -> Unit) {
        val viewModel: WatchViewModel = hiltViewModel(key = "watch-more-row")
        val state by viewModel.uiState.collectAsStateWithLifecycle()
        WatchMoreRow(state.watch, selected, onOpen)
    }

    @Composable
    override fun Watch(onNavigateBack: () -> Unit) {
        val viewModel: WatchViewModel = hiltViewModel()
        val state by viewModel.uiState.collectAsStateWithLifecycle()
        val snackbarHostState = remember { SnackbarHostState() }
        val resources = LocalResources.current
        // Back from the watch's app or from Bluetooth: look again.
        LifecycleResumeEffect(viewModel) {
            viewModel.onAction(WatchAction.Refresh)
            onPauseOrDispose { }
        }
        LaunchedEffect(viewModel, snackbarHostState) {
            viewModel.effect.collect { effect ->
                when (effect) {
                    is WatchEffect.Message -> snackbarHostState.showSnackbar(resources.getString(effect.text))
                }
            }
        }
        WatchScreen(state = state, onAction = viewModel::onAction, onNavigateBack = onNavigateBack, snackbarHostState = snackbarHostState)
    }
}

/** Mer → Klockan, with what is there to do; nothing when [WatchState.showInMore] is false. */
@Composable
internal fun WatchMoreRow(watch: WatchState, selected: Boolean, onOpen: () -> Unit) {
    if (!watch.showInMore) return
    val name = watch.watchName.orEmpty()
    val paired = watch.paired
    val supporting = when {
        paired != null && watch.availability != WatchAvailability.READY -> stringResource(R.string.watch_more_away)
        paired != null -> stringResource(R.string.watch_more_paired, paired.watchName)
        watch.availability == WatchAvailability.NO_APP -> stringResource(R.string.watch_more_no_app, name)
        else -> stringResource(R.string.watch_more_ready, name)
    }
    SettingsGroup {
        SettingsRow(
            icon = TandemIcons.Watch,
            title = stringResource(R.string.watch_title),
            supporting = supporting,
            onClick = onOpen,
            selected = selected,
        )
    }
}
