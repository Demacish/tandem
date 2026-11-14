package casa.moller.tandem.core.sync.data

import casa.moller.tandem.core.data.sync.RequestGuard
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.sync.account.ActiveSources
import casa.moller.tandem.core.sync.guard.SyncGuard
import javax.inject.Inject

/** [RequestGuard] on [SyncGuard], with the token fingerprint `RemoteCaller` records (0.8.0 review, fix-08). */
internal class DefaultRequestGuard @Inject constructor(private val guard: SyncGuard, private val sources: ActiveSources) : RequestGuard {
    override fun check(accountId: String, background: Boolean): TandemError? = guard.check(accountId, background)

    override fun record(accountId: String, error: TandemError?) {
        guard.record(accountId, sources.tokenFingerprint(accountId), error)
    }
}
