package casa.moller.tandem.core.datastore.di

import android.content.Context
import casa.moller.tandem.core.datastore.AiKeyStore
import casa.moller.tandem.core.datastore.AiPreferencesStore
import casa.moller.tandem.core.datastore.DatastoreFiles
import casa.moller.tandem.core.datastore.DiagnosticsStore
import casa.moller.tandem.core.datastore.SecureAccountStore
import casa.moller.tandem.core.datastore.SettingsStore
import casa.moller.tandem.core.datastore.StorageHealthMonitor
import casa.moller.tandem.core.datastore.crypto.AeadProvider
import casa.moller.tandem.core.datastore.crypto.AndroidKeystoreAeadProvider
import casa.moller.tandem.core.datastore.internal.EncryptedAccountStore
import casa.moller.tandem.core.datastore.internal.EncryptedAiKeyStore
import casa.moller.tandem.core.datastore.internal.PreferencesAiPreferencesStore
import casa.moller.tandem.core.datastore.internal.PreferencesDiagnosticsStore
import casa.moller.tandem.core.datastore.internal.PreferencesSettingsStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Both stores are process-wide singletons: DataStore allows only one active instance per file.
 * Files are created lazily on first access, not when the graph is built.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatastoreModule {
    @Provides
    @Singleton
    fun provideStorageHealthMonitor(): StorageHealthMonitor = StorageHealthMonitor()

    @Provides
    @Singleton
    fun provideAeadProvider(@ApplicationContext context: Context): AeadProvider = AndroidKeystoreAeadProvider(context)

    @Provides
    @Singleton
    fun provideSecureAccountStore(
        @ApplicationContext context: Context,
        keys: AeadProvider,
        health: StorageHealthMonitor,
    ): SecureAccountStore = EncryptedAccountStore(
        produceFile = { DatastoreFiles.secureAccountsFile(context) },
        keys = keys,
        health = health,
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    )

    @Provides
    @Singleton
    fun provideSettingsStore(@ApplicationContext context: Context, health: StorageHealthMonitor): SettingsStore =
        PreferencesSettingsStore.create(
            produceFile = { DatastoreFiles.settingsFile(context) },
            health = health,
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        )

    // --- Phase 1b (inställningar lane): the measured timings for Settings → Diagnostics. ---
    @Provides
    @Singleton
    fun provideDiagnosticsStore(@ApplicationContext context: Context): DiagnosticsStore = PreferencesDiagnosticsStore.create(
        produceFile = { DatastoreFiles.diagnosticsFile(context) },
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    )

    // --- Fas S (ai-foto lane, B22): the user's AI keys (encrypted, never returned as a value) and
    // the non-secret AI settings, both per device and excluded from backup. ---
    @Provides
    @Singleton
    fun provideAiKeyStore(@ApplicationContext context: Context, keys: AeadProvider): AiKeyStore = EncryptedAiKeyStore(
        produceFile = { DatastoreFiles.secureAiKeysFile(context) },
        keys = keys,
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    )

    @Provides
    @Singleton
    fun provideAiPreferencesStore(@ApplicationContext context: Context): AiPreferencesStore = PreferencesAiPreferencesStore.create(
        produceFile = { DatastoreFiles.aiSettingsFile(context) },
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    )
}
