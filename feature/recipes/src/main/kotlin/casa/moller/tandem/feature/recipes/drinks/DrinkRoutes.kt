package casa.moller.tandem.feature.recipes.drinks

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import casa.moller.tandem.feature.recipes.R
import casa.moller.tandem.feature.recipes.RecipeDetailRoute
import casa.moller.tandem.feature.recipes.navigation.CreateDrinkKey
import casa.moller.tandem.feature.recipes.navigation.DrinkDestination
import casa.moller.tandem.feature.recipes.navigation.DrinkDetailKey
import casa.moller.tandem.feature.recipes.navigation.DrinksKey
import casa.moller.tandem.feature.recipes.navigation.PairingPickerKey
import java.io.File
import kotlinx.coroutines.launch

// Fas S (drycker lane): the ONE route feature:shell shows for every [DrinkDestination] (#43, plan §8.8).

/**
 * @param onNavigate opens another destination (a drink, the picker, "Ny dryck").
 * @param onOpenRecipe a dish's or a drink's detail.
 * @param onBack null on a page beside a list on wide windows.
 * @param selectedDrinkId the drink open beside "Drycker" on wide windows (highlighted).
 *
 * The shell shows [DrinkDetailKey] with its full recipe detail (cook mode, shopping sheet); the
 * branch here is the same detail without those, for hosts that have none.
 */
@Composable
fun DrinkRoute(
    key: DrinkDestination,
    onNavigate: (NavKey) -> Unit,
    onOpenRecipe: (Long) -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    selectedDrinkId: Long? = null,
) {
    when (key) {
        is DrinksKey -> DrinksRoute(
            initialType = key.type,
            onOpenDrink = { onNavigate(DrinkDetailKey(it)) },
            onCreateDrink = { onNavigate(CreateDrinkKey()) },
            onBack = onBack,
            selectedDrinkId = selectedDrinkId,
            modifier = modifier,
        )

        is DrinkDetailKey -> {
            val uriHandler = LocalUriHandler.current
            RecipeDetailRoute(
                recipeId = key.recipeId,
                onBack = onBack ?: {},
                onOpenRecipe = onOpenRecipe,
                onOpenOnWeb = { uriHandler.openUri(it) },
                modifier = modifier,
                onNavigate = onNavigate,
            )
        }

        is PairingPickerKey -> PairingPickerRoute(
            recipeId = key.recipeId,
            onBack = onBack ?: {},
            onCreateDrink = { onNavigate(CreateDrinkKey(pairWithRecipeId = key.recipeId)) },
            modifier = modifier,
        )

        is CreateDrinkKey -> CreateDrinkRoute(pairWithRecipeId = key.pairWithRecipeId, onDone = onBack ?: {}, modifier = modifier)
    }
}

@Composable
private fun DrinksRoute(
    initialType: String?,
    onOpenDrink: (Long) -> Unit,
    onCreateDrink: () -> Unit,
    onBack: (() -> Unit)?,
    selectedDrinkId: Long?,
    modifier: Modifier,
) {
    val viewModel = hiltViewModel<DrinksViewModel, DrinksViewModel.Factory>(
        key = "drinks-$initialType",
        creationCallback = { factory -> factory.create(initialType) },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val resources = LocalResources.current
    LaunchedEffect(viewModel, snackbarHostState) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is DrinksEffect.Message -> snackbarHostState.showSnackbar(resources.getString(effect.text))
            }
        }
    }
    DrinksScreen(
        state = state,
        onAction = viewModel::onAction,
        onOpenDrink = onOpenDrink,
        onCreateDrink = onCreateDrink,
        onBack = onBack,
        modifier = modifier,
        selectedDrinkId = selectedDrinkId,
        snackbarHostState = snackbarHostState,
    )
}

@Composable
private fun PairingPickerRoute(recipeId: Long, onBack: () -> Unit, onCreateDrink: () -> Unit, modifier: Modifier) {
    val viewModel = hiltViewModel<PairingPickerViewModel, PairingPickerViewModel.Factory>(
        key = "pairing-picker-$recipeId",
        creationCallback = { factory -> factory.create(recipeId) },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val resources = LocalResources.current
    LaunchedEffect(viewModel, snackbarHostState) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is PairingPickerEffect.Message -> snackbarHostState.showSnackbar(resources.getString(effect.text))
            }
        }
    }
    PairingPickerScreen(
        state = state,
        onAction = viewModel::onAction,
        onBack = onBack,
        onCreateDrink = onCreateDrink,
        modifier = modifier,
        snackbarHostState = snackbarHostState,
    )
}

/**
 * "Ny dryck" with the photo launchers: the system photo picker (no permission), and the camera
 * (`TakePicture` into the app's cache through its FileProvider). CAMERA is asked only when "Ta
 * foto" is tapped; denied, a message points to the picker, which keeps working.
 */
@Composable
private fun CreateDrinkRoute(pairWithRecipeId: Long?, onDone: () -> Unit, modifier: Modifier) {
    val viewModel = hiltViewModel<CreateDrinkViewModel, CreateDrinkViewModel.Factory>(
        key = "create-drink-$pairWithRecipeId",
        creationCallback = { factory -> factory.create(pairWithRecipeId) },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val resources = LocalResources.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingCapture by rememberSaveable { mutableStateOf<String?>(null) }
    val currentOnDone by rememberUpdatedState(onDone)
    val showMessage: (Int) -> Unit = { text -> scope.launch { snackbarHostState.showSnackbar(resources.getString(text)) } }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.onAction(CreateDrinkAction.SetPhoto(uri.toString()))
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val uri = pendingCapture
        if (taken && uri != null) viewModel.onAction(CreateDrinkAction.SetPhoto(uri))
        pendingCapture = null
    }
    val launchCamera: () -> Unit = {
        val uri = cameraUri(context)
        pendingCapture = uri.toString()
        try {
            camera.launch(uri)
        } catch (e: ActivityNotFoundException) {
            pendingCapture = null
            showMessage(R.string.drinks_create_camera_denied)
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera() else showMessage(R.string.drinks_create_camera_denied)
    }

    LaunchedEffect(viewModel, snackbarHostState, context) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is CreateDrinkEffect.Message -> snackbarHostState.showSnackbar(resources.getString(effect.text))

                is CreateDrinkEffect.Saved -> {
                    // This page closes with its snackbar host, so the confirmation is a short toast.
                    Toast.makeText(context, resources.getString(effect.text, effect.name), Toast.LENGTH_SHORT).show()
                    currentOnDone()
                }
            }
        }
    }
    CreateDrinkScreen(
        state = state,
        onAction = viewModel::onAction,
        onTakePhoto = {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                launchCamera()
            } else {
                permission.launch(Manifest.permission.CAMERA)
            }
        },
        onPickPhoto = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        onBack = onDone,
        modifier = modifier,
        snackbarHostState = snackbarHostState,
    )
}

/** A new file in the app's cache for the camera (`res/xml/drink_photo_paths.xml`); the repository copies and scales it. */
private fun cameraUri(context: Context): Uri {
    val directory = File(context.cacheDir, CAMERA_DIRECTORY).apply { mkdirs() }
    val file = File(directory, "drink-${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(context, context.packageName + PHOTO_AUTHORITY_SUFFIX, file)
}

private const val CAMERA_DIRECTORY = "drink-camera"

/** The FileProvider of feature:recipes' manifest (`${applicationId}.drinkphotos`). */
private const val PHOTO_AUTHORITY_SUFFIX = ".drinkphotos"
