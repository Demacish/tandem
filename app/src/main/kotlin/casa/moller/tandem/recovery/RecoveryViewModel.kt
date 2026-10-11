package casa.moller.tandem.recovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import casa.moller.tandem.core.data.sync.PendingChangesExporter
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.catchingUnexpected
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Optional
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * "Export pending changes" on the recovery screen (feature #37).
 *
 * @property available an outbox exporter is bound in this build (see [PendingChangesExporter]).
 * @property pendingChanges changes waiting in the outbox; null while unknown or unreadable.
 * @property exporting an export is being prepared.
 */
data class PendingExportState(val available: Boolean = false, val pendingChanges: Int? = null, val exporting: Boolean = false) {
    /** The button is enabled: there is something to export and no export is running. */
    val canExport: Boolean
        get() = available && (pendingChanges ?: 0) > 0 && !exporting
}

/** One-off effects of [RecoveryViewModel]. */
sealed interface RecoveryEffect {
    /** Share [json] as text through the system share sheet. */
    data class SharePendingChanges(val json: String) : RecoveryEffect

    /** The outbox could not be read; nothing was shared. */
    data object ExportFailed : RecoveryEffect
}

/**
 * The recovery screen's own state: the pending outbox and its export. Logging in again and
 * clearing the app data stay with the app's session handling (AppViewModel).
 *
 * The exporter is OPTIONAL (`@BindsOptionalOf`, casa.moller.tandem.di.PendingChangesModule): until
 * the outbox's implementation binds one, the export is shown as unavailable instead of failing.
 */
@HiltViewModel
class RecoveryViewModel @Inject constructor(exporter: Optional<PendingChangesExporter>) : ViewModel() {

    private val exporter: PendingChangesExporter? = exporter.orElse(null)
    private val exporting = MutableStateFlow(false)
    private val effectChannel = Channel<RecoveryEffect>(Channel.BUFFERED)

    /** One-off effects; collect them once. */
    val effects: Flow<RecoveryEffect> = effectChannel.receiveAsFlow()

    private val pendingCount: Flow<Int?> = this.exporter
        ?.observePendingCount()
        ?.map<Int, Int?> { it }
        // The recovery screen must never crash on a broken outbox: the count is just unknown.
        ?.catch { emit(null) }
        ?: flowOf(null)

    val exportState: StateFlow<PendingExportState> = combine(pendingCount, exporting) { count, running ->
        PendingExportState(available = this.exporter != null, pendingChanges = count, exporting = running)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), PendingExportState(available = this.exporter != null))

    /** Reads the outbox as JSON and asks the screen to share it. */
    fun exportPendingChanges() {
        val source = exporter ?: return
        if (exporting.value) return
        exporting.value = true
        viewModelScope.launch {
            // A broken outbox must show "could not export", not crash the recovery screen.
            val result = catchingUnexpected { source.exportAsJson() }
            exporting.value = false
            effectChannel.send(
                when (result) {
                    is Outcome.Success -> RecoveryEffect.SharePendingChanges(result.value)
                    is Outcome.Failure -> RecoveryEffect.ExportFailed
                },
            )
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
