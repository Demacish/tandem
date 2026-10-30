package casa.moller.tandem.feature.scan.receipt

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import casa.moller.tandem.core.data.permissions.SpacePermissionsRepository
import casa.moller.tandem.core.domain.scan.ReceiptLayout
import casa.moller.tandem.core.domain.scan.ReceiptParser
import casa.moller.tandem.core.domain.scan.TextFragment
import casa.moller.tandem.core.model.scan.ScanSource
import casa.moller.tandem.feature.scan.ScanMatcher
import casa.moller.tandem.feature.scan.engine.ReceiptTextEngine
import casa.moller.tandem.feature.scan.navigation.ReceiptScanKey
import casa.moller.tandem.feature.scan.session.ScanSessionStore
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Why the receipt gave no review. */
enum class ReceiptProblem {
    /** The last photo had no text at all (blurred, dark, not a receipt). */
    NO_TEXT,

    /** There was text, but no line looked like an item. */
    NO_ITEMS,

    /** The photo could not be taken or read. */
    UNREADABLE,
}

/**
 * The receipt scanner (#44).
 *
 * @property pages photos read so far (a long receipt can take several).
 * @property reading a photo is being taken or read.
 * @property matching the lines are being matched to foods.
 */
data class ReceiptScanUiState(
    val readOnly: Boolean = false,
    val demo: Boolean = false,
    val pages: Int = 0,
    val reading: Boolean = false,
    val matching: Boolean = false,
    val problem: ReceiptProblem? = null,
) {
    val busy: Boolean
        get() = reading || matching
}

sealed interface ReceiptScanAction {
    /** A photo is being taken (or a picked one read). */
    data object Reading : ReceiptScanAction

    /**
     * Read one photo with [read] (decoding and recognising off the main thread). It runs in the
     * ViewModel, so a rotation, fold or resize meanwhile neither loses it nor leaves "reading"
     * stuck (Fas S review, fix-ui).
     */
    class ReadPage(val read: suspend () -> List<TextFragment>) : ReceiptScanAction {
        override fun toString(): String = "ReadPage"
    }

    /** The scanner's page (re)appeared: a photo whose capture was lost with the old camera is no longer awaited. */
    data object Shown : ReceiptScanAction

    /** The recognised text of one photo, with its boxes (only the text is kept, in memory). */
    data class PageRead(val fragments: List<TextFragment>) : ReceiptScanAction

    data object PageFailed : ReceiptScanAction

    data object RemoveLastPage : ReceiptScanAction

    /** "Läs kvittot": parse, match and open the review. */
    data object Done : ReceiptScanAction

    data object DismissProblem : ReceiptScanAction
}

sealed interface ReceiptScanEffect {
    data class OpenReview(val sessionId: String) : ReceiptScanEffect

    /** The review saved the receipt: the scanner closes. */
    data object Finished : ReceiptScanEffect
}

