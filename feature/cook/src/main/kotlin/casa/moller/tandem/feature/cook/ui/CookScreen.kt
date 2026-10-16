package casa.moller.tandem.feature.cook.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import casa.moller.tandem.core.designsystem.component.CalloutCard
import casa.moller.tandem.core.designsystem.component.StatusChip
import casa.moller.tandem.core.designsystem.component.TandemPrimaryButton
import casa.moller.tandem.core.designsystem.component.TandemSecondaryButton
import casa.moller.tandem.core.designsystem.component.TandemTextButton
import casa.moller.tandem.core.designsystem.component.TandemTone
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.icon.TextDecrease
import casa.moller.tandem.core.designsystem.icon.TextIncrease
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.domain.format.toNumberStyle
import casa.moller.tandem.core.model.timer.CookTimer
import casa.moller.tandem.core.ui.ErrorState
import casa.moller.tandem.core.ui.LoadingState
import casa.moller.tandem.core.ui.recipe.IconLabel
import casa.moller.tandem.core.ui.recipe.currentLocale
import casa.moller.tandem.core.ui.timer.KitchenIconButton
import casa.moller.tandem.core.ui.timer.TimerActions
import casa.moller.tandem.core.ui.timer.TimerBar
import casa.moller.tandem.core.ui.timer.rememberTimerNow
import casa.moller.tandem.feature.cook.CookAction
import casa.moller.tandem.feature.cook.CookUiState
import casa.moller.tandem.feature.cook.R
import kotlin.time.Instant

/**
 * How cook mode lays itself out (plan 6.5, "Kokläge" row of the adaptive table).
 *
 * - [PHONE]: compact width: one step per page, the step's ingredients in an expandable
 *   section, the timer bar and the buttons at the bottom.
 * - [TWO_PANE]: at least [TwoPaneMinWidth] wide (tablets in both orientations, phones in
 *   landscape): the step's ingredients and the active timers on the left, the step large on
 *   the right (Stitch kokl_ge_surfplatta_*).
 * - [TABLETOP]: a half-folded foldable lying on the table: the step in the upper half, timers and
 *   buttons in the lower half, nothing on the fold (Stitch kokl_ge_bordsl_ge_vikbar_telefon).
 */
enum class CookLayout { PHONE, TWO_PANE, TABLETOP }

/** Windows at least this wide get two panes (M3 medium and up). */
internal val TwoPaneMinWidth = 600.dp

/** The step text is larger by default where there is room (plan 6.5 "extra stor text som standard"). */
internal val LargeStepTextMinWidth = 840.dp

internal fun cookLayoutFor(width: Dp, tabletop: Boolean): CookLayout = when {
    tabletop -> CookLayout.TABLETOP
    width >= TwoPaneMinWidth -> CookLayout.TWO_PANE
    else -> CookLayout.PHONE
}

/**
 * Cook mode (plan 6.2 "Kokläge", 6.3 wireframe, Stitch kokl_ge_*), stateless: [state] in,
 * [onAction] out. Full screen on every width; the screen stays on while it is shown.
 *
 * Keyboard (plan 6.5): arrow keys, Page Up/Down and space (Shift+space back) turn the pages.
 *
 * @param onClose leaves cook mode (✕ and "Close without saving").
 * @param onOpenNotificationSettings the app's notification settings (alarm may be silent).
 * @param onOpenExactAlarmSettings the system page that allows exact alarms (play build).
 * @param layout forces a layout (tests, previews); null decides from the window.
 */
@Composable
fun CookScreen(
    state: CookUiState,
    onAction: (CookAction) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    onOpenNotificationSettings: () -> Unit = {},
    onOpenExactAlarmSettings: () -> Unit = {},
    layout: CookLayout? = null,
) {
    val locale = currentLocale()
    val currentOnAction by rememberUpdatedState(onAction)
    LaunchedEffect(locale) { currentOnAction(CookAction.SetLocale(locale.toNumberStyle(), locale.toLanguageTag())) }
    KeepScreenOn()
    val tabletop = currentWindowAdaptiveInfoV2().windowPosture.isTabletop
    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            val chosen = layout ?: cookLayoutFor(maxWidth, tabletop)
            when {
                state.hasDetail -> CookContent(
                    state = state,
                    onAction = onAction,
                    onClose = onClose,
                    layout = chosen,
                    largeText = maxWidth >= LargeStepTextMinWidth,
                    onOpenNotificationSettings = onOpenNotificationSettings,
                    onOpenExactAlarmSettings = onOpenExactAlarmSettings,
                )

                else -> Column(Modifier.fillMaxSize()) {
                    CookHeader(state = state, onAction = onAction, onClose = onClose, showScreenOn = false, progress = null)
                    MissingRecipe(state = state, onAction = onAction)
                }
            }
        }
    }
    state.notificationPrompt?.let {
        NotificationPromptDialog(
            onContinue = { onAction(CookAction.AcceptNotificationPrompt) },
            onNotNow = { onAction(CookAction.DeclineNotificationPrompt) },
        )
    }
}

