package casa.moller.tandem.feature.pantry

import android.content.res.Resources
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringArrayResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import casa.moller.tandem.core.ui.pantry.foodTitle
import casa.moller.tandem.core.ui.pantry.formatPantryAmount
import casa.moller.tandem.feature.pantry.entry.PantryEntryAction
import casa.moller.tandem.feature.pantry.entry.PantryEntryEffect
import casa.moller.tandem.feature.pantry.entry.PantryEntryScreen
import casa.moller.tandem.feature.pantry.entry.PantryEntryViewModel
import casa.moller.tandem.feature.pantry.hemma.PantryAction
import casa.moller.tandem.feature.pantry.hemma.PantryEffect
import casa.moller.tandem.feature.pantry.hemma.PantryMessage
import casa.moller.tandem.feature.pantry.hemma.PantryScreen
import casa.moller.tandem.feature.pantry.hemma.PantryViewModel
import casa.moller.tandem.feature.pantry.makenow.MakeNowAction
import casa.moller.tandem.feature.pantry.makenow.MakeNowEffect
import casa.moller.tandem.feature.pantry.makenow.MakeNowScreen
import casa.moller.tandem.feature.pantry.makenow.MakeNowViewModel
import casa.moller.tandem.feature.pantry.navigation.MakeNowKey
import casa.moller.tandem.feature.pantry.navigation.PantryDestination
import casa.moller.tandem.feature.pantry.navigation.PantryEntryKey
import casa.moller.tandem.feature.pantry.navigation.PantryKey
import casa.moller.tandem.feature.pantry.navigation.PantryLocationKey
import casa.moller.tandem.feature.pantry.navigation.PantryScanner
import casa.moller.tandem.feature.pantry.navigation.StaplesKey
import casa.moller.tandem.feature.pantry.navigation.UnpackKey
import casa.moller.tandem.feature.pantry.staples.StaplesAction
import casa.moller.tandem.feature.pantry.staples.StaplesEffect
import casa.moller.tandem.feature.pantry.staples.StaplesScreen
import casa.moller.tandem.feature.pantry.staples.StaplesViewModel
import casa.moller.tandem.feature.pantry.unpack.UnpackAction
import casa.moller.tandem.feature.pantry.unpack.UnpackEffect
import casa.moller.tandem.feature.pantry.unpack.UnpackScreen
import casa.moller.tandem.feature.pantry.unpack.UnpackViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// Fas S (hemma-ui lane): the ONE route feature:shell shows for every [PantryDestination]. The
// callbacks are the shell's navigation, so this module never knows other features: recipes, cook
// mode, the scanners (feature:scan) and the shopping sheet (feature:shopping) come in as callbacks
// and a slot.

/**
 * feature:shopping's "Lägg på inköpslistan" sheet for a recipe with exactly [missingFoodIds]
 * ticked ("Lägg det som saknas på listan", plan §8.8). The shell passes
 * `RecipeMissingShoppingSheet`; [request] changes every time the user asks, so the same recipe can
 * be asked for again. Its messages go to [snackbarHostState] (the screen's).
 */
typealias MissingToListSheet =
    @Composable (recipeId: Long, missingFoodIds: Set<Long>, request: Int, snackbarHostState: SnackbarHostState) -> Unit

/**
 * @param onNavigate opens another destination of this module.
 * @param onOpenRecipe a recipe's detail (feature:recipes, mapped by the shell).
 * @param onBack the back arrow; null on a tab root (Hemma is opened from Mer, so it has one).
 * @param onStartCooking cook mode for a recipe of "Kan laga nu" (feature:cook).
 * @param onOpenScanner a scanner of feature:scan, for a place when known (barcode, receipt, fridge photo).
 * @param missingToList the shopping sheet with the missing foods ticked; null shows no sheet.
 */
@Composable
fun PantryRoute(
    key: PantryDestination,
    onNavigate: (NavKey) -> Unit,
    onOpenRecipe: (Long) -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onStartCooking: (Long) -> Unit = {},
    onOpenScanner: (PantryScanner, Long?) -> Unit = { _, _ -> },
    missingToList: MissingToListSheet? = null,
) {
    val back = onBack ?: {}
    when (key) {
        PantryKey -> PantryHomeRoute(null, onNavigate, onOpenScanner, onBack, modifier)
        is PantryLocationKey -> PantryHomeRoute(key.locationId, onNavigate, onOpenScanner, onBack, modifier)
        is PantryEntryKey -> PantryEntryRoute(key = key, onDone = back, modifier = modifier)
        UnpackKey -> UnpackRoute(onDone = back, modifier = modifier)
        is MakeNowKey -> MakeNowRoute(
            key = key,
            onNavigate = onNavigate,
            onOpenRecipe = onOpenRecipe,
            onStartCooking = onStartCooking,
            onBack = onBack,
            missingToList = missingToList,
            modifier = modifier,
        )

        StaplesKey -> StaplesRoute(onDone = back, modifier = modifier)
    }
}

