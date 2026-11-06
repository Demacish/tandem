package casa.moller.tandem.feature.recipes.editor

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import casa.moller.tandem.core.model.recipe.StepFile
import casa.moller.tandem.core.ui.accessibility.announce
import casa.moller.tandem.feature.recipes.R
import casa.moller.tandem.feature.recipes.drinks.DrinkCameraFiles
import casa.moller.tandem.feature.recipes.navigation.RecipeEditorKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fas 3 (redigerare lane): the recipe editor for [key] (docs/BYGGE.md "Receptredigeraren"). The
 * photo comes from the camera (a file in the cache through the drinks' FileProvider,
 * [DrinkCameraFiles]: the same originals rule, deleted as soon as the repository has its scaled
 * copy) or the system photo picker; the camera is asked for only when "Ta foto" is tapped.
 *
 * @param onSaved the recipe was saved: [RecipeEditorEffect.Saved] (a new recipe is then opened).
 * @param onBack closes the editor. With unsaved changes the arrow and the system's back ask first
 *   ("Spara / Släng / Fortsätt redigera"): the draft lives only as long as this page.
 */
@Composable
fun RecipeEditorRoute(
    key: RecipeEditorKey,
    onSaved: (recipeId: Long, isNew: Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val recipeId = key.recipeId
    val viewModel = hiltViewModel<RecipeEditorViewModel, RecipeEditorViewModel.Factory>(
        key = "recipe-editor-$recipeId-${key.importedDraft?.hashCode() ?: 0}",
        creationCallback = { factory -> factory.create(recipeId, key.importedDraft, key.sourcePath) },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val resources = LocalResources.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingCapture by rememberSaveable { mutableStateOf<String?>(null) }
    val currentOnSaved by rememberUpdatedState(onSaved)
    val currentOnBack by rememberUpdatedState(onBack)
    val showMessage: (Int) -> Unit = { text -> scope.launch { snackbarHostState.showSnackbar(resources.getString(text)) } }

    // A photo replaced or removed is let go of: its camera original is deleted, a picked one's grant released.
    val photoChanged: (String?) -> Unit = { uri ->
        val old = viewModel.currentPhoto
        if (old != null && old != uri) {
            DrinkCameraFiles.delete(context, old)
            releasePickedPhoto(context, old)
        }
        viewModel.onAction(RecipeEditorAction.SetPhoto(uri))
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            keepPickedPhoto(context, uri)
            photoChanged(uri.toString())
        }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val uri = pendingCapture
        if (taken && uri != null) photoChanged(uri) else DrinkCameraFiles.delete(context, uri)
        pendingCapture = null
    }
    // Fas 4 (#22): the step a file is being chosen for; the page may be gone and back by the time it comes.
    var fileStep by rememberSaveable { mutableStateOf<Int?>(null) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val step = fileStep
        fileStep = null
        if (uri != null && step != null) {
            val picked = pickedFile(context, uri)
            when {
                picked == null -> showMessage(R.string.recipe_editor_file_unreadable)

                picked.size > StepFile.MAX_BYTES -> showMessage(R.string.recipe_editor_file_too_large)

                else -> {
                    keepPickedPhoto(context, uri)
                    val name = StepFile.fittingName(picked.name)
                    viewModel.onAction(RecipeEditorAction.SetStepFile(step, StepFile(name = name, uri = uri.toString())))
                }
            }
        }
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
    LaunchedEffect(viewModel, context) {
        val keep = listOf(viewModel.currentPhoto, pendingCapture)
        withContext(Dispatchers.IO) { DrinkCameraFiles.sweep(context, keep) }
    }
    val release: () -> Unit = {
        viewModel.currentPhoto?.let { releasePickedPhoto(context, it) }
        viewModel.chosenFiles.filter { it.startsWith(ContentResolver.SCHEME_CONTENT) }.forEach { releasePickedPhoto(context, it) }
        DrinkCameraFiles.sweep(context)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera() else showMessage(R.string.recipe_editor_camera_denied)
    }

    val view = LocalView.current
    var askingToLeave by rememberSaveable { mutableStateOf(false) }
    val leave: () -> Unit = {
        release()
        currentOnBack()
    }
    val requestLeave: () -> Unit = { if (state.hasChanges && !state.saving) askingToLeave = true else leave() }
    BackHandler(onBack = requestLeave)
    LaunchedEffect(viewModel, snackbarHostState, context) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is RecipeEditorEffect.Message -> snackbarHostState.showSnackbar(resources.getString(effect.text))

                is RecipeEditorEffect.Announce -> view.announce(resources.getString(effect.text, effect.first, effect.second))

                is RecipeEditorEffect.Removed -> launch {
                    val result = snackbarHostState.showSnackbar(
                        message = resources.getString(effect.text, effect.first, effect.second),
                        actionLabel = resources.getString(R.string.recipe_editor_undo),
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) viewModel.onAction(RecipeEditorAction.UndoRemove)
                }

                is RecipeEditorEffect.Saved -> {
                    // This page closes with its snackbar host, so the confirmation is a short toast.
                    Toast.makeText(context, resources.getString(effect.text, effect.name), Toast.LENGTH_SHORT).show()
                    release()
                    currentOnSaved(effect.recipeId, effect.isNew)
                }
            }
        }
    }
    RecipeEditorScreen(
        state = state,
        onAction = { action -> if (action is RecipeEditorAction.SetPhoto) photoChanged(action.uri) else viewModel.onAction(action) },
        onTakePhoto = {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                launchCamera()
            } else {
                permission.launch(Manifest.permission.CAMERA)
            }
        },
        onPickPhoto = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        onBack = requestLeave,
        modifier = modifier,
        snackbarHostState = snackbarHostState,
        onPickStepFile = { step ->
            fileStep = step
            try {
                filePicker.launch(arrayOf(ANY_FILE))
            } catch (e: ActivityNotFoundException) {
                fileStep = null
                showMessage(R.string.recipe_editor_file_unreadable)
            }
        },
    )
    if (askingToLeave) {
        LeaveEditorDialog(
            onSave = {
                askingToLeave = false
                viewModel.onAction(RecipeEditorAction.Save)
            },
            onDiscard = {
                askingToLeave = false
                leave()
            },
            onKeepEditing = { askingToLeave = false },
        )
    }
}

