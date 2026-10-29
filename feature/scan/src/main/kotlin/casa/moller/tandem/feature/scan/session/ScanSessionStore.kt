package casa.moller.tandem.feature.scan.session

import casa.moller.tandem.core.model.scan.ScanReviewItem
import casa.moller.tandem.core.model.scan.ScanSource
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * One scan's rows on their way to the shared review list (plan §8.8: nothing is saved before the
 * user confirms). [ScanReviewKey][casa.moller.tandem.feature.scan.navigation.ScanReviewKey] names it
 * by [id].
 *
 * @property locationId where rows without their own place go (the scanner's `locationId`), null =
 *   the household's first location.
 * @property saved set when the review saved: the number of entries now in the pantry. The scanner
 *   that opened the review sees it and closes (receipt, fridge photo) or starts over (barcode).
 */
data class ScanSession(
    val id: String,
    val source: ScanSource,
    val locationId: Long?,
    val items: List<ScanReviewItem> = emptyList(),
    val saved: Int? = null,
)

/**
 * The scans in memory, shared by the scanners of feature:scan and the review (docs/BYGGE.md
 * "Skanning"). Only in memory, on purpose: a receipt's or a photo's text never reaches the disk or
 * the saved state; after process death the session is gone and the review offers to scan again.
 *
 * Contract for a scanner (barcode, receipt, and the ai-foto lane's fridge photo):
 * 1. [create] a session with the rows ([ScanReviewItem], one per thing found);
 * 2. open `ScanReviewKey(id)`;
 * 3. observe the session: when [ScanSession.saved] is set, the review saved and closed itself;
 *    the scanner closes too (or starts a new session) and [remove]s the old one.
 */
@Singleton
class ScanSessionStore @Inject constructor() {
    private val sessions = MutableStateFlow<Map<String, ScanSession>>(emptyMap())

    /** A new session; returns its id. */
    fun create(source: ScanSource, locationId: Long?, items: List<ScanReviewItem> = emptyList()): String {
        val id = UUID.randomUUID().toString()
        sessions.update { it + (id to ScanSession(id, source, locationId, items)) }
        return id
    }

    fun get(id: String): ScanSession? = sessions.value[id]

    fun observe(id: String): Flow<ScanSession?> = sessions.map { it[id] }.distinctUntilChanged()

    /** Changes the session [id] (nothing when it is gone). */
    fun update(id: String, transform: (ScanSession) -> ScanSession) {
        sessions.update { all -> all[id]?.let { all + (id to transform(it)) } ?: all }
    }

    /** The review saved [count] entries: the rows are dropped and the scanner is told. */
    fun markSaved(id: String, count: Int) = update(id) { it.copy(items = emptyList(), saved = count) }

    fun remove(id: String) {
        sessions.update { it - id }
    }
}
