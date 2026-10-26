package casa.moller.tandem.feature.cook.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import casa.moller.tandem.core.designsystem.component.CalloutCard
import casa.moller.tandem.core.designsystem.component.StatusChip
import casa.moller.tandem.core.designsystem.component.TandemButtonSize
import casa.moller.tandem.core.designsystem.component.TandemPrimaryButton
import casa.moller.tandem.core.designsystem.component.TandemSecondaryButton
import casa.moller.tandem.core.designsystem.component.TandemTone
import casa.moller.tandem.core.designsystem.icon.TandemIcons
import casa.moller.tandem.core.designsystem.icon.TextDecrease
import casa.moller.tandem.core.designsystem.icon.TextIncrease
import casa.moller.tandem.core.designsystem.theme.TandemTheme
import casa.moller.tandem.core.domain.format.toNumberStyle
import casa.moller.tandem.core.model.error.TandemError
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
import casa.moller.tandem.feature.cook.SilentAlarmCause
import kotlin.time.Duration.Companion.seconds
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
 * A horizontal fold in window coordinates (pixels, like `WindowAdaptiveInfo.windowPosture`'s
 * hinge bounds): the tabletop layout ends the upper half at [top] and starts the controls at
 * [bottom], so no line of the step lies on the crease (plan 6.5 "Gångjärnet respekteras").
 */
@Immutable
data class FoldBounds(val top: Float, val bottom: Float) {
    init {
        require(bottom >= top) { "bottom is below top" }
    }

    val height: Float
        get() = bottom - top
}

/**
 * Cook mode (plan 6.2 "Kokläge", 6.3 wireframe, Stitch kokl_ge_*), stateless: [state] in,
 * [onAction] out. Full screen on every width; the screen stays on while it is shown.
 *
 * Keyboard (plan 6.5): arrow keys, Page Up/Down and space (Shift+space back) turn the pages.
 *
 * @param onClose leaves cook mode (✕ and "Close without saving").
 * @param onOpenNotificationSettings the app's notification settings (notifications are off).
 * @param onOpenAlarmChannelSettings the "Timers (alarm)" channel's settings (its sound is off).
 * @param onOpenExactAlarmSettings the system page that allows exact alarms (play build).
 * @param layout forces a layout (tests, previews); null decides from the window.
 * @param fold forces the fold of the tabletop layout (tests); null reads the window's horizontal
 *   hinge, and without one the halves split the screen evenly.
 * @param shoppingActions Phase 2 (koppling lane, #12): "Lägg på inköpslistan" on the "Klart!" page.
 */
