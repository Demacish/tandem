package casa.moller.tandem.core.sync.calendar

import casa.moller.tandem.core.datastore.LocalCalendarRecord
import casa.moller.tandem.core.datastore.MealCalendarStore
import casa.moller.tandem.core.sync.work.MealCalendarWork
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** The local calendar's setting in memory. */
internal class FakeMealCalendarStore(initial: LocalCalendarRecord = LocalCalendarRecord()) : MealCalendarStore {
    override val state = MutableStateFlow(initial)

    override suspend fun setEnabled(enabled: Boolean) {
        state.update { if (enabled) it.copy(enabled = true) else LocalCalendarRecord() }
    }

    override suspend fun recordWrite(atMillis: Long, events: Int, problem: String?) {
        state.update {
            if (problem == null) it.copy(lastWrittenMillis = atMillis, events = events, problem = null) else it.copy(problem = problem)
        }
    }
}

/** Records "ensure" / "cancel". */
internal class RecordingMealCalendarWork : MealCalendarWork {
    val calls: MutableList<String> = CopyOnWriteArrayList()

    override fun ensure() {
        calls += "ensure"
    }

    override fun cancel() {
        calls += "cancel"
    }
}
