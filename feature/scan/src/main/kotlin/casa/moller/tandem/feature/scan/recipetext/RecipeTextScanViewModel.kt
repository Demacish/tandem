package casa.moller.tandem.feature.scan.recipetext

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import casa.moller.tandem.core.data.permissions.SpacePermissionsRepository
import casa.moller.tandem.core.domain.scan.RecipeTextLayout
import casa.moller.tandem.core.domain.scan.TextFragment
import casa.moller.tandem.feature.scan.engine.ReceiptTextEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
 * What "Fota recept" shows (Fas 4, textigenkänning av recept; ROADMAP "Fas 4").
 *
 * @property pages the photos read so far, each as its text (a long page or a spread is several).
 * @property reading a photo is being read.
 * @property problem the last photo gave nothing, or could not be read.
 * @property readOnly a guest: the recipe could not be saved, so nothing is read.
 */
@Immutable
data class RecipeTextScanUiState(
    val pages: List<String> = emptyList(),
    val reading: Boolean = false,
    val problem: RecipeTextProblem? = null,
    val readOnly: Boolean = false,
) {
    /** What the import gets: the pages as one text. */
    val text: String
        get() = RecipeTextLayout.join(pages)

    /** The first lines of what was read, for the preview. */
    val preview: String
        get() = text.lines().filter { it.isNotBlank() }.take(PREVIEW_LINES).joinToString("\n")

    companion object {
        const val PREVIEW_LINES: Int = 6
    }
}

enum class RecipeTextProblem { NO_TEXT, UNREADABLE }

sealed interface RecipeTextScanAction {
    /** A photo is on its way (the capture started). */
    data object Reading : RecipeTextScanAction

    /** Reads one photo (decoding and ML Kit, off the main thread) into its fragments. */
    class ReadPage(val read: suspend () -> List<TextFragment>) : RecipeTextScanAction

    /** The page is shown (a capture of a page before a recreation never answers). */
    data object Shown : RecipeTextScanAction

    class PageRead(val fragments: List<TextFragment>) : RecipeTextScanAction

    data object PageFailed : RecipeTextScanAction

    data object RemoveLastPage : RecipeTextScanAction

    data object DismissProblem : RecipeTextScanAction

    /** "Använd texten": hand the text to the import. */
    data object Done : RecipeTextScanAction
}

sealed interface RecipeTextScanEffect {
    /** The text goes to "Importera recept" (the shell opens it in this page's place). */
    data class TextRead(val text: String) : RecipeTextScanEffect
}

@HiltViewModel
class RecipeTextScanViewModel @Inject constructor(private val handle: SavedStateHandle, permissions: SpacePermissionsRepository) :
    ViewModel() {
    private data class Local(val reading: Boolean = false, val problem: RecipeTextProblem? = null)

    private val effectChannel = Channel<RecipeTextScanEffect>(Channel.BUFFERED)
    val effects: Flow<RecipeTextScanEffect> = effectChannel.receiveAsFlow()

    /** The text read so far: the camera and the picker are when the system kills the app, and it is small. */
    private val pages: StateFlow<ArrayList<String>> = handle.getStateFlow(KEY_PAGES, ArrayList())
    private val local = MutableStateFlow(Local())
    private var readJob: Job? = null
    private var engine: ReceiptTextEngine? = null

    val uiState: StateFlow<RecipeTextScanUiState> = combine(pages, local, permissions.permissions) { read, ui, rights ->
        RecipeTextScanUiState(pages = read, reading = ui.reading, problem = ui.problem, readOnly = rights.readOnly)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), RecipeTextScanUiState())

    fun onAction(action: RecipeTextScanAction) {
        when (action) {
            RecipeTextScanAction.Reading -> local.update { it.copy(reading = true, problem = null) }
            is RecipeTextScanAction.ReadPage -> readPage(action.read)
            RecipeTextScanAction.Shown -> if (readJob?.isActive != true) local.update { it.copy(reading = false) }
            is RecipeTextScanAction.PageRead -> pageRead(action.fragments)
            RecipeTextScanAction.PageFailed -> local.update { it.copy(reading = false, problem = RecipeTextProblem.UNREADABLE) }
            RecipeTextScanAction.RemoveLastPage -> handle[KEY_PAGES] = ArrayList(pages.value.dropLast(1))
            RecipeTextScanAction.DismissProblem -> local.update { it.copy(problem = null) }
            RecipeTextScanAction.Done -> done()
        }
    }

    /** The text recognizer of this page (the receipt's, Latin text on the device): created once, closed with the ViewModel. */
    internal fun engine(context: Context): ReceiptTextEngine = engine ?: ReceiptTextEngine(context).also { engine = it }

    @Suppress("TooGenericExceptionCaught") // ML Kit and the decoder fail a broken or unsupported image with any Exception.
    private fun readPage(read: suspend () -> List<TextFragment>) {
        if (readJob?.isActive == true) return
        local.update { it.copy(reading = true, problem = null) }
        readJob = viewModelScope.launch {
            val result = try {
                RecipeTextScanAction.PageRead(read())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                RecipeTextScanAction.PageFailed
            } catch (e: OutOfMemoryError) {
                RecipeTextScanAction.PageFailed
            }
            onAction(result)
        }
    }

    private fun pageRead(fragments: List<TextFragment>) {
        val text = RecipeTextLayout.text(fragments)
        if (text.isBlank()) {
            local.update { it.copy(reading = false, problem = RecipeTextProblem.NO_TEXT) }
            return
        }
        val room = MAX_SAVED_CHARS - pages.value.sumOf { it.length }
        if (room > 0) handle[KEY_PAGES] = ArrayList(pages.value + text.take(room))
        local.update { it.copy(reading = false, problem = null) }
    }

    private fun done() {
        val text = uiState.value.text
        if (text.isBlank()) return
        effectChannel.trySend(RecipeTextScanEffect.TextRead(text))
    }

    override fun onCleared() {
        engine?.close()
        super.onCleared()
    }

    internal companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L
        private const val KEY_PAGES = "recipe_text_pages"

        /** Far more than a recipe, and small for a saved Bundle (the system's limit is about 1 MB). */
        const val MAX_SAVED_CHARS: Int = 100_000
    }
}
