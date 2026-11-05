package casa.moller.tandem.core.sync.di

import casa.moller.tandem.core.data.push.FastPushRepository
import casa.moller.tandem.core.sync.platform.AccountNotifications
import casa.moller.tandem.core.sync.platform.AndroidAccountNotifications
import casa.moller.tandem.core.sync.push.AndroidFastPushNotifier
import casa.moller.tandem.core.sync.push.DefaultFastPushRepository
import casa.moller.tandem.core.sync.push.FastPushNotifier
import casa.moller.tandem.core.sync.push.FastPushReceiver
import casa.moller.tandem.core.sync.push.PushDistributorGateway
import casa.moller.tandem.core.sync.push.PushWakeScheduler
import casa.moller.tandem.core.sync.push.PushWakeWait
import casa.moller.tandem.core.sync.push.UnifiedPushGateway
import casa.moller.tandem.core.sync.push.WorkManagerPushWakeScheduler
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay

/**
 * Fas 4 (push lane): the optional fast push (docs/BYGGE.md "Push-tjänst"): the repository, the
 * UnifiedPush distributors, the wake-up's work and the test notification.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class FastPushModule {
    @Binds
    abstract fun bindFastPushRepository(impl: DefaultFastPushRepository): FastPushRepository

    @Binds
    abstract fun bindFastPushReceiver(impl: DefaultFastPushRepository): FastPushReceiver

    @Binds
    abstract fun bindPushDistributorGateway(impl: UnifiedPushGateway): PushDistributorGateway

    @Binds
    abstract fun bindPushWakeScheduler(impl: WorkManagerPushWakeScheduler): PushWakeScheduler

    @Binds
    abstract fun bindFastPushNotifier(impl: AndroidFastPushNotifier): FastPushNotifier

    // Fas 4 (integration): the account's notifications go with the account.
    @Binds
    abstract fun bindAccountNotifications(impl: AndroidAccountNotifications): AccountNotifications

    companion object {
        /** ShoppingChangeNotifications' 3 s batch plus a margin. */
        @Provides
        fun providePushWakeWait(): PushWakeWait = PushWakeWait { delay(4.seconds) }
    }
}
