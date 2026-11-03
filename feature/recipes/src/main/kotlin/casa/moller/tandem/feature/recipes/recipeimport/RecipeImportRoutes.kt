package casa.moller.tandem.feature.recipes.recipeimport

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import casa.moller.tandem.feature.recipes.R
import casa.moller.tandem.feature.recipes.drinks.DrinkCameraFiles
import casa.moller.tandem.feature.recipes.navigation.RecipeImportKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fas 3 (import lane): "Importera recept" for [key] (docs/BYGGE.md "Fas 3 – import"). A photo
 * comes from the camera (a cache file through the drinks' FileProvider, [DrinkCameraFiles],
 * deleted once read) or the document picker (images and PDFs); a shared file
 * ([RecipeImportKey.sharedFileUri]) is read once. Files are read off the main thread and scaled
 * ([ImportFiles]) before they go to the server.
 *
 * @param onOpenEditor the import is accepted: open the editor with the draft (saved-draft JSON).
 */
@Composable
fun RecipeImportRoute(
    key: RecipeImportKey,
    onOpenEditor: (importedDraft: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = hiltViewModel<RecipeImportViewModel, RecipeImportViewModel.Factory>(
        key = "recipe-import-${key.hashCode()}",
        creationCallback = { factory -> factory.create(key) },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val resources = LocalResources.current
    val context = LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()
    var pendingCapture by rememberSaveable { mutableStateOf<String?>(null) }
    val currentOnOpenEditor by rememberUpdatedState(onOpenEditor)
    val showMessage: (Int) -> Unit = { text -> scope.launch { snackbarHostState.showSnackbar(resources.getString(text)) } }

    val readFile: (Uri, String?) -> Unit = { uri, mimeType ->
        scope.launch {
            val file = withContext(Dispatchers.IO) { ImportFiles.read(appContext, uri, mimeType) }
            viewModel.onAction(if (file == null) RecipeImportAction.FileUnreadable else file)
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) readFile(uri, appContext.contentResolver.getType(uri))
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val uri = pendingCapture
        if (taken && uri != null) {
            scope.launch {
                val file = withContext(Dispatchers.IO) { ImportFiles.read(appContext, Uri.parse(uri), ImportFiles.JPEG) }
                DrinkCameraFiles.delete(appContext, uri)
                viewModel.onAction(if (file == null) RecipeImportAction.FileUnreadable else file)
            }
        } else {
            DrinkCameraFiles.delete(appContext, uri)
        }
        pendingCapture = null
    }
    val launchCamera: () -> Unit = {
        val uri = cameraUri(context)
        pendingCapture = uri.toString()
        try {
            camera.launch(uri)
        } catch (e: ActivityNotFoundException) {
            DrinkCameraFiles.delete(context, pendingCapture)
            pendingCapture = null
            showMessage(R.string.recipe_editor_camera_denied)
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera() else showMessage(R.string.recipe_editor_camera_denied)
    }
    // A file another app shared: read once (the grant lasts this activity; after process death the read fails and says so).
    val shared = state.sharedFile
    LaunchedEffect(viewModel, shared) {
        if (shared != null) {
            viewModel.onAction(RecipeImportAction.SharedFileTaken)
            readFile(Uri.parse(shared.uri), shared.mimeType)
        }
    }
    LaunchedEffect(viewModel, snackbarHostState) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is RecipeImportEffect.Message -> snackbarHostState.showSnackbar(resources.getString(effect.text))
                is RecipeImportEffect.OpenEditor -> currentOnOpenEditor(effect.importedDraft)
            }
        }
    }
    RecipeImportScreen(
        state = state,
        onAction = viewModel::onAction,
        onTakePhoto = {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                launchCamera()
            } else {
                permission.launch(Manifest.permission.CAMERA)
            }
        },
        onPickFile = { picker.launch(arrayOf("image/*", ImportFiles.PDF)) },
        onBack = onBack,
        modifier = modifier,
        snackbarHostState = snackbarHostState,
    )
}

private fun cameraUri(context: Context): Uri =
    FileProvider.getUriForFile(context, context.packageName + DrinkCameraFiles.AUTHORITY_SUFFIX, DrinkCameraFiles.newFile(context))
