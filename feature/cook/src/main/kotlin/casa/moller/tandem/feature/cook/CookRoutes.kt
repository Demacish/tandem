package casa.moller.tandem.feature.cook

import android.Manifest
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import casa.moller.tandem.core.designsystem.theme.TandemDarkTheme
import casa.moller.tandem.core.domain.voice.VoiceCommand
import casa.moller.tandem.core.model.notification.TandemNotificationChannel
import casa.moller.tandem.core.model.timer.CookTimer
import casa.moller.tandem.core.ui.accessibility.announce
import casa.moller.tandem.core.ui.recipe.currentLocale
import casa.moller.tandem.feature.cook.handsfree.HandsfreeAction
import casa.moller.tandem.feature.cook.handsfree.HandsfreeEffect
import casa.moller.tandem.feature.cook.handsfree.HandsfreeViewModel
import casa.moller.tandem.feature.cook.navigation.CookKey
import casa.moller.tandem.feature.cook.navigation.CookPageRequest
import casa.moller.tandem.feature.cook.pantry.CookPantryRoute
import casa.moller.tandem.feature.cook.session.CookRecipePickerSheet
import casa.moller.tandem.feature.cook.session.CookSessionAction
import casa.moller.tandem.feature.cook.session.CookSessionUi
import casa.moller.tandem.feature.cook.session.CookSessionViewModel
import casa.moller.tandem.feature.cook.session.tabDescription
import casa.moller.tandem.feature.cook.timers.TimersScreen
import casa.moller.tandem.feature.cook.timers.TimersViewModel
import casa.moller.tandem.feature.cook.ui.CookScreen
import casa.moller.tandem.feature.cook.ui.DarkSystemBars
import casa.moller.tandem.feature.cook.ui.stepPlainText
import kotlinx.coroutines.launch
/**
 * Cook mode for [key] (plan 6.2 "Kokläge", features #6–#8). Full screen on every width
 * ([CookKey] hides the navigation); keeps the screen on, can be forced dark ("Kokläge alltid
 * mörkt"), asks for notifications at the first timer and leaves after "Klart!" was saved.
 *
 * Fas 4 (two recipes at once, plan 8.1, Kitshn #433): "Laga ett recept till" opens a second
 * recipe beside the first ([CookSessionViewModel]); each recipe has its own [CookViewModel] in this
 * entry (own page, servings, "Klart!" draft, all in SavedStateHandle), the tabs switch between
 * them and the timers are shared. With two recipes, ✕, "Stäng utan att spara" and a saved "Klart!"
 * close that recipe only; system back leaves cook mode.
 *
 * Navigation contract (docs/BYGGE.md "Navigering"): the shell pushes [CookKey] from the recipe
 * detail's and Today's "Laga" (and from a timer notification) and shows this route.
 *
 * @param onClose leaves cook mode (✕ and "Klart!" of the only recipe; system back is the shell's).
 * @param pageRequest a page this open cook mode should show now, for one of its recipes (a tapped
 *   notification of one of its timers); null for none. Cook mode selects that recipe's tab, keeps
 *   its servings and "Klart!" draft and turns to the page, then calls [onPageRequestHandled].
 * @param onRecipesChanged Fas 4: the recipes open in this cook mode, whenever they change, so the
 *   shell finds this cook mode for a timer of either recipe (`ShellNavigationState.openCook`).
 * @param shoppingAction Phase 2 (koppling lane, #12): "Lägg på inköpslistan" on the last page,
 *   given the recipe, cook mode's servings, its tin (Fas 5, plan §8.1 "Pannstorlek": null without
 *   a tin; the list gets `servings × factor` like Tandoor's web) and its snackbar
 *   (feature:shopping's route, passed in by the shell).
 */