/** A new file in the app's cache for the camera (`res/xml/drink_photo_paths.xml`); the repository copies and scales it. */
private fun cameraUri(context: Context): Uri =
    FileProvider.getUriForFile(context, context.packageName + DrinkCameraFiles.AUTHORITY_SUFFIX, DrinkCameraFiles.newFile(context))

private const val ANY_FILE = "*/*"

/** A document's name and size as its provider tells them. */
private class PickedFile(val name: String, val size: Long)

/** What [uri] is called and how large it is; null when the provider will not say. A size it does not know counts as 0: the copy checks. */
private fun pickedFile(context: Context, uri: Uri): PickedFile? = try {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val name = cursor.getString(0)?.takeIf { it.isNotBlank() } ?: return@use null
        PickedFile(name = name, size = if (cursor.isNull(1)) 0L else cursor.getLong(1))
    }
} catch (e: SecurityException) {
    null
} catch (e: IllegalArgumentException) {
    null
}

/** Keeps the read grant of a photo-picker image past the process (released by [releasePickedPhoto]). */
private fun keepPickedPhoto(context: Context, uri: Uri) {
    try {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    } catch (ignored: SecurityException) {
        // Not persistable: the photo is still read at save in this process.
    }
}

private fun releasePickedPhoto(context: Context, uri: String) {
    if (DrinkCameraFiles.fileOf(context, uri) != null) return
    try {
        context.contentResolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
    } catch (ignored: SecurityException) {
        // Nothing was kept.
    }
}
