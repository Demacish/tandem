package casa.moller.tandem.core.sync.mealplan

import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.engine.DrainResult
import casa.moller.tandem.core.sync.engine.OutboxSender
import casa.moller.tandem.core.sync.platform.NetworkStatus
import casa.moller.tandem.core.sync.platform.SyncScheduler
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.datetime.LocalDate

/**
 * One refresh of the meal plan outside a sync run (pull to refresh in Veckan, its polling at most
 * every 60 s, plan 3.5): the window first, which also drops queued changes of meals deleted
 * elsewhere ([MealPlanWindowReader]), then the outbox, whose PATCHes compare fields with the
 * server's current meal before they go.
 */
@Singleton
internal class MealPlanSync @Inject constructor(
    private val reader: MealPlanWindowReader,
    private val outbox: OutboxSender,
    private val scheduler: SyncScheduler,
    private val network: NetworkStatus,
) {
    /** [range] (fix-ui): read only these days instead of the window (a week Veckan shows outside it). */
    suspend fun refresh(remote: ActiveSource.Remote, background: Boolean, range: ClosedRange<LocalDate>? = null): Outcome<Unit> {
        if (!network.online.value) return Outcome.Failure(TandemError.NetworkUnavailable)
        val read = reader.read(remote, background, range)
        if (outbox.drain(remote) == DrainResult.RETRY) scheduler.scheduleOutbox()
        return read
    }
}