@Composable
fun CookScreen(
    state: CookUiState,
    onAction: (CookAction) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    onOpenNotificationSettings: () -> Unit = {},
    onOpenAlarmChannelSettings: () -> Unit = {},
    onOpenExactAlarmSettings: () -> Unit = {},
    layout: CookLayout? = null,
    fold: FoldBounds? = null,
    shoppingActions: (@Composable () -> Unit)? = null,
) {
    val locale = currentLocale()
    val currentOnAction by rememberUpdatedState(onAction)
    LaunchedEffect(locale) { currentOnAction(CookAction.SetLocale(locale.toNumberStyle(), locale.toLanguageTag())) }
    KeepScreenOn()
    val posture = currentWindowAdaptiveInfoV2().windowPosture
    val tabletop = posture.isTabletop
    val windowFold = posture.hingeList.firstOrNull { !it.isVertical }?.bounds?.let { FoldBounds(it.top, it.bottom) }
    val warningActions = WarningActions(
        onOpenNotificationSettings = onOpenNotificationSettings,
        onOpenAlarmChannelSettings = onOpenAlarmChannelSettings,
        onOpenExactAlarmSettings = onOpenExactAlarmSettings,
    )
    // "Justera tiden": the step and suggestion whose timer is being set up, and the chosen
    // seconds (kept across rotation). A tapped timer asks here before it starts.
    var adjustStepIndex by rememberSaveable { mutableIntStateOf(NO_ADJUST) }
    var adjustSuggestion by rememberSaveable { mutableIntStateOf(0) }
    var adjustSeconds by rememberSaveable { mutableLongStateOf(0L) }
    val stepAction: (CookAction) -> Unit = { action ->
        val index = (action as? CookAction.StartTimer)?.takeIf { it.duration == null }
            ?.let { start -> state.steps.getOrNull(start.stepIndex)?.suggestions?.indexOf(start.suggestion) }
        if (action is CookAction.StartTimer && index != null && index >= 0) {
            adjustStepIndex = action.stepIndex
            adjustSuggestion = index
            adjustSeconds = action.suggestion.duration.inWholeSeconds
        } else {
            onAction(action)
        }
    }
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
                    onAction = stepAction,
                    onClose = onClose,
                    layout = chosen,
                    largeText = maxWidth >= LargeStepTextMinWidth,
                    availableHeight = maxHeight,
                    fold = fold ?: windowFold,
                    warningActions = warningActions,
                    shoppingActions = shoppingActions,
                )

                else -> Column(Modifier.fillMaxSize()) {
                    CookHeader(state = state, onAction = onAction, onClose = onClose, showScreenOn = false, progress = null)
                    MissingRecipe(state = state, onAction = onAction, onClose = onClose)
                }
            }
        }
    }
    state.steps.getOrNull(adjustStepIndex)?.suggestions?.getOrNull(adjustSuggestion)?.let { suggestion ->
        TimerAdjustDialog(
            suggestion = suggestion,
            duration = adjustSeconds.seconds,
            onChange = { adjustSeconds = it.inWholeSeconds },
            onStart = { text ->
                onAction(CookAction.StartTimer(adjustStepIndex, suggestion, adjustSeconds.seconds, text))
                adjustStepIndex = NO_ADJUST
            },
            onDismiss = { adjustStepIndex = NO_ADJUST },
        )
    }
    state.notificationPrompt?.let {
        NotificationPromptDialog(
            onContinue = { onAction(CookAction.AcceptNotificationPrompt) },
            onNotNow = { onAction(CookAction.DeclineNotificationPrompt) },
        )
    }
}

/** Where the warnings' buttons lead (system settings pages, opened by the route). */
@Immutable
internal class WarningActions(
    val onOpenNotificationSettings: () -> Unit = {},
    val onOpenAlarmChannelSettings: () -> Unit = {},
    val onOpenExactAlarmSettings: () -> Unit = {},
)

/**
 * No stored recipe yet: loading, or why it cannot be shown. Offline (or no network) asks for a
 * connection; a recipe deleted in Tandoor says so without a retry that could never succeed; a
 * refused login points to "Logga in igen" (the shell's banner, after closing cook mode); anything
 * else is the app's usual error text with "Try again" (as the 0.1.0 recipe detail).
 */
@Composable
private fun MissingRecipe(state: CookUiState, onAction: (CookAction) -> Unit, onClose: () -> Unit) {
    val error = state.error
    val retry = { onAction(CookAction.Retry) }
    val close: @Composable ColumnScope.() -> Unit = {
        // Not "Close cook mode" again: the header's ✕ already says that (one speakable text per action).
        TandemSecondaryButton(text = stringResource(R.string.cook_go_back), onClick = onClose, size = TandemButtonSize.Kitchen)
    }
    when {
        state.loading || error == null -> LoadingState(modifier = Modifier.fillMaxSize(), message = stringResource(R.string.cook_loading))

        else -> Column(Modifier.verticalScroll(rememberScrollState())) {
            when {
                state.offline || error.isNoConnection -> ErrorState(
                    title = stringResource(R.string.cook_not_downloaded_title),
                    body = stringResource(R.string.cook_not_downloaded_body),
                    icon = TandemIcons.CloudOff,
                    onRetry = retry,
                )

                error == TandemError.NotFound -> ErrorState(
                    title = stringResource(R.string.cook_recipe_gone_title),
                    body = stringResource(R.string.cook_recipe_gone_body),
                    icon = TandemIcons.Info,
                    secondaryAction = close,
                )

                error is TandemError.Unauthorized -> ErrorState(
                    title = stringResource(R.string.cook_recipe_login_title),
                    body = stringResource(R.string.cook_recipe_login_body),
                    icon = TandemIcons.Info,
                    secondaryAction = close,
                )

                else -> ErrorState(error = error, onRetry = retry)
            }
        }
    }
}

