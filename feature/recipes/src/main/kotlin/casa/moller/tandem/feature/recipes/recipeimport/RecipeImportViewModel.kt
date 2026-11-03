package casa.moller.tandem.feature.recipes.recipeimport

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import casa.moller.tandem.core.data.DataProblem
import casa.moller.tandem.core.data.dataProblem
import casa.moller.tandem.core.data.permissions.SpacePermissionsRepository
import casa.moller.tandem.core.data.recipe.RecipeImportProblem
import casa.moller.tandem.core.data.recipe.RecipeImportRepository
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.recipe.RecipeImportResult
import casa.moller.tandem.core.model.recipe.RecipeImportSource
import casa.moller.tandem.feature.recipes.R
import casa.moller.tandem.feature.recipes.editor.SavedDraft
import casa.moller.tandem.feature.recipes.navigation.RecipeImportKey
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * "Importera recept" (Fas 3, import lane; plan §8.9; docs/BYGGE.md "Fas 3 – import"): a link or
 * text goes to [RecipeImportRepository] (the server parses it), a photo or PDF too when the
 * space has an AI provider. The answer is shown as a card and then opened in the recipe editor
 * as a new recipe (`RecipeEditorKey.importedDraft`), where it is checked and saved offline first.
 * The input lives in [SavedStateHandle]; the result does not (an import is repeated after
 * process death rather than kept: it is one request, and an AI answer may be stale).
 */
@HiltViewModel(assistedFactory = RecipeImportViewModel.Factory::class)
class RecipeImportViewModel @AssistedInject constructor(
    @Assisted key: RecipeImportKey,
    private val handle: SavedStateHandle,
    private val imports: RecipeImportRepository,
    permissions: SpacePermissionsRepository,
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(key: RecipeImportKey): RecipeImportViewModel
    }

    private val effectChannel = Channel<RecipeImportEffect>(Channel.BUFFERED)
    val effects: Flow<RecipeImportEffect> = effectChannel.receiveAsFlow()

    private val input: StateFlow<String> = handle.getStateFlow(KEY_INPUT, key.sharedText.orEmpty())
    private val sharedFileTaken: StateFlow<Boolean> = handle.getStateFlow(KEY_SHARED_FILE_TAKEN, false)
    private val sharedFile: SharedFile? = key.sharedFileUri?.let { SharedFile(it, key.sharedMimeType) }
    private val progress = MutableStateFlow(Progress())

    val uiState: StateFlow<RecipeImportUiState> = combine(
        input,
        progress,
        imports.observeAiImportAvailable(),
        permissions.permissions,
        sharedFileTaken,
    ) { input, progress, aiAvailable, permissions, taken ->
        RecipeImportUiState(
            input = input,
            working = progress.working,
            result = progress.result,
            problem = progress.problem,
            failure = progress.failure,
            aiAvailable = aiAvailable,
            sharedFile = sharedFile?.takeUnless { taken },
            readOnly = permissions.readOnly,
            isDemo = permissions.isDemo,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), RecipeImportUiState(input = input.value))

    fun onAction(action: RecipeImportAction) {
        when (action) {
            is RecipeImportAction.SetInput -> handle[KEY_INPUT] = action.text

            RecipeImportAction.Import -> import(sourceOf(input.value))

            is RecipeImportAction.ImportFile -> import(RecipeImportSource.File(action.bytes, action.mimeType, action.fileName))

            RecipeImportAction.FileUnreadable -> progress.update {
                it.copy(problem = R.string.recipe_import_file_unreadable, failure = null)
            }

            RecipeImportAction.SharedFileTaken -> handle[KEY_SHARED_FILE_TAKEN] = true

            is RecipeImportAction.ChooseImage -> progress.update { progress ->
                progress.copy(result = progress.result?.let { it.copy(draft = it.draft.copy(imageUrl = action.url)) })
            }

            RecipeImportAction.Clear -> progress.update { it.copy(result = null, problem = null, failure = null) }

            RecipeImportAction.OpenEditor -> progress.value.result?.let { result ->
                effectChannel.trySend(RecipeImportEffect.OpenEditor(SavedDraft.encode(result.draft)))
            }
        }
    }

    private fun import(source: RecipeImportSource?) {
        if (source == null || progress.value.working) return
        progress.update { it.copy(working = true, result = null, problem = null, failure = null) }
        viewModelScope.launch {
            when (val outcome = imports.import(source)) {
                is Outcome.Success -> progress.update { it.copy(working = false, result = outcome.value) }

                is Outcome.Failure -> {
                    val problem = problemOf(outcome.error, source)
                    val failure = outcome.error.takeIf { problem == null }
                    progress.update { it.copy(working = false, problem = problem ?: R.string.recipe_import_failed, failure = failure) }
                }
            }
        }
    }

    private fun problemOf(error: TandemError, source: RecipeImportSource): Int? = when (RecipeImportProblem.of(error)) {
        RecipeImportProblem.EMPTY_INPUT -> R.string.recipe_import_empty
        RecipeImportProblem.NOTHING_FOUND ->
            if (source is RecipeImportSource.File) R.string.recipe_import_nothing_in_file else R.string.recipe_import_nothing_found
        RecipeImportProblem.AI_UNAVAILABLE -> R.string.recipe_import_ai_unavailable
        RecipeImportProblem.FILE_TOO_LARGE -> R.string.recipe_import_file_too_large
        RecipeImportProblem.DEMO_MODE -> R.string.recipe_import_demo
        null -> when (error.dataProblem()) {
            DataProblem.READ_ONLY -> R.string.recipe_import_read_only
            else -> null
        }
    }

    private data class Progress(
        val working: Boolean = false,
        val result: RecipeImportResult? = null,
        val problem: Int? = null,
        val failure: TandemError? = null,
    )

    internal companion object {
        const val KEY_INPUT = "recipe_import_input"
        const val KEY_SHARED_FILE_TAKEN = "recipe_import_shared_file_taken"
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        /** Shared texts rarely are a bare link ("Pannkakor https://…" from a browser): the first link in a short text wins. */
        private const val LINK_IN_TEXT_MAX_LENGTH = 400
        private val LINK = Regex("""https?://\S+""")

        /** [text] as the source to send: a link when it is (or carries) one, else the text; null when blank. */
        fun sourceOf(text: String): RecipeImportSource? {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return null
            if (LINK.matches(trimmed)) return RecipeImportSource.Link(trimmed)
            if (trimmed.length <= LINK_IN_TEXT_MAX_LENGTH) {
                LINK.find(trimmed)?.let { return RecipeImportSource.Link(it.value.trimEnd('.', ',', ')')) }
            }
            return RecipeImportSource.Text(trimmed)
        }
    }
}
