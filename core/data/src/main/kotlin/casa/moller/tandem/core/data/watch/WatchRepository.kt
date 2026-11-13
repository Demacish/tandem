package casa.moller.tandem.core.data.watch

import casa.moller.tandem.core.model.watch.WatchResult
import casa.moller.tandem.core.model.watch.WatchState
import kotlinx.coroutines.flow.Flow

/**
 * Fas 5 (wear lane, plan §7 #33, §3.1): Tandem on a Wear OS watch. Implemented in core:watch on
 * the Wearable Data Layer (Google Play services); without them [state] says
 * [casa.moller.tandem.core.model.watch.WatchAvailability.UNAVAILABLE] and nothing else happens.
 *
 * Pairing gives the watch its OWN token (scope `read write`, 90 days, renewed by the phone while
 * paired), created by the phone with the active account's token and sent to the watch over the
 * Data Layer (a message to a nearby watch, never a stored item, never the cloud, a backup or a
 * log). The phone's own token never leaves the phone. Main-safe.
 */
interface WatchRepository {
    val state: Flow<WatchState>

    /** Looks for watches again (the page opened or came back). */
    suspend fun refresh()

    /** "Koppla klockan": creates the watch token and sends it; replaces an earlier pairing. */
    suspend fun pair(): WatchResult

    /** "Koppla från klockan": deletes the watch token on the server and wipes the watch. */
    suspend fun unpair(): WatchResult
}
