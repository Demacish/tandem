package casa.moller.tandem.feature.recipes.recipeimport

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import casa.moller.tandem.core.ui.accessibility.announce
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
 * ([ImportFiles]) before they go to the server. A photo is a page that waits for the next ones
 * (plan §8.4), as are several images shared at once ([RecipeImportKey.sharedPageUris]).
 *
 * TalkBack is told what happened: the form with the focused button is replaced by the work and the
 * result in one frame, so nothing else would say it ([importAnnouncement]).
 *
 * @param onOpenEditor the import is accepted: open the editor with the draft (saved-draft JSON) and the file it was read from.
 * @param onScanText Fas 4 (textigenkänning): "Fota recept", read on the device (feature:scan through the shell); null hides it.
 */
@Composable
fun RecipeImportRoute(
    key: RecipeImportKey,
    onOpenEditor: (importedDraft: String, sourcePath: String?) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onScanText: (() -> Unit)? = null,
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
    val addPage: suspend (Uri) -> Unit = { uri ->
        val path = withContext(Dispatchers.IO) { ImportFiles.storePage(appContext, uri) }
        viewModel.onAction(if (path == null) RecipeImportAction.FileUnreadable else RecipeImportAction.AddPage(path))
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val type = ImportFiles.typeOf(appContext, uri, null)
            // A picture is one more page; a PDF is complete as it is.
            if (type?.startsWith("image/") == true) scope.launch { addPage(uri) } else readFile(uri, type)
        }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val uri = pendingCapture
        if (taken && uri != null) {
            scope.launch {
                addPage(Uri.parse(uri))
                DrinkCameraFiles.delete(appContext, uri)
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
    val sharedPages = state.sharedPages
    LaunchedEffect(viewModel, sharedPages) {
        if (sharedPages.isNotEmpty()) {
            viewModel.onAction(RecipeImportAction.SharedFileTaken)
            // In the screen's scope: "taken" empties the list, which restarts this effect.
            scope.launch { sharedPages.forEach { addPage(Uri.parse(it)) } }
        }
    }
    LaunchedEffect(appContext) { withContext(Dispatchers.IO) { ImportFiles.sweep(appContext) } }
    // Going back from the review discards the result. Anything else that ends the page (a process
    // death, the tab selected again) leaves a kept AI answer for the next time the page opens.
    val leave: () -> Unit = {
        viewModel.onAction(RecipeImportAction.Leave)
        onBack()
    }
    BackHandler(enabled = state.result != null, onBack = leave)
    val view = LocalView.current
    val announcement = importAnnouncement(state)
    var announced by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(announcement) {
        if (announcement != announced) {
            announced = announcement
            if (announcement != null) view.announce(announcement)
        }
    }
    LaunchedEffect(viewModel, snackbarHostState) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is RecipeImportEffect.Message -> snackbarHostState.showSnackbar(resources.getString(effect.text))
                is RecipeImportEffect.OpenEditor -> currentOnOpenEditor(effect.importedDraft, effect.sourcePath)
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
        onScanText = onScanText,
        onBack = leave,
        modifier = modifier,
        snackbarHostState = snackbarHostState,
        pageView = { reading, viewModifier ->
            PageReaderWebView(
                url = reading.loadUrl,
                readRequest = reading.readRequest,
                onPage = { html, text -> viewModel.onAction(RecipeImportAction.PageLoaded(html, text)) },
                onFailed = { viewModel.onAction(RecipeImportAction.PageUnavailable) },
                modifier = viewModifier,
            )
        },
        onSendPages = {
            val pages = state.pages
            scope.launch {
                val file = withContext(Dispatchers.IO) { ImportFiles.join(appContext, pages) }
                viewModel.onAction(if (file == null) RecipeImportAction.FileUnreadable else file)
            }
        },
    )
}

/** What TalkBack is told about the import's progress: the work, a problem, the page reader, the result. */
@Composable
private fun importAnnouncement(state: RecipeImportUiState): String? {
    val reading = state.pageReading
    val problem = state.problem
    val result = state.result
    return when {
        state.working -> stringResource(workingText(state))
        reading?.nothingFound == true -> stringResource(R.string.recipe_import_page_nothing_title)
        reading != null -> stringResource(R.string.recipe_import_page_title)
        result != null -> stringResource(R.string.recipe_import_found_name, result.draft.name)
        problem != null -> listOfNotNull(stringResource(problem), state.problemHint?.let { stringResource(it) }).joinToString(" ")
        else -> null
    }
}

private fun cameraUri(context: Context): Uri =
    FileProvider.getUriForFile(context, context.packageName + DrinkCameraFiles.AUTHORITY_SUFFIX, DrinkCameraFiles.newFile(context))