/** Keeps the display on while cook mode is shown (plan 6.2 "Skärmen hålls på"). */
@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

@Composable
private fun MissingRecipe(state: CookUiState, onAction: (CookAction) -> Unit) {
    val error = state.error
    when {
        state.loading || error == null -> LoadingState(modifier = Modifier.fillMaxSize(), message = stringResource(R.string.cook_loading))

        else -> Column(Modifier.verticalScroll(rememberScrollState())) {
            ErrorState(
                title = stringResource(R.string.cook_not_downloaded_title),
                body = stringResource(R.string.cook_not_downloaded_body),
                icon = TandemIcons.CloudOff,
                onRetry = { onAction(CookAction.Retry) },
            )
        }
    }
}

@Composable
private fun CookContent(
    state: CookUiState,
    onAction: (CookAction) -> Unit,
    onClose: () -> Unit,
    layout: CookLayout,
    largeText: Boolean,
    onOpenNotificationSettings: () -> Unit,
    onOpenExactAlarmSettings: () -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = state.page) { state.pageCount }
    PagerSync(pagerState = pagerState, page = state.page, onAction = onAction)
    val now by rememberTimerNow()
    val timerActions = remember(onAction) {
        TimerActions(
            onPause = { onAction(CookAction.PauseTimer(it)) },
            onResume = { onAction(CookAction.ResumeTimer(it)) },
            onAddMinute = { onAction(CookAction.AddMinute(it)) },
            onCancel = { onAction(CookAction.CancelTimer(it)) },
            onStopRinging = { onAction(CookAction.StopRinging(it)) },
        )
    }
    // A timer of this recipe jumps to its step when its name is tapped.
    val openTimer: (CookTimer) -> Unit = { timer ->
        val step = timer.stepIndex
        if (timer.recipeId == state.recipeId && step != null && step < state.steps.size) onAction(CookAction.GoToPage(step))
    }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(focusRequester) { runCatching { focusRequester.requestFocus() } }
    val progress = (state.page + 1).toFloat() / state.pageCount
    val pages: @Composable (Modifier, Boolean) -> Unit = { pagerModifier, showIngredients ->
        HorizontalPager(
            state = pagerState,
            modifier = pagerModifier.testTag(COOK_PAGER_TAG),
        ) { page ->
            if (page < state.steps.size) {
                CookStepPage(
                    state = state,
                    step = state.steps[page],
                    onAction = onAction,
                    showIngredients = showIngredients,
                    largeText = largeText,
                )
            } else {
                CookDonePage(state = state, onAction = onAction, onClose = onClose)
            }
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionRight, Key.PageDown -> true.also { onAction(CookAction.Next) }
                    Key.DirectionLeft, Key.PageUp -> true.also { onAction(CookAction.Previous) }
                    Key.Spacebar -> true.also { onAction(if (event.isShiftPressed) CookAction.Previous else CookAction.Next) }
                    else -> false
                }
            }
            .focusRequester(focusRequester)
            // A focus target without semantics: receives the keys, is no extra TalkBack stop.
            .focusTarget(),
    ) {
        CookHeader(state = state, onAction = onAction, onClose = onClose, showScreenOn = layout == CookLayout.TWO_PANE, progress = progress)
        when (layout) {
            CookLayout.PHONE -> {
                pages(Modifier.weight(1f), true)
                BottomArea(
                    state = state,
                    now = now,
                    timerActions = timerActions,
                    onOpenTimer = openTimer,
                    onAction = onAction,
                    onOpenNotificationSettings = onOpenNotificationSettings,
                    onOpenExactAlarmSettings = onOpenExactAlarmSettings,
                )
            }

            CookLayout.TWO_PANE -> Row(Modifier.weight(1f)) {
                CookSidePane(
                    state = state,
                    now = now,
                    timerActions = timerActions,
                    onOpenTimer = openTimer,
                    onAction = onAction,
                    onOpenNotificationSettings = onOpenNotificationSettings,
                    onOpenExactAlarmSettings = onOpenExactAlarmSettings,
                    modifier = Modifier
                        .weight(SIDE_PANE_WEIGHT)
                        .fillMaxHeight()
                        .testTag(COOK_SIDE_PANE_TAG),
                )
                VerticalDivider(Modifier.fillMaxHeight())
                Column(Modifier.weight(STEP_PANE_WEIGHT)) {
                    pages(Modifier.weight(1f), false)
                    PageButtons(state = state, onAction = onAction, modifier = Modifier.padding(TandemTheme.spacing.lg))
                }
            }

            CookLayout.TABLETOP -> {
                // Upper half: the step. Lower half (below the fold): timers and buttons.
                pages(Modifier.weight(1f), true)
                HorizontalDivider()
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .testTag(COOK_TABLETOP_CONTROLS_TAG),
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    BottomArea(
                        state = state,
                        now = now,
                        timerActions = timerActions,
                        onOpenTimer = openTimer,
                        onAction = onAction,
                        onOpenNotificationSettings = onOpenNotificationSettings,
                        onOpenExactAlarmSettings = onOpenExactAlarmSettings,
                        timerBarMaxHeight = TABLETOP_TIMER_BAR_HEIGHT,
                    )
                }
            }
        }
    }
}