@Composable
fun CookRoute(
    key: CookKey,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    pageRequest: CookPageRequest? = null,
    onPageRequestHandled: () -> Unit = {},
    onRecipesChanged: (Set<Long>) -> Unit = {},
    shoppingAction: (@Composable (recipeId: Long, servings: Double?, panSize: Int?, snackbar: SnackbarHostState) -> Unit)? = null,
) {
    val sessionViewModel = hiltViewModel<CookSessionViewModel, CookSessionViewModel.Factory>(
        key = "cook-session",
        creationCallback = { factory -> factory.create(key) },
    )
    val session by sessionViewModel.session.collectAsStateWithLifecycle()
    val picker by sessionViewModel.picker.collectAsStateWithLifecycle()
    val closedRecipeId by sessionViewModel.closedRecipeId.collectAsStateWithLifecycle()
    // One cook mode per open recipe, all in this entry's ViewModelStore (both survive tab switches).
    val viewModels = session.recipes.associate { recipe ->
        recipe.recipeId to key(recipe.recipeId) {
            hiltViewModel<CookViewModel, CookViewModel.Factory>(
                key = recipe.viewModelKey,
                creationCallback = { factory -> factory.create(recipe.toCookKey()) },
            )
        }
    }
    // key: without it, closing the first tab hands its slot to the other recipe, whose state then
    // is the closed recipe's for one frame: the pager opened on that page and moved the recipe there.
    val states = viewModels.mapValues { (recipeId, viewModel) -> key(recipeId) { viewModel.uiState.collectAsStateWithLifecycle().value } }
    val activeId = session.activeRecipeId
    val viewModel = viewModels.getValue(activeId)
    val state = states.getValue(activeId)
    val sessionUi = CookSessionUi.of(session, states, closedRecipeId)
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val resources = LocalResources.current
    val currentOnClose by rememberUpdatedState(onClose)
    val view = LocalView.current
    val currentOnPageRequestHandled by rememberUpdatedState(onPageRequestHandled)
    val currentOnRecipesChanged by rememberUpdatedState(onRecipesChanged)
    // ✕ / "Stäng utan att spara" / a saved "Klart!" of [recipeId]: with two recipes only that one closes.
    val closeRecipe: (Long) -> Unit = { recipeId ->
        if (session.isTwo) sessionViewModel.onAction(CookSessionAction.CloseRecipe(recipeId)) else currentOnClose()
    }
    val currentCloseRecipe by rememberUpdatedState(closeRecipe)
    val scope = rememberCoroutineScope()
    // ✕ with two recipes: the closed one's step, servings and draft stay in its ViewModel, so "Ångra"
    // brings them back. "Stäng utan att spara" and a saved "Klart!" are deliberate and go for good.
    val closeTab: (Long) -> Unit = { recipeId ->
        val name = states[recipeId]?.recipeName.orEmpty()
        sessionViewModel.onAction(CookSessionAction.CloseRecipe(recipeId, undoable = true))
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message = resources.getString(R.string.cook_recipe_closed, name),
                actionLabel = resources.getString(R.string.cook_undo),
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) sessionViewModel.onAction(CookSessionAction.RestoreClosed)
        }
    }
    val ids = session.recipeIds.toSet()
    LaunchedEffect(ids) {
        currentOnRecipesChanged(ids)
        viewModels.values.forEach { it.onAction(CookAction.SetOtherRecipes(ids)) }
    }
    LaunchedEffect(pageRequest, ids) {
        val request = pageRequest ?: return@LaunchedEffect
        if (request.recipeId !in ids) return@LaunchedEffect
        sessionViewModel.onAction(CookSessionAction.SelectRecipe(request.recipeId))
        viewModels[request.recipeId]?.onAction(CookAction.GoToPage(request.step))
        currentOnPageRequestHandled()
    }
    // The recipe shown changed (a tab, ✕, a recipe added): TalkBack hears which one and where it is.
    // The header's live region is another recipe's now, not a change of its text.
    val shownTab = sessionUi.tabs.firstOrNull { it.selected }
    val shownDescription = shownTab?.takeIf { it.loaded || it.failed }?.let { tabDescription(it) }
    var announcedRecipe by rememberSaveable { mutableLongStateOf(activeId) }
    LaunchedEffect(activeId, shownDescription) {
        if (activeId != announcedRecipe && shownDescription != null) {
            announcedRecipe = activeId
            view.announce(shownDescription)
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.onAction(CookAction.NotificationPermissionResult(granted))
    }
    viewModels.forEach { (recipeId, recipeViewModel) ->
        key(recipeId) {
            // No visible message (the timer bar shows the new timer); only TalkBack says it.
            LaunchedEffect(recipeViewModel, view) {
                recipeViewModel.announcements.collect { view.announce(resources.getString(it.text, it.argument)) }
            }
            LaunchedEffect(recipeViewModel, snackbarHostState) {
                recipeViewModel.effects.collect { effect ->
                    when (effect) {
                        CookEffect.RequestNotificationPermission -> permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)

                        is CookEffect.Message -> snackbarHostState.showSnackbar(resources.getString(effect.text))

                        is CookEffect.Finished -> {
                            // The screen (or this recipe's tab) goes away right after, so a toast
                            // (announced by TalkBack) instead of a snackbar.
                            Toast.makeText(context, effect.message, Toast.LENGTH_SHORT).show()
                            currentCloseRecipe(recipeId)
                        }
                    }
                }
            }
        }
    }
    // The user may have changed notifications or exact alarms in the system settings meanwhile.
    LifecycleResumeEffect(viewModel) {
        viewModel.onAction(CookAction.RefreshCapabilities)
        onPauseOrDispose { }
    }
    // Fas 5 (plan §8.1 "Handsfree", #32): one handsfree control for the cook mode, its commands go
    // to the recipe shown; it listens only while cook mode is resumed.
    val handsfreeViewModel = hiltViewModel<HandsfreeViewModel>(key = "cook-handsfree")
    val handsfree by handsfreeViewModel.uiState.collectAsStateWithLifecycle()
    HandsfreeEffects(handsfreeViewModel = handsfreeViewModel, shownViewModel = viewModel, shown = state)
    // Fas 5 (plan §8.1 "Pannstorlek"): "Lägg på inköpslistan" goes on the list in the tin shown.
    val panSize = state.pan?.current
    CookTheme(alwaysDark = state.alwaysDark) {
        // The pager and the dialogs follow the shown recipe by themselves (CookScreen); the header
        // with its tabs stays, so a tab switch keeps TalkBack's and the keyboard's focus on the tab.
        CookScreen(
            state = state,
            onAction = viewModel::onAction,
            onClose = { closeRecipe(activeId) },
            onCloseTab = if (session.isTwo) ({ closeTab(activeId) }) else null,
            modifier = modifier,
            snackbarHostState = snackbarHostState,
            onOpenNotificationSettings = { context.openSettings(notificationSettingsIntent(context)) },
            onOpenAlarmChannelSettings = { context.openSettings(alarmChannelSettingsIntent(context)) },
            onOpenExactAlarmSettings = { context.openSettings(exactAlarmSettingsIntent(context)) },
            shoppingActions = shoppingAction?.let { action -> { action(activeId, state.servings?.current, panSize, snackbarHostState) } },
            // Fas S (hemma-ui lane, plan §8.8): "Tog något slut?" (hidden for a guest and without pantry items).
            pantryActions = { key(activeId) { CookPantryRoute(recipeId = activeId, snackbarHostState = snackbarHostState) } },
            session = sessionUi,
            onSessionAction = { action ->
                sessionViewModel.onAction(action)
                if (action is CookSessionAction.OpenTimer) viewModels[action.recipeId]?.onAction(CookAction.GoToPage(action.step))
            },
            handsfree = handsfree,
            onHandsfreeAction = handsfreeViewModel::onAction,
        )
        CookRecipePickerSheet(state = picker, onAction = sessionViewModel::onAction, offline = state.offline)
    }
}