@Composable
private fun PantryHomeRoute(
    locationId: Long?,
    onNavigate: (NavKey) -> Unit,
    onOpenScanner: (PantryScanner, Long?) -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier,
) {
    val viewModel = hiltViewModel<PantryViewModel, PantryViewModel.Factory>(
        key = "pantry-$locationId",
        creationCallback = { factory -> factory.create(locationId) },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(viewModel, snackbarHostState, resources) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is PantryEffect.Show -> scope.showMessage(snackbarHostState, resources.text(effect.message), effect.undoable, resources) {
                    viewModel.onAction(PantryAction.Undo)
                }

                is PantryEffect.Failed -> scope.showMessage(snackbarHostState, resources.pantryErrorText(effect.error), false, resources) {}
            }
        }
    }
    PantryScreen(
        state = state,
        onAction = { action ->
            when (action) {
                is PantryAction.OpenEntry -> onNavigate(PantryEntryKey(entryId = action.entryId))
                is PantryAction.AddEntry -> onNavigate(PantryEntryKey(locationId = action.locationId))
                is PantryAction.OpenLocation -> onNavigate(PantryLocationKey(action.locationId))
                is PantryAction.OpenScanner -> onOpenScanner(action.scanner, action.locationId)
                PantryAction.OpenUnpack -> onNavigate(UnpackKey)
                PantryAction.OpenMakeNow -> onNavigate(MakeNowKey())
                PantryAction.OpenStaples -> onNavigate(StaplesKey)
                else -> viewModel.onAction(action)
            }
        },
        modifier = modifier,
        onBack = onBack,
        snackbarHostState = snackbarHostState,
    )
}

@Composable
private fun PantryEntryRoute(key: PantryEntryKey, onDone: () -> Unit, modifier: Modifier) {
    val viewModel = hiltViewModel<PantryEntryViewModel, PantryEntryViewModel.Factory>(
        key = "pantry-entry-${key.entryId}-${key.locationId}",
        creationCallback = { factory -> factory.create(key) },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val resources = LocalResources.current
    val locale = LocalConfiguration.current.locales[0]
    val currentOnDone by rememberUpdatedState(onDone)
    LaunchedEffect(viewModel, locale) { viewModel.onAction(PantryEntryAction.SetLocale(locale)) }
    LaunchedEffect(viewModel, snackbarHostState, resources) {
        viewModel.effects.collect { effect ->
            when (effect) {
                PantryEntryEffect.Done -> currentOnDone()
                is PantryEntryEffect.Failed -> snackbarHostState.showSnackbar(resources.pantryErrorText(effect.error))
            }
        }
    }
    PantryEntryScreen(
        state = state,
        onAction = { action -> if (action == PantryEntryAction.Close) onDone() else viewModel.onAction(action) },
        modifier = modifier,
        snackbarHostState = snackbarHostState,
    )
}

@Composable
private fun UnpackRoute(onDone: () -> Unit, modifier: Modifier) {
    val viewModel: UnpackViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val resources = LocalResources.current
    val currentOnDone by rememberUpdatedState(onDone)
    LaunchedEffect(viewModel, snackbarHostState, resources) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is UnpackEffect.Done -> currentOnDone()
                is UnpackEffect.Failed -> snackbarHostState.showSnackbar(resources.pantryErrorText(effect.error))
            }
        }
    }
    UnpackScreen(
        state = state,
        onAction = { action -> if (action == UnpackAction.Close) onDone() else viewModel.onAction(action) },
        modifier = modifier,
        snackbarHostState = snackbarHostState,
    )
}

@Composable
private fun StaplesRoute(onDone: () -> Unit, modifier: Modifier) {
    val viewModel: StaplesViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val currentOnDone by rememberUpdatedState(onDone)
    val suggestions = stringArrayResource(R.array.pantry_staple_suggestions).toList()
    LaunchedEffect(viewModel, suggestions) { viewModel.onAction(StaplesAction.Suggestions(suggestions)) }
    LaunchedEffect(viewModel, snackbarHostState, resources) {
        viewModel.effects.collect { effect ->
            when (effect) {
                StaplesEffect.Done -> currentOnDone()

                is StaplesEffect.Removed -> scope.showMessage(
                    snackbarHostState,
                    resources.getString(R.string.pantry_staples_removed, effect.name),
                    true,
                    resources,
                ) { viewModel.onAction(StaplesAction.Undo) }

                is StaplesEffect.Failed -> snackbarHostState.showSnackbar(resources.pantryErrorText(effect.error))
            }
        }
    }
    StaplesScreen(state = state, onAction = viewModel::onAction, modifier = modifier, snackbarHostState = snackbarHostState)
}