/**
 * Keeps the pager and the ViewModel's page in step: a swipe settles → [CookAction.GoToPage];
 * a page change from the buttons, the keyboard or a restored state → the pager scrolls there.
 */
@Composable
private fun PagerSync(pagerState: PagerState, page: Int, onAction: (CookAction) -> Unit) {
    val currentOnAction by rememberUpdatedState(onAction)
    LaunchedEffect(page) {
        if (pagerState.currentPage != page && page < pagerState.pageCount) pagerState.animateScrollToPage(page)
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { settled -> currentOnAction(CookAction.GoToPage(settled)) }
    }
}

/** ✕, recipe name and "Steg 3 av 6", A−/A+, then the progress bar. */
@Composable
private fun CookHeader(state: CookUiState, onAction: (CookAction) -> Unit, onClose: () -> Unit, showScreenOn: Boolean, progress: Float?) {
    val spacing = TandemTheme.spacing
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KitchenIconButton(icon = TandemIcons.Close, description = stringResource(R.string.cook_close), onClick = onClose)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = spacing.xs),
            ) {
                Text(
                    text = state.recipeName.ifEmpty { stringResource(R.string.cook_title) },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
                if (state.hasDetail) {
                    Text(
                        text = if (state.isDonePage) {
                            stringResource(R.string.cook_done_page)
                        } else {
                            stringResource(R.string.cook_step_of, state.page + 1, state.steps.size)
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (showScreenOn) {
                StatusChip(
                    label = stringResource(R.string.cook_screen_on),
                    icon = TandemIcons.Visibility,
                    modifier = Modifier.padding(end = spacing.sm),
                )
            }
            if (state.hasDetail) {
                KitchenIconButton(
                    icon = TandemIcons.TextDecrease,
                    description = stringResource(R.string.cook_text_smaller),
                    onClick = { onAction(CookAction.SmallerText) },
                    enabled = state.canDecreaseText,
                )
                KitchenIconButton(
                    icon = TandemIcons.TextIncrease,
                    description = stringResource(R.string.cook_text_larger),
                    onClick = { onAction(CookAction.LargerText) },
                    enabled = state.canIncreaseText,
                )
            }
        }
        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.screenHorizontal, vertical = spacing.xs)
                    // The header already says "Step 3 of 6"; the bar is decoration for TalkBack.
                    .clearAndSetSemantics { },
            )
        }
    }
}

