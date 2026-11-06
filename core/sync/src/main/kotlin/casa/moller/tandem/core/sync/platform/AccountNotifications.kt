package casa.moller.tandem.core.sync.platform

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import casa.moller.tandem.core.data.di.ApplicationScope
import casa.moller.tandem.core.model.notification.TandemNotificationIds
import casa.moller.tandem.core.sync.account.ActiveSource
import casa.moller.tandem.core.sync.account.ActiveSources
import casa.moller.tandem.core.sync.account.identity
import casa.moller.tandem.core.sync.push.AndroidFastPushNotifier
import casa.moller.tandem.core.sync.recipeimport.KeptImportCleaner
import casa.moller.tandem.core.sync.shopping.ShoppingChangeNotifications
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/** Removes the notifications that say something about the account (Fas 4; tests record the call). */
internal fun interface AccountNotifications {
    fun cancelAll()
}

/** The list, the pantry, an import and the fast push test notification. Timers are not the account's (`TimerSource` tells them apart). */
internal class AndroidAccountNotifications @Inject constructor(@ApplicationContext private val context: Context) : AccountNotifications {
    override fun cancelAll() {
        val manager = NotificationManagerCompat.from(context)
        manager.cancel(TandemNotificationIds.SHOPPING_CHANGES)
        manager.cancel(TandemNotificationIds.PANTRY_EXPIRY)
        manager.cancel(TandemNotificationIds.IMPORT_DONE)
        manager.cancel(AndroidFastPushNotifier.TEST_NOTIFICATION_ID)
    }
}

/**
 * Fas 4 (integration): what the active account's list or pantry said stays with it. When the source
 * changes (another account, logout, demo mode) the open notifications are removed and changes still
 * gathered for one are dropped, so nothing of one household shows in the next (started by
 * `SyncStarter`; the first source of a process is not a change). An AI answer waiting to be reviewed
 * goes with them: it does not come back when the user switches back to the account.
 */
@Singleton
internal class AccountNotificationCleanup @Inject constructor(
    private val sources: ActiveSources,
    private val shopping: ShoppingChangeNotifications,
    private val notifications: AccountNotifications,
    private val keptImports: KeptImportCleaner,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            var previous: ActiveSource? = null
            sources.source
                .filter { it != ActiveSource.Loading }
                .distinctUntilChangedBy { it.identity }
                .collect { current ->
                    val left = previous
                    previous = current
                    if (left != null) {
                        shopping.dropPending()
                        notifications.cancelAll()
                        if (left is ActiveSource.Stored) keptImports.clear(left)
                    }
                }
        }
    }
}
