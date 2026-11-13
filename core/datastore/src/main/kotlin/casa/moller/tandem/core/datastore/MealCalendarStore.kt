package casa.moller.tandem.core.datastore

import kotlinx.coroutines.flow.Flow

/**
 * Fas 5 (kalender lane, plan §7 #36): "Visa i telefonens kalender" on this phone, in its own small
 * Preferences DataStore file ([DatastoreFiles.MEAL_CALENDAR]): whether it is on and how the last
 * write went. Nothing personal (no meal, no token); a failed read is "off", a failed write is dropped.
 */
interface MealCalendarStore {
    val state: Flow<LocalCalendarRecord>

    suspend fun setEnabled(enabled: Boolean)

    /** The outcome of a write: [problem] null after a successful one ([events] meals, at [atMillis]). */
    suspend fun recordWrite(atMillis: Long, events: Int, problem: String?)
}

/**
 * @property enabled the user turned the local calendar on.
 * @property lastWrittenMillis when it was last brought in step, null when never.
 * @property events how many meals it held then.
 * @property problem the name of a `LocalMealCalendarProblem` when the last attempt failed.
 */
data class LocalCalendarRecord(
    val enabled: Boolean = false,
    val lastWrittenMillis: Long? = null,
    val events: Int = 0,
    val problem: String? = null,
)
