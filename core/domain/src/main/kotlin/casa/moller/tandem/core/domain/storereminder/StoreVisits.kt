package casa.moller.tandem.core.domain.storereminder

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Fas 5 (butik lane, plan §10 Fas 5 "högst en gång per besök"): one store's visit, as the geofence
 * events tell it.
 *
 * @property inside the last event was ENTER (and no EXIT since).
 * @property lastTransitionAt when the last event (ENTER or EXIT) came, null before the first.
 * @property lastRemindedAt when the reminder was last posted for this store, null when never.
 */
data class StoreVisit(val inside: Boolean = false, val lastTransitionAt: Instant? = null, val lastRemindedAt: Instant? = null)

/** What the phone said about one store. */
enum class StoreTransition { ENTER, EXIT }

/** What to do with an ENTER. */
enum class EnterDecision {
    /** A new visit: post the reminder (if the list has something on it) and call [StoreVisits.reminded]. */
    REMIND,

    /** Still the same visit (no EXIT since the ENTER that started it): nothing. */
    SAME_VISIT,

    /** A new visit, but the last reminder for this store was less than [StoreVisits.MIN_GAP] ago: nothing. */
    TOO_SOON,

    /** An event older than one already handled (two runs crossed): nothing, the state stays. */
    OUT_OF_ORDER,
}

/**
 * The once-per-visit rule (pure, so it is tested without a phone):
 *
 * - **ENTER** starts a visit and reminds, unless the store's previous visit is still open (no EXIT
 *   since its ENTER, within [STALE_VISIT]) or the last reminder for the store came less than
 *   [MIN_GAP] ago (walking out to the car and back in, or a geofence that flickers at the edge).
 * - **EXIT** ends the visit; only after it can a later ENTER remind again (re-armed), and then only
 *   when [MIN_GAP] has passed since the last reminder.
 * - An open visit older than [STALE_VISIT] counts as ended: a lost EXIT (the phone was off, the
 *   geofences were put back after a reboot) never silences the store for good.
 * - An event older than the last one handled by less than [OUT_OF_ORDER_WINDOW] is ignored (two
 *   background runs that crossed). A larger step back means the clock was changed: the event is
 *   taken as it comes, and a reminder "in the future" counts as long ago.
 */
object StoreVisits {
    /** At least this long between two reminders for the same store. */
    val MIN_GAP: Duration = 3.hours

    /** An ENTER without an EXIT for this long is a visit of its own. */
    val STALE_VISIT: Duration = 12.hours

    /** Crossed runs: events this close are put in order, a larger step back is a changed clock. */
    val OUT_OF_ORDER_WINDOW: Duration = 10.minutes

    /** The visit after an ENTER at [at] and what to do. */
    fun onEnter(visit: StoreVisit, at: Instant): Pair<StoreVisit, EnterDecision> {
        if (isOutOfOrder(visit, at)) return visit to EnterDecision.OUT_OF_ORDER
        val entered = visit.copy(inside = true, lastTransitionAt = at)
        val since = visit.lastTransitionAt?.let { at - it }
        if (visit.inside && since != null && since >= Duration.ZERO && since < STALE_VISIT) {
            return visit.copy(lastTransitionAt = at) to EnterDecision.SAME_VISIT
        }
        val sinceReminder = visit.lastRemindedAt?.let { at - it }
        if (sinceReminder != null && sinceReminder >= Duration.ZERO && sinceReminder < MIN_GAP) {
            return entered to EnterDecision.TOO_SOON
        }
        return entered to EnterDecision.REMIND
    }

    /** The visit after an EXIT at [at] (the store is re-armed). */
    fun onExit(visit: StoreVisit, at: Instant): StoreVisit {
        if (isOutOfOrder(visit, at)) return visit
        return visit.copy(inside = false, lastTransitionAt = at)
    }

    /** The reminder was posted at [at]. */
    fun reminded(visit: StoreVisit, at: Instant): StoreVisit = visit.copy(lastRemindedAt = at)

    private fun isOutOfOrder(visit: StoreVisit, at: Instant): Boolean {
        val last = visit.lastTransitionAt ?: return false
        val behind = last - at
        return behind > Duration.ZERO && behind < OUT_OF_ORDER_WINDOW
    }
}