@Composable
private fun MakeNowRoute(
    key: MakeNowKey,
    onNavigate: (NavKey) -> Unit,
    onOpenRecipe: (Long) -> Unit,
    onStartCooking: (Long) -> Unit,
    onBack: (() -> Unit)?,
    missingToList: MissingToListSheet?,
    modifier: Modifier,
) {
    val viewModel = hiltViewModel<MakeNowViewModel, MakeNowViewModel.Factory>(
        key = "make-now-${key.useUpFirst}",
        creationCallback = { factory -> factory.create(key.useUpFirst) },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val resources = LocalResources.current
    val currentOpenRecipe by rememberUpdatedState(onOpenRecipe)
    // The sheet asked for last: the recipe, its missing foods and a counter, so asking again reopens it.
    var sheetRecipe by rememberSaveable { mutableStateOf<Long?>(null) }
    var sheetFoods by rememberSaveable { mutableStateOf(LongArray(0)) }
    var sheetRequest by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(viewModel, snackbarHostState, resources) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is MakeNowEffect.OpenRecipe -> currentOpenRecipe(effect.recipeId)

                is MakeNowEffect.AddToList -> {
                    sheetRecipe = effect.recipeId
                    sheetFoods = effect.foodIds.toLongArray()
                    sheetRequest += 1
                }

                MakeNowEffect.NothingToSurprise -> snackbarHostState.showSnackbar(
                    resources.getString(R.string.pantry_make_now_surprise_none),
                )

                is MakeNowEffect.Failed -> snackbarHostState.showSnackbar(resources.pantryErrorText(effect.error))
            }
        }
    }
    MakeNowScreen(
        state = state,
        onAction = { action ->
            when (action) {
                is MakeNowAction.OpenRecipe -> onOpenRecipe(action.recipeId)
                is MakeNowAction.StartCooking -> onStartCooking(action.recipeId)
                MakeNowAction.OpenStaples -> onNavigate(StaplesKey)
                else -> viewModel.onAction(action)
            }
        },
        modifier = modifier,
        onBack = onBack,
        snackbarHostState = snackbarHostState,
    )
    val recipe = sheetRecipe
    if (missingToList != null && recipe != null) missingToList(recipe, sheetFoods.toSet(), sheetRequest, snackbarHostState)
}

/** A snackbar, with "Ångra" ([onUndo]) when [undoable]; the newest message replaces the shown one. */
private fun CoroutineScope.showMessage(host: SnackbarHostState, text: String, undoable: Boolean, resources: Resources, onUndo: () -> Unit) {
    launch {
        host.currentSnackbarData?.dismiss()
        val result = host.showSnackbar(
            message = text,
            actionLabel = if (undoable) resources.getString(R.string.pantry_undo) else null,
            duration = if (undoable) SnackbarDuration.Long else SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) onUndo()
    }
}

/** A change on Hemma as one line ("Grädde är slut", "Mjölk: 1 l kvar", "Grädde flyttad till Frys"). */
internal fun Resources.text(message: PantryMessage): String {
    val locale = configuration.locales[0]
    return when (message) {
        is PantryMessage.MarkedEmpty -> getString(R.string.pantry_message_empty, foodTitle(message.name, locale))

        is PantryMessage.Decremented -> if (message.amount <= 0.0) {
            getString(R.string.pantry_message_empty, foodTitle(message.name, locale))
        } else {
            getString(
                R.string.pantry_message_left,
                foodTitle(message.name, locale),
                formatPantryAmount(message.amount, message.unit, locale),
            )
        }

        is PantryMessage.Moved -> getString(R.string.pantry_message_moved, foodTitle(message.name, locale), message.location)
        is PantryMessage.OnHandRemoved -> getString(R.string.pantry_message_on_hand_removed, foodTitle(message.name, locale))
        is PantryMessage.LocationsCreated -> getQuantityString(R.plurals.pantry_message_locations_created, message.count, message.count)
        is PantryMessage.LocationSaved -> getString(R.string.pantry_message_location_saved, message.name)
        is PantryMessage.LocationDeleted -> getString(R.string.pantry_message_location_deleted, message.name)
    }
}