/** Errors that mean "no connection": the recipe can be fetched once the device is online. */
private val TandemError.isNoConnection: Boolean
    get() = this == TandemError.NetworkUnavailable || this == TandemError.UnknownHost || this == TandemError.Timeout

@Composable
private fun CookContent(
    state: CookUiState,
    onAction: (CookAction) -> Unit,
    onClose: () -> Unit,
    layout: CookLayout,
    largeText: Boolean,
    availableHeight: Dp,
    fold: FoldBounds?,
    warningActions: WarningActions,
    shoppingActions: (@Composable () -> Unit)? = null,
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
                CookDonePage(state = state, onAction = onAction, onClose = onClose, shoppingActions = shoppingActions)
            }
        }
    }
    val header: @Composable () -> Unit = {
        CookHeader(state = state, onAction = onAction, onClose = onClose, showScreenOn = layout == CookLayout.TWO_PANE, progress = progress)
    }
    val bottomArea: @Composable (Dp) -> Unit = { maxScroll ->
        BottomArea(
            state = state,
            now = now,
            timerActions = timerActions,
            onOpenTimer = openTimer,
            onAction = onAction,
            warningActions = warningActions,
            maxScrollHeight = maxScroll,
            compactHeight = availableHeight < CompactHeight,
        )
    }
    // Where this column starts in the window, to place the tabletop halves around the fold.
    var windowTop by remember { mutableFloatStateOf(Float.NaN) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { windowTop = it.positionInWindow().y }
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
        when (layout) {
            CookLayout.PHONE -> {
                header()
                pages(Modifier.weight(1f), true)
                bottomArea(availableHeight * bottomShare(availableHeight))
            }

            CookLayout.TWO_PANE -> {
                header()
                Row(Modifier.weight(1f)) {
                    CookSidePane(
                        state = state,
                        now = now,
                        timerActions = timerActions,
                        onOpenTimer = openTimer,
                        onAction = onAction,
                        warningActions = warningActions,
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
            }

            CookLayout.TABLETOP -> {
                // Upper half (header and step) ends at the fold, the controls start below it.
                val density = LocalDensity.current
                val split = tabletopSplit(fold = fold, windowTop = windowTop, height = with(density) { availableHeight.toPx() })
                val upper = if (split != null) Modifier.height(with(density) { split.upper.toDp() }) else Modifier.weight(1f)
                Column(upper.testTag(COOK_TABLETOP_UPPER_TAG)) {
                    header()
                    pages(Modifier.weight(1f), true)
                }
                if (split != null) {
                    Spacer(Modifier.height(with(density) { split.gap.toDp() }))
                } else {
                    HorizontalDivider()
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .testTag(COOK_TABLETOP_CONTROLS_TAG),
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    bottomArea(TABLETOP_TIMER_BAR_HEIGHT + TandemTheme.sizes.kitchenTouchTarget)
                }
            }
        }
    }
}

/** The tabletop halves in pixels: the upper part (header and step) and the gap of the fold. */
internal data class TabletopSplit(val upper: Float, val gap: Float)

/**
 * Where the tabletop layout splits a column of [height] px that starts at [windowTop] in the
 * window: the upper part ends at the fold's top, the gap is the fold. Null (split evenly
 * instead) without a fold, before the column was placed, or when the fold lies outside the
 * column or leaves no room on one side.
 */
internal fun tabletopSplit(fold: FoldBounds?, windowTop: Float, height: Float): TabletopSplit? {
    if (fold == null || windowTop.isNaN()) return null
    val upper = fold.top - windowTop
    val gap = fold.height
    return TabletopSplit(upper = upper, gap = gap).takeIf { upper > 0f && upper + gap < height }
}

/** How much of a phone's height the warnings and the timer bar may take: less on short windows. */
private fun bottomShare(height: Dp): Float = if (height < CompactHeight) COMPACT_BOTTOM_SHARE else BOTTOM_SHARE

/** Windows shorter than this (split screen, small phones in landscape) get the compact warnings. */
internal val CompactHeight = 480.dp

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

/**
 * ✕, recipe name and "Steg 3 av 6", A−/A+, then the progress bar. "Steg 3 av 6" is a polite live
 * region that says the step's name too, so TalkBack announces every page turn (the focus stays
 * on "Next").
 */
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
                    val step = state.currentStep
                    val announcement = when {
                        state.isDonePage -> stringResource(R.string.cook_done_page)

                        step != null && step.name.isNotBlank() ->
                            stringResource(R.string.cook_step_of_named, state.page + 1, state.steps.size, step.name)

                        else -> stringResource(R.string.cook_step_of, state.page + 1, state.steps.size)
                    }
                    Text(
                        text = if (state.isDonePage) {
                            stringResource(R.string.cook_done_page)
                        } else {
                            stringResource(R.string.cook_step_of, state.page + 1, state.steps.size)
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .testTag(COOK_STEP_OF_TAG)
                            .semantics {
                                contentDescription = announcement
                                liveRegion = LiveRegionMode.Polite
                            },
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

/**
 * Phone and tabletop: warnings, the timer bar and the page buttons. The warnings and the timer
 * bar share one region of at most [maxScrollHeight] that scrolls inside, so the step above keeps
 * room and the 64 dp page buttons are always measured at full height (split screen, plan 6.5).
 *
 * @param compactHeight a short window: the warnings are one line each ([AlarmWarnings]).
 */
@Composable
private fun BottomArea(
    state: CookUiState,
    now: Instant,
    timerActions: TimerActions,
    onOpenTimer: (CookTimer) -> Unit,
    onAction: (CookAction) -> Unit,
    warningActions: WarningActions,
    maxScrollHeight: Dp,
    compactHeight: Boolean,
) {
    val spacing = TandemTheme.spacing
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.lg, vertical = spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        if (state.timers.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxScrollHeight)
                    .verticalScroll(rememberScrollState())
                    .testTag(COOK_BOTTOM_TIMERS_TAG),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                AlarmWarnings(state = state, onAction = onAction, actions = warningActions, compact = compactHeight)
                TimerBar(
                    timers = state.timers,
                    now = now,
                    actions = timerActions,
                    onOpen = onOpenTimer,
                    maxHeight = PHONE_TIMER_BAR_HEIGHT,
                    modifier = Modifier.testTag(COOK_TIMER_BAR_TAG),
                )
            }
        }
        PageButtons(state = state, onAction = onAction)
    }
}

