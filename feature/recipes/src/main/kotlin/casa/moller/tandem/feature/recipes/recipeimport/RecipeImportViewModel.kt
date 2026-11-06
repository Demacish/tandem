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
import casa.moller.tandem.core.model.recipe.RecipeDraft
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
import kotlinx.coroutines.flow.first
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
 *
 * Fas 3's leftovers: when the server cannot read a shared LINK (a site that blocks it), the page
 * is read in a WebView in the app and its HTML sent instead (#38, [PageReading]); a result can
 * have its ingredient lines moved to their steps by the space's AI (#40, one more AI call).
 *
 * Fas 4 review (fix-data; plan §8.4 step 3, #18, B12): a link nothing could read (an Instagram login
 * wall) is not the end: the text shared with it, or the page's visible text, goes to the AI when the
 * user asks for it, never by itself since it costs credits. Without a provider the shared text is
 * read on the device.
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
            pageReading = progress.pageReading,
            sortingSteps = progress.sortingSteps,
            stepsSorted = progress.beforeSort != null,
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

            RecipeImportAction.Clear -> progress.update { Progress() }

            RecipeImportAction.OpenEditor -> progress.value.result?.let { result ->
                effectChannel.trySend(RecipeImportEffect.OpenEditor(SavedDraft.encode(result.draft)))
            }

            is RecipeImportAction.PageLoaded -> sendPage(action.html, action.text)

            RecipeImportAction.ReadPageAgain -> progress.update { progress ->
                progress.copy(pageReading = progress.pageReading?.let { it.copy(readRequest = it.readRequest + 1) })
            }

            RecipeImportAction.CancelPage -> progress.update {
                it.copy(pageReading = null, problem = R.string.recipe_import_nothing_found, failure = null)
            }

            RecipeImportAction.InterpretText -> interpretText()

            RecipeImportAction.SortSteps -> sortSteps()

            RecipeImportAction.UndoSortSteps -> progress.update { progress ->
                val before = progress.beforeSort ?: return@update progress
                progress.copy(result = progress.result?.copy(draft = before), beforeSort = null)
            }
        }
    }

    /**
     * #38: the page's HTML from the WebView goes to the server with the link. Each finished load
     * sends once more (a page that first asked the visitor to confirm something), never two at a time.
     */
    private fun sendPage(html: String, text: String) {
        val reading = progress.value.pageReading ?: return
        if (reading.sending || html.isBlank()) return
        progress.update { it.copy(pageReading = reading.copy(sending = true, pageText = text.ifBlank { reading.pageText })) }
        viewModelScope.launch {
            val outcome = imports.import(RecipeImportSource.Page(reading.url, html))
            // Cancelled meanwhile: the answer is dropped.
            progress.update { progress -> progress.pageReading?.let { progress.afterPage(it, outcome) } ?: progress }
        }
    }

    private fun Progress.afterPage(current: PageReading, outcome: Outcome<RecipeImportResult>): Progress {
        if (outcome is Outcome.Success) return copy(pageReading = null, result = outcome.value)
        val error = (outcome as Outcome.Failure).error
        if (RecipeImportProblem.of(error) == RecipeImportProblem.NOTHING_FOUND) {
            return copy(pageReading = current.copy(sending = false, nothingFound = true))
        }
        val problem = problemOf(error, RecipeImportSource.Link(current.url))
        return copy(pageReading = null, problem = problem ?: R.string.recipe_import_failed, failure = error.takeIf { problem == null })
    }

    /** The last step of the chain: the AI reads the caption or the page's text, or the device the caption. */
    private fun interpretText() {
        val reading = progress.value.pageReading ?: return
        viewModelScope.launch {
            val source = if (imports.observeAiImportAvailable().first()) {
                RecipeImportSource.AiText(reading.caption.ifBlank { reading.pageText })
            } else {
                RecipeImportSource.Text(reading.caption)
            }
            import(source, sourceUrl = reading.url)
        }
    }

    /** #40: the space's AI moves the result's ingredient lines to their steps; the draft before is kept for Ångra. */
    private fun sortSteps() {
        val current = progress.value
        val result = current.result ?: return
        if (current.sortingSteps) return
        progress.update { it.copy(sortingSteps = true) }
        viewModelScope.launch {
            val outcome = imports.sortIngredientsIntoSteps(result.draft)
            val message = when (outcome) {
                is Outcome.Success -> R.string.recipe_import_sort_done

                is Outcome.Failure -> when (RecipeImportProblem.of(outcome.error)) {
                    RecipeImportProblem.STEP_SORT_CHANGED -> R.string.recipe_import_sort_changed
                    RecipeImportProblem.AI_UNAVAILABLE -> R.string.recipe_import_ai_unavailable
                    else -> R.string.recipe_import_sort_failed
                }
            }
            progress.update { progress ->
                if (outcome !is Outcome.Success) return@update progress.copy(sortingSteps = false)
                progress.copy(
                    sortingSteps = false,
                    result = progress.result?.copy(draft = outcome.value),
                    beforeSort = progress.beforeSort ?: result.draft,
                )
            }
            effectChannel.trySend(RecipeImportEffect.Message(message))
        }
    }

    /** [sourceUrl]: the link a text came with, the recipe's source unless the import found one. */
    private fun import(source: RecipeImportSource?, sourceUrl: String? = null) {
        if (source == null || progress.value.working) return
        val caption = (source as? RecipeImportSource.Link)?.let { captionOf(input.value, it.url) }.orEmpty()
        progress.update { Progress(working = true) }
        viewModelScope.launch {
            when (val outcome = imports.import(source)) {
                is Outcome.Success -> progress.update { it.copy(working = false, result = outcome.value.withSource(sourceUrl)) }

                is Outcome.Failure -> {
                    val problem = problemOf(outcome.error, source)
                    val blocked = source is RecipeImportSource.Link &&
                        RecipeImportProblem.of(outcome.error) == RecipeImportProblem.NOTHING_FOUND &&
                        source.url.startsWith("http", ignoreCase = true)
                    val failure = outcome.error.takeIf { problem == null }
                    progress.update {
                        if (blocked) {
                            // #38: the server could not read the link: the app reads the page itself.
                            it.copy(working = false, pageReading = PageReading(source.url, caption = caption))
                        } else {
                            it.copy(working = false, problem = problem ?: R.string.recipe_import_failed, failure = failure)
                        }
                    }
                }
            }
        }
    }

    private fun RecipeImportResult.withSource(url: String?): RecipeImportResult =
        if (url == null || draft.sourceUrl.isNotBlank()) this else copy(draft = draft.copy(sourceUrl = url))

    private fun problemOf(error: TandemError, source: RecipeImportSource): Int? = when (RecipeImportProblem.of(error)) {
        RecipeImportProblem.EMPTY_INPUT -> R.string.recipe_import_empty

        RecipeImportProblem.NOTHING_FOUND ->
            if (source is RecipeImportSource.File) R.string.recipe_import_nothing_in_file else R.string.recipe_import_nothing_found

        RecipeImportProblem.AI_UNAVAILABLE -> R.string.recipe_import_ai_unavailable

        RecipeImportProblem.FILE_TOO_LARGE -> R.string.recipe_import_file_too_large

        RecipeImportProblem.DEMO_MODE -> R.string.recipe_import_demo_problem

        RecipeImportProblem.STEP_SORT_CHANGED -> R.string.recipe_import_sort_changed

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
        val pageReading: PageReading? = null,
        val sortingSteps: Boolean = false,
        val beforeSort: RecipeDraft? = null,
    )

    internal companion object {
        const val KEY_INPUT = "recipe_import_input"
        const val KEY_SHARED_FILE_TAKEN = "recipe_import_shared_file_taken"
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        /** Shared texts rarely are a bare link ("Pannkakor https://…" from a browser): the first link in a short text wins. */
        private const val LINK_IN_TEXT_MAX_LENGTH = 400
        private val LINK = Regex("""https?://\S+""")

        /** What was shared together with [url] ("Pannkakor https://…"): [text] without the link. */
        fun captionOf(text: String, url: String): String = text.replace(url, " ").trim(' ', '\n', '\t', '.', ',', ':', '-', '–')

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