/**
 * Fas 5 (plan §8.1 "Handsfree", #32): what ties the handsfree control to the screen. Visible while
 * resumed (the microphone closes on pause, screen off and when cook mode is left), the UI's
 * language, the page shown (read aloud when chosen), the voice's "nästa"/"tillbaka" for the recipe
 * shown, and the microphone asked for in context.
 */
@Composable
private fun HandsfreeEffects(handsfreeViewModel: HandsfreeViewModel, shownViewModel: CookViewModel, shown: CookUiState) {
    val context = LocalContext.current
    val currentShown by rememberUpdatedState(shownViewModel)
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        handsfreeViewModel.onAction(HandsfreeAction.PermissionResult(granted))
    }
    LifecycleResumeEffect(handsfreeViewModel) {
        handsfreeViewModel.onAction(HandsfreeAction.SetVisible(true))
        onPauseOrDispose { handsfreeViewModel.onAction(HandsfreeAction.SetVisible(false)) }
    }
    val locale = currentLocale()
    LaunchedEffect(handsfreeViewModel, locale) { handsfreeViewModel.onAction(HandsfreeAction.SetLanguage(locale.toLanguageTag())) }
    val speech = cookSpeech(shown)
    LaunchedEffect(handsfreeViewModel, shown.recipeId, shown.page, speech) {
        if (speech.isNotEmpty()) handsfreeViewModel.onAction(HandsfreeAction.StepShown("${shown.recipeId}:${shown.page}", speech))
    }
    LaunchedEffect(handsfreeViewModel) {
        handsfreeViewModel.effects.collect { effect ->
            when (effect) {
                is HandsfreeEffect.Command -> when (effect.command) {
                    VoiceCommand.NEXT -> currentShown.onAction(CookAction.Next)
                    VoiceCommand.PREVIOUS -> currentShown.onAction(CookAction.Previous)
                    VoiceCommand.REPEAT -> Unit
                }

                HandsfreeEffect.RequestMicrophonePermission -> microphone.launch(Manifest.permission.RECORD_AUDIO)

                HandsfreeEffect.OpenAppSettings -> context.openSettings(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()),
                )
            }
        }
    }
}