/**
 * "Föregående" and "Nästa" (64 dp, plan 6.6); on the last step "Klart!". With a large font
 * (scale 1.5 and up) on a narrow pane they are stacked, full width and without icons, so
 * "Föregående" never breaks inside the word.
 */
@Composable
internal fun PageButtons(state: CookUiState, onAction: (CookAction) -> Unit, modifier: Modifier = Modifier) {
    val spacing = TandemTheme.spacing
    val largeFont = LocalDensity.current.fontScale >= LARGE_FONT_SCALE
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val stacked = largeFont && maxWidth < StackedButtonsMaxWidth
        val previous: @Composable (Modifier) -> Unit = { buttonModifier ->
            TandemSecondaryButton(
                text = stringResource(R.string.cook_previous),
                onClick = { onAction(CookAction.Previous) },
                enabled = state.page > 0,
                leadingIcon = TandemIcons.ArrowBack.takeUnless { stacked },
                size = TandemButtonSize.Kitchen,
                modifier = buttonModifier,
            )
        }
        val next: @Composable (Modifier) -> Unit = { buttonModifier ->
            TandemPrimaryButton(
                text = stringResource(if (state.page == state.donePage - 1) R.string.cook_next_done else R.string.cook_next),
                onClick = { onAction(CookAction.Next) },
                trailingIcon = TandemIcons.ArrowForward.takeUnless { stacked },
                size = TandemButtonSize.Kitchen,
                modifier = buttonModifier,
            )
        }
        if (stacked) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                if (!state.isDonePage) next(Modifier.fillMaxWidth())
                previous(Modifier.fillMaxWidth())
            }
        } else {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
                previous(Modifier.weight(1f))
                if (!state.isDonePage) {
                    next(Modifier.weight(1f))
                } else {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * The alarm may be silent or hard to stop (notifications off, or the alarm channel off) or late
 * (play build without exact alarms): said while a timer runs, with a way to the right system
 * setting (plan 8.1/8.7, 4.4). Never the raw permission names (docs/design/README.md). Every
 * button is 64 dp (cook mode, plan 6.6).
 *
 * @param compact a short window: one line per warning with its main button (the late alarm's
 *   "Not now" is left out; the warning still turns into one line after "Not now" elsewhere).
 */
@Composable
internal fun AlarmWarnings(state: CookUiState, onAction: (CookAction) -> Unit, actions: WarningActions, compact: Boolean = false) {
    when (state.silentAlarmCause) {
        SilentAlarmCause.NOTIFICATIONS_OFF -> Warning(
            title = stringResource(R.string.cook_alarm_silent_title),
            body = stringResource(R.string.cook_alarm_silent_body),
            action = stringResource(R.string.cook_open_settings),
            onAction = actions.onOpenNotificationSettings,
            compact = compact,
        )

        SilentAlarmCause.ALARM_CHANNEL_OFF -> Warning(
            title = stringResource(R.string.cook_alarm_channel_off_title),
            body = stringResource(R.string.cook_alarm_channel_off_body),
            action = stringResource(R.string.cook_open_settings),
            onAction = actions.onOpenAlarmChannelSettings,
            compact = compact,
        )

        null -> if (state.showLateAlarmWarning) {
            val canAsk = state.capabilities.exactAlarmsRequestable && !state.exactAlarmPromptDismissed
            if (canAsk) {
                Warning(
                    title = stringResource(R.string.cook_alarm_late_title),
                    body = stringResource(R.string.cook_alarm_late_body),
                    action = stringResource(R.string.cook_alarm_allow),
                    onAction = actions.onOpenExactAlarmSettings,
                    compact = compact,
                    dismiss = stringResource(R.string.cook_dismiss),
                    onDismiss = { onAction(CookAction.DismissExactAlarmPrompt) },
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
}

/** One warning: a card with its text and buttons, or one line with the main button ([compact]). */
@Composable
private fun Warning(
    title: String,
    body: String,
    action: String,
    onAction: () -> Unit,
    compact: Boolean,
    dismiss: String? = null,
    onDismiss: () -> Unit = {},
) {
    if (compact) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TandemTheme.spacing.sm),
        ) {
            IconLabel(
                icon = TandemIcons.Warning,
                text = title,
                iconTint = TandemTheme.extendedColors.warning,
                modifier = Modifier.weight(1f),
            )
            TandemPrimaryButton(text = action, onClick = onAction, size = TandemButtonSize.Kitchen)
        }
    } else {
        CalloutCard(
            title = title,
            body = body,
            icon = TandemIcons.Warning,
            tone = TandemTone.Warning,
            actions = {
                // Filled and tonal: a text button's colour is too faint on the warning container.
                TandemPrimaryButton(text = action, onClick = onAction, size = TandemButtonSize.Kitchen)
                if (dismiss != null) {
                    TandemSecondaryButton(text = dismiss, onClick = onDismiss, size = TandemButtonSize.Kitchen)
                }
            },
        )
    }
}

private const val SIDE_PANE_WEIGHT = 0.38f
private const val STEP_PANE_WEIGHT = 0.62f

/** About two timers at the bottom of a phone; more scroll inside the bar. */
private val PHONE_TIMER_BAR_HEIGHT = 168.dp
private val TABLETOP_TIMER_BAR_HEIGHT = 240.dp

/** The warnings and the timer bar take at most this share of a phone's height (less when it is short). */
private const val BOTTOM_SHARE = 0.4f
private const val COMPACT_BOTTOM_SHARE = 0.33f

/** From this font scale the page buttons may be stacked. */
private const val LARGE_FONT_SCALE = 1.5f

/** Panes narrower than this stack the page buttons at a large font scale. */
private val StackedButtonsMaxWidth = 480.dp

/** Test tags. */
internal const val COOK_PAGER_TAG = "cook:pager"
internal const val COOK_SIDE_PANE_TAG = "cook:side-pane"
internal const val COOK_TIMER_BAR_TAG = "cook:timer-bar"
internal const val COOK_TABLETOP_CONTROLS_TAG = "cook:tabletop-controls"
internal const val COOK_TABLETOP_UPPER_TAG = "cook:tabletop-upper"
internal const val COOK_BOTTOM_TIMERS_TAG = "cook:bottom-timers"
internal const val COOK_STEP_OF_TAG = "cook:step-of"

private const val NO_ADJUST = -1