/** Phone and tabletop: warnings, the timer bar and the page buttons. */
@Composable
private fun BottomArea(
    state: CookUiState,
    now: Instant,
    timerActions: TimerActions,
    onOpenTimer: (CookTimer) -> Unit,
    onAction: (CookAction) -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onOpenExactAlarmSettings: () -> Unit,
    timerBarMaxHeight: Dp = PHONE_TIMER_BAR_HEIGHT,
) {
    val spacing = TandemTheme.spacing
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.lg, vertical = spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        AlarmWarnings(
            state = state,
            onAction = onAction,
            onOpenNotificationSettings = onOpenNotificationSettings,
            onOpenExactAlarmSettings = onOpenExactAlarmSettings,
        )
        TimerBar(
            timers = state.timers,
            now = now,
            actions = timerActions,
            onOpen = onOpenTimer,
            maxHeight = timerBarMaxHeight,
            modifier = Modifier.testTag(COOK_TIMER_BAR_TAG),
        )
        PageButtons(state = state, onAction = onAction)
    }
}

/** "Föregående" and "Nästa" (64 dp, plan 6.6); on the last step "Klart!". */
@Composable
internal fun PageButtons(state: CookUiState, onAction: (CookAction) -> Unit, modifier: Modifier = Modifier) {
    val spacing = TandemTheme.spacing
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
        TandemSecondaryButton(
            text = stringResource(R.string.cook_previous),
            onClick = { onAction(CookAction.Previous) },
            enabled = state.page > 0,
            leadingIcon = TandemIcons.ArrowBack,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = TandemTheme.sizes.kitchenTouchTarget),
        )
        if (!state.isDonePage) {
            TandemPrimaryButton(
                text = stringResource(if (state.page == state.donePage - 1) R.string.cook_next_done else R.string.cook_next),
                onClick = { onAction(CookAction.Next) },
                trailingIcon = TandemIcons.ArrowForward,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = TandemTheme.sizes.kitchenTouchTarget),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
    }
}

/**
 * The alarm may be silent (notifications off) or late (play build without exact alarms): said
 * while a timer runs, with a way to the system setting (plan 8.1/8.7, 4.4). Never the raw
 * permission names (docs/design/README.md).
 */
@Composable
internal fun AlarmWarnings(
    state: CookUiState,
    onAction: (CookAction) -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onOpenExactAlarmSettings: () -> Unit,
) {
    if (state.showSilentAlarmWarning) {
        CalloutCard(
            title = stringResource(R.string.cook_alarm_silent_title),
            body = stringResource(R.string.cook_alarm_silent_body),
            icon = TandemIcons.Warning,
            tone = TandemTone.Warning,
            actions = { TandemTextButton(text = stringResource(R.string.cook_open_settings), onClick = onOpenNotificationSettings) },
        )
    } else if (state.showLateAlarmWarning) {
        val canAsk = state.capabilities.exactAlarmsRequestable && !state.exactAlarmPromptDismissed
        if (canAsk) {
            CalloutCard(
                title = stringResource(R.string.cook_alarm_late_title),
                body = stringResource(R.string.cook_alarm_late_body),
                icon = TandemIcons.Warning,
                tone = TandemTone.Warning,
                actions = {
                    TandemTextButton(
                        text = stringResource(R.string.cook_dismiss),
                        onClick = { onAction(CookAction.DismissExactAlarmPrompt) },
                    )
                    TandemTextButton(text = stringResource(R.string.cook_alarm_allow), onClick = onOpenExactAlarmSettings)
                },
            )
        } else {
            IconLabel(
                icon = TandemIcons.Warning,
                text = stringResource(R.string.cook_alarm_late_title),
                iconTint = TandemTheme.extendedColors.warning,
            )
        }
    }
}

private const val SIDE_PANE_WEIGHT = 0.38f
private const val STEP_PANE_WEIGHT = 0.62f

/** About two timers at the bottom of a phone; more scroll inside the bar. */
private val PHONE_TIMER_BAR_HEIGHT = 168.dp
private val TABLETOP_TIMER_BAR_HEIGHT = 240.dp

/** Test tags. */
internal const val COOK_PAGER_TAG = "cook:pager"
internal const val COOK_SIDE_PANE_TAG = "cook:side-pane"
internal const val COOK_TIMER_BAR_TAG = "cook:timer-bar"
internal const val COOK_TABLETOP_CONTROLS_TAG = "cook:tabletop-controls"
