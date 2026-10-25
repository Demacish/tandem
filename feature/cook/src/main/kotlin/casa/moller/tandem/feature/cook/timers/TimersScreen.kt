package casa.moller.tandem.feature.cook.timers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import casa.moller.tandem.core.data.timer.TimerController
import casa.moller.tandem.core.designsystem.component.TandemTextButton
import casa.moller.tandem.core.designsystem.component.TandemTopAppBar
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.icon.Timer
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.model.timer.CookTimer
import casa.moller.tandem.core.model.timer.TimerSource
import casa.moller.tandem.core.model.timer.TimerState
import casa.moller.tandem.core.ui.EmptyState
import casa.moller.tandem.core.ui.timer.TimerActions
import casa.moller.tandem.core.ui.timer.TimerCard
import casa.moller.tandem.core.ui.timer.rememberTimerNow
import casa.moller.tandem.core.ui.timer.sortedForDisplay
import casa.moller.tandem.feature.cook.R
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** What the timer list can do. */
sealed interface TimersAction {
    data class Pause(val id: Long) : TimersAction

    data class Resume(val id: Long) : TimersAction

    data class AddMinute(val id: Long) : TimersAction

    data class Cancel(val id: Long) : TimersAction

    data class StopRinging(val id: Long) : TimersAction

    data object ClearFinished : TimersAction
}

/** The device's timers ([TimerController.timers]) for [TimersScreen]. */
@HiltViewModel
class TimersViewModel @Inject constructor(private val timerController: TimerController) : ViewModel() {
    val timers: StateFlow<List<CookTimer>> = timerController.timers

    fun onAction(action: TimersAction) {
        viewModelScope.launch {
            when (action) {
                is TimersAction.Pause -> timerController.pause(action.id)
                is TimersAction.Resume -> timerController.resume(action.id)
                is TimersAction.AddMinute -> timerController.addOneMinute(action.id)
                is TimersAction.Cancel -> timerController.cancel(action.id)
                is TimersAction.StopRinging -> timerController.stopRinging(action.id)
                TimersAction.ClearFinished -> timerController.clearFinished()
            }
        }
    }
}

/**
 * Every timer of the device as cards (plan 8.1 "Timer-HUD"), ringing ones first; a timer of a
 * recipe opens cook mode at its step. "Rensa klara" removes the finished ones.
 *
 * @param activeSource the app's source now (core:model `TimerSource`): a timer started in demo
 *   mode or for another account still runs here, but does not open that recipe id in this source.
 */
@Composable
fun TimersScreen(
    timers: List<CookTimer>,
    onAction: (TimersAction) -> Unit,
    onBack: () -> Unit,
    onOpenCook: (CookTimer) -> Unit,
    modifier: Modifier = Modifier,
    activeSource: String? = null,
) {
    val now by rememberTimerNow()
    val actions = remember(onAction) {
        TimerActions(
            onPause = { onAction(TimersAction.Pause(it)) },
            onResume = { onAction(TimersAction.Resume(it)) },
            onAddMinute = { onAction(TimersAction.AddMinute(it)) },
            onCancel = { onAction(TimersAction.Cancel(it)) },
            onStopRinging = { onAction(TimersAction.StopRinging(it)) },
        )
    }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TandemTopAppBar(
                title = stringResource(R.string.cook_timers_title),
                onNavigateBack = onBack,
                actions = {
                    if (timers.any { it.state == TimerState.FINISHED }) {
                        TandemTextButton(
                            text = stringResource(R.string.cook_timers_clear_finished),
                            onClick = { onAction(TimersAction.ClearFinished) },
                        )
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (timers.isEmpty()) {
            EmptyState(
                icon = TandemIcons.Timer,
                title = stringResource(R.string.cook_timers_empty_title),
                body = stringResource(R.string.cook_timers_empty_body),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
        } else {
            val spacing = TandemTheme.spacing
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(horizontal = spacing.screenHorizontal, vertical = spacing.lg),
                verticalArrangement = Arrangement.spacedBy(spacing.md),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                items(timers.sortedForDisplay(), key = { it.id }) { timer ->
                    val opensCook = timer.recipeId != null && TimerSource.matches(timer, activeSource)
                    val step = timer.stepIndex?.takeIf { opensCook }
                    TimerCard(
                        timer = timer,
                        now = now,
                        actions = actions,
                        overline = step?.let { stringResource(R.string.cook_step_heading, it + 1) },
                        onOpen = if (opensCook) ({ onOpenCook(timer) }) else null,
                        modifier = Modifier.widthIn(max = TandemTheme.sizes.maxReadingWidth),
                    )
                }
            }
        }
    }
}