/** What reading the page aloud says: "Steg 2 av 6. Koka pastan. Koka pastan i 4 liter vatten …". */
@Composable
private fun cookSpeech(state: CookUiState): String {
    if (!state.hasDetail) return ""
    if (state.isDonePage) return stringResource(R.string.cook_handsfree_speech_done)
    val step = state.currentStep ?: return ""
    val position = stringResource(R.string.cook_step_of, state.page + 1, state.steps.size)
    val text = stepPlainText(step.instruction).replace('\n', ' ')
    return listOf(position, step.name, text).map { it.trim().trimEnd('.') }.filter { it.isNotEmpty() }.joinToString(". ")
}

/**
 * All timers of the device (plan 8.1 "Löpande timers syns i alla skärmar"), from cook mode or a
 * notification of a timer without a recipe.
 *
 * @param onOpenCook opens cook mode at the timer's recipe and step.
 * @param activeSource the app's source now (core:model `TimerSource`); only timers of it open cook mode.
 */
@Composable
fun TimersRoute(onBack: () -> Unit, onOpenCook: (CookTimer) -> Unit, activeSource: String?, modifier: Modifier = Modifier) {
    val viewModel: TimersViewModel = hiltViewModel()
    val timers by viewModel.timers.collectAsStateWithLifecycle()
    TimersScreen(
        timers = timers,
        onAction = viewModel::onAction,
        onBack = onBack,
        onOpenCook = onOpenCook,
        activeSource = activeSource,
        modifier = modifier,
    )
}

/**
 * "Kokläge alltid mörkt": a dark theme (and light system bar icons) around cook mode only. One
 * call site for [content] whatever the setting, so turning it on (or the settings arriving) never
 * throws cook mode's state away; the surrounding theme's dark/light and dynamic colour choice
 * stays otherwise (core:designsystem `TandemDarkTheme`).
 */
@Composable
private fun CookTheme(alwaysDark: Boolean, content: @Composable () -> Unit) {
    DarkSystemBars(enabled = alwaysDark)
    TandemDarkTheme(enabled = alwaysDark, content = content)
}

private fun notificationSettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

/** The "Timers (alarm)" channel's own page (its sound was switched off). */
private fun alarmChannelSettingsIntent(context: Context): Intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    .putExtra(Settings.EXTRA_CHANNEL_ID, TandemNotificationChannel.TIMER_ALARM.id)

/** Play build: the system page where the user allows exact alarms for Tandem (plan 4.4). */
private fun exactAlarmSettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${context.packageName}".toUri())

/** Opens a system settings page; a device without it falls back to the app's details page. */
private fun Context.openSettings(intent: Intent) {
    val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri())
    runCatching { startActivity(intent) }.recoverCatching { startActivity(fallback) }
}