/**
 * The receipt scanner (#44, plan §8.8 "Kvitto"): text recognition on the device (the route), one
 * or more photos of a long receipt, then core:domain `ReceiptLayout` + `ReceiptParser` (totals,
 * VAT, pant, discounts, payment, loyalty, dates and header lines dropped) and the matching
 * (learned receipt names first, then the stored foods). The rows go to the shared review list;
 * nothing is saved before the user confirms there.
 *
 * Privacy: photos are never stored (the route reads them in memory and closes them); the
 * recognised text lives only in this ViewModel (not in the saved state) until the review opens,
 * and only item lines reach the review. Nothing is logged.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = ReceiptScanViewModel.Factory::class)
class ReceiptScanViewModel @AssistedInject internal constructor(
    @Assisted private val key: ReceiptScanKey,
    private val store: ScanSessionStore,
    private val matcher: ScanMatcher,
    permissions: SpacePermissionsRepository,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(key: ReceiptScanKey): ReceiptScanViewModel
    }

    private data class Local(val reading: Boolean = false, val matching: Boolean = false, val problem: ReceiptProblem? = null)

    private val effectChannel = Channel<ReceiptScanEffect>(Channel.BUFFERED)
    val effects: Flow<ReceiptScanEffect> = effectChannel.receiveAsFlow()

    private val pages = MutableStateFlow<List<List<String>>>(emptyList())
    private val local = MutableStateFlow(Local())
    private val sessionId = MutableStateFlow<String?>(null)
    private var pagesInSession = -1
    private var readJob: Job? = null
    private var engine: ReceiptTextEngine? = null

    val uiState: StateFlow<ReceiptScanUiState> = combine(pages, local, permissions.permissions) { read, ui, rights ->
        ReceiptScanUiState(
            readOnly = rights.readOnly,
            demo = rights.isDemo,
            pages = read.size,
            reading = ui.reading,
            matching = ui.matching,
            problem = ui.problem,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), ReceiptScanUiState())

    init {
        // The review saved the receipt: close the scanner too.
        viewModelScope.launch {
            sessionId.flatMapLatest { id -> id?.let { store.observe(it) } ?: emptyFlow() }.collect { session ->
                if (session?.saved != null) {
                    store.remove(session.id)
                    sessionId.value = null
                    pages.value = emptyList()
                    effectChannel.send(ReceiptScanEffect.Finished)
                }
            }
        }
    }

    fun onAction(action: ReceiptScanAction) {
        when (action) {
            ReceiptScanAction.Reading -> local.update { it.copy(reading = true, problem = null) }
            is ReceiptScanAction.ReadPage -> readPage(action.read)
            ReceiptScanAction.Shown -> if (readJob?.isActive != true) local.update { it.copy(reading = false) }
            is ReceiptScanAction.PageRead -> pageRead(action.fragments)
            ReceiptScanAction.PageFailed -> local.update { it.copy(reading = false, problem = ReceiptProblem.UNREADABLE) }
            ReceiptScanAction.RemoveLastPage -> pages.update { it.dropLast(1) }
            ReceiptScanAction.Done -> done()
            ReceiptScanAction.DismissProblem -> local.update { it.copy(problem = null) }
        }
    }

    /** The text recognizer of this scanner: created once (it outlives a rotation) and closed with the ViewModel. */
    internal fun engine(context: Context): ReceiptTextEngine = engine ?: ReceiptTextEngine(context).also { engine = it }

    @Suppress("TooGenericExceptionCaught") // ML Kit and the decoder fail a broken or unsupported image with any Exception.
    private fun readPage(read: suspend () -> List<TextFragment>) {
        if (readJob?.isActive == true) return
        local.update { it.copy(reading = true, problem = null) }
        readJob = viewModelScope.launch {
            val result = try {
                ReceiptScanAction.PageRead(read())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ReceiptScanAction.PageFailed
            } catch (e: OutOfMemoryError) {
                ReceiptScanAction.PageFailed
            }
            onAction(result)
        }
    }

    private fun pageRead(fragments: List<TextFragment>) {
        val rows = ReceiptLayout.rows(fragments)
        if (rows.isEmpty()) {
            local.update { it.copy(reading = false, problem = ReceiptProblem.NO_TEXT) }
        } else {
            pages.update { it + listOf(rows) }
            local.update { it.copy(reading = false, problem = null) }
        }
    }

    private fun done() {
        val read = pages.value
        if (read.isEmpty() || local.value.matching) return
        // Back from the review without saving and nothing new: the same review again (with its edits).
        val existing = sessionId.value?.takeIf { pagesInSession == read.size && store.get(it) != null }
        if (existing != null) {
            effectChannel.trySend(ReceiptScanEffect.OpenReview(existing))
            return
        }
        local.update { it.copy(matching = true, problem = null) }
        viewModelScope.launch {
            val items = ReceiptParser.parse(ReceiptLayout.join(read))
            if (items.isEmpty()) {
                local.update { it.copy(matching = false, problem = ReceiptProblem.NO_ITEMS) }
                return@launch
            }
            val rows = matcher.receiptRows(items, matcher.foods())
            sessionId.value?.let(store::remove)
            val id = store.create(ScanSource.RECEIPT, key.locationId, rows)
            pagesInSession = read.size
            sessionId.value = id
            local.update { it.copy(matching = false) }
            effectChannel.send(ReceiptScanEffect.OpenReview(id))
        }
    }

    override fun onCleared() {
        sessionId.value?.let(store::remove)
        engine?.close()
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
