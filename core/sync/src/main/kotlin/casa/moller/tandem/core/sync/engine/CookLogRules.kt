package casa.moller.tandem.core.sync.engine

import casa.moller.tandem.core.database.dao.CookStatsRules
import casa.moller.tandem.core.sync.account.ActiveSource

/**
 * How the cook log of this source turns into statistics (`CookLogDao`): a real account counts
 * its own ratings from the log (the server's lists carry none); demo mode keeps the demo
 * details' ratings unless a recipe was rated on this device, and has a single user.
 */
internal val ActiveSource.Stored.cookStatsRules: CookStatsRules
    get() = when (this) {
        is ActiveSource.Remote -> CookStatsRules(ownUserId = account.userId, ratingsFromLog = true)
        ActiveSource.Demo -> DEMO_COOK_STATS_RULES
    }

private val DEMO_COOK_STATS_RULES = CookStatsRules(ownUserId = null, ratingsFromLog = false)
