package casa.moller.tandem.core.sync.di

import casa.moller.tandem.core.data.shopping.StoreReminderRepository
import casa.moller.tandem.core.sync.platform.AndroidStoreReminderNotifier
import casa.moller.tandem.core.sync.platform.StoreReminderNotifier
import casa.moller.tandem.core.sync.storereminder.AndroidDeviceRegistrationKey
import casa.moller.tandem.core.sync.storereminder.AndroidStoreReminderPermissions
import casa.moller.tandem.core.sync.storereminder.ComponentSystemEventSwitch
import casa.moller.tandem.core.sync.storereminder.CurrentLocationGateway
import casa.moller.tandem.core.sync.storereminder.DefaultStoreReminderRepository
import casa.moller.tandem.core.sync.storereminder.DeviceRegistrationKey
import casa.moller.tandem.core.sync.storereminder.GeofenceGateway
import casa.moller.tandem.core.sync.storereminder.GooglePlayServicesCheck
import casa.moller.tandem.core.sync.storereminder.PlayCurrentLocationGateway
import casa.moller.tandem.core.sync.storereminder.PlayGeofenceGateway
import casa.moller.tandem.core.sync.storereminder.PlayServicesCheck
import casa.moller.tandem.core.sync.storereminder.StoreReminderPermissionReader
import casa.moller.tandem.core.sync.storereminder.SystemEventSwitch
import casa.moller.tandem.core.sync.work.StoreReminderWork
import casa.moller.tandem.core.sync.work.WorkManagerStoreReminderWork
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Fas 5 (butik lane, plan §7 #34): "Påminn mig nära butiken" (docs/BYGGE.md "Påminnelse nära butiken"). */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class StoreReminderModule {
    @Binds
    abstract fun bindStoreReminderRepository(impl: DefaultStoreReminderRepository): StoreReminderRepository

    @Binds
    abstract fun bindPlayServicesCheck(impl: GooglePlayServicesCheck): PlayServicesCheck

    @Binds
    abstract fun bindGeofenceGateway(impl: PlayGeofenceGateway): GeofenceGateway

    @Binds
    abstract fun bindCurrentLocationGateway(impl: PlayCurrentLocationGateway): CurrentLocationGateway

    @Binds
    abstract fun bindPermissionReader(impl: AndroidStoreReminderPermissions): StoreReminderPermissionReader

    @Binds
    abstract fun bindDeviceRegistrationKey(impl: AndroidDeviceRegistrationKey): DeviceRegistrationKey

    @Binds
    abstract fun bindSystemEventSwitch(impl: ComponentSystemEventSwitch): SystemEventSwitch

    @Binds
    abstract fun bindStoreReminderNotifier(impl: AndroidStoreReminderNotifier): StoreReminderNotifier

    @Binds
    abstract fun bindStoreReminderWork(impl: WorkManagerStoreReminderWork): StoreReminderWork
}
