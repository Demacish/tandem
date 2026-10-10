package casa.moller.tandem.crash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import casa.moller.tandem.core.common.crash.CrashLog
import casa.moller.tandem.core.common.crash.CrashLogStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The crash logs from earlier runs (plan 4.2 p7): read once when the app starts, shown as a
 * card on the welcome step and in Mer until the user deletes them. Nothing is sent anywhere;
 * copying and sharing are the user's own actions in the UI.
 */
@HiltViewModel
class CrashReportViewModel @Inject constructor(private val store: CrashLogStore) : ViewModel() {
    private val state = MutableStateFlow<String?>(null)
    private var deleted = false

    /** All stored crash logs as one text (newest first), or null if there are none. */
    val report: StateFlow<String?> = state.asStateFlow()

    init {
        viewModelScope.launch {
            val text = reportText(withContext(Dispatchers.IO) { store.read() })
            // A delete while the logs were being read wins.
            if (!deleted) state.value = text
        }
    }

    /** "Ta bort": deletes every stored crash log. */
    fun delete() {
        deleted = true
        state.value = null
        viewModelScope.launch { withContext(Dispatchers.IO) { store.deleteAll() } }
    }

    internal companion object {
        fun reportText(logs: List<CrashLog>): String? = logs.takeIf { it.isNotEmpty() }
            ?.joinToString(separator = "\n----------------------------------------\n\n") { it.text.trimEnd() + "\n" }
    }
}
