package casa.moller.tandem.core.datastore.internal

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import casa.moller.tandem.core.datastore.DatastoreFiles
import casa.moller.tandem.core.datastore.StorageHealthMonitor
import casa.moller.tandem.core.datastore.StoreScopes
import casa.moller.tandem.core.model.settings.AppSettings
import casa.moller.tandem.core.model.settings.PendingVersionNotice
import casa.moller.tandem.core.model.settings.RecoveryReason
import casa.moller.tandem.core.model.settings.SyncInterval
import casa.moller.tandem.core.model.settings.ThemeMode
import casa.moller.tandem.core.model.timer.TimerMode
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PreferencesSettingsStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scopes = StoreScopes()
    private val health = StorageHealthMonitor()
    private lateinit var file: File

    @Before
    fun setUp() {
        file = File(tmp.newFolder("datastore"), DatastoreFiles.SETTINGS)
    }

    @After
    fun tearDown() = runBlocking { scopes.closeAll() }

    private fun open(): PreferencesSettingsStore =
        PreferencesSettingsStore.create(produceFile = { file }, health = health, scope = scopes.newScope())

    @Test
    fun defaultsWithoutFile() = runTest {
        assertEquals(AppSettings.Default, open().settings.first())
        assertFalse(health.health.value.recoveryNeeded)
    }

    @Test
    fun updatesArePersisted() = runTest {
        val store = open()
        store.setThemeMode(ThemeMode.DARK)
        store.setOnboardingCompleted(true)
        store.setDemoMode(true)
        store.setActiveAccountId("account-1")
        val expected = AppSettings(
            themeMode = ThemeMode.DARK,
            onboardingCompleted = true,
            demoMode = true,
            activeAccountId = "account-1",
        )
        assertEquals(expected, store.settings.first())

        scopes.closeAll()
        assertEquals(expected, open().settings.first())
    }

    @Test
    fun updateTransformsCurrentValueAndClearsActiveAccount() = runTest {
        val store = open()
        store.setActiveAccountId("account-1")
        store.update { it.copy(themeMode = ThemeMode.LIGHT, activeAccountId = null) }

        val settings = store.settings.first()
        assertEquals(ThemeMode.LIGHT, settings.themeMode)
        assertNull(settings.activeAccountId)
    }

    @Test
    fun aPendingVersionNoticeSurvivesARestartUntilItIsCleared() = runTest {
        val notice = PendingVersionNotice(accountId = "account-1", previousVersion = "2.6.13", newVersion = "2.6.15")
        open().update { it.copy(versionNotice = notice) }

        // Process death: a new store on the same file still has the notice ...
        scopes.closeAll()
        val reopened = open()
        assertEquals(notice, reopened.settings.first().versionNotice)

        // ... and after it was shown (cleared) it stays gone, also after the next restart.
        reopened.update { it.copy(versionNotice = null) }
        scopes.closeAll()
        assertNull(open().settings.first().versionNotice)
    }

    @Test
    fun corruptFileResetsToDefaultsAndFlagsRecovery() = runTest {
        file.writeBytes(ByteArray(64) { 0xFF.toByte() })
        val store = open()

        assertEquals(AppSettings.Default, store.settings.first())
        assertEquals(setOf(RecoveryReason.SETTINGS_UNREADABLE), health.health.value.recoveryReasons)

        store.setDemoMode(true)
        assertEquals(true, store.settings.first().demoMode)
    }

    @Test
    fun readErrorFallsBackToDefaultsAndRecovers() = runTest {
        val real = PreferenceDataStoreFactory.create(scope = scopes.newScope(), produceFile = { file })
        real.edit { it[PreferencesSettingsStore.Keys.DEMO_MODE] = true }
        val store = PreferencesSettingsStore(FailingOnceDataStore(real))

        store.settings.test {
            assertEquals(AppSettings.Default, awaitItem())
            // The same collector sees the stored values (and later changes) after the failure.
            assertEquals(true, awaitItem().demoMode)
            store.setThemeMode(ThemeMode.DARK)
            assertEquals(ThemeMode.DARK, awaitItem().themeMode)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** The first collection of [data] fails with an I/O error (not corruption), later ones work. */
    private class FailingOnceDataStore(private val real: DataStore<Preferences>) : DataStore<Preferences> {
        @Volatile
        private var failed = false

        override val data: Flow<Preferences> = flow {
            if (!failed) {
                failed = true
                throw IOException("test: settings could not be read")
            }
            emitAll(real.data)
        }

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = real.updateData(transform)
    }

    @Test
    fun unknownThemeValueFallsBackToSystem() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(scope = scopes.newScope(), produceFile = { file })
        dataStore.edit { it[PreferencesSettingsStore.Keys.THEME_MODE] = "PURPLE" }

        assertEquals(ThemeMode.SYSTEM, PreferencesSettingsStore(dataStore).settings.first().themeMode)
    }

    // --- Phase 1b (grund lane) ---

    @Test
    fun phase1bSettingsSurviveARestart() = runTest {
        val expected = AppSettings(
            themeMode = ThemeMode.DARK,
            dynamicColor = true,
            cookTextScale = 1.75f,
            cookModeAlwaysDark = true,
            timerMode = TimerMode.CLOCK_APP,
            syncInterval = SyncInterval.SIX_HOURS,
            downloadAllRecipes = true,
            notificationPermissionAsked = true,
            exactAlarmPromptDismissed = true,
        )
        open().update { expected }

        scopes.closeAll()
        assertEquals(expected, open().settings.first())
    }

    @Test
    fun unknownOrOutOfRangePhase1bValuesFallBack() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(scope = scopes.newScope(), produceFile = { file })
        dataStore.edit {
            it[PreferencesSettingsStore.Keys.TIMER_MODE] = "SUNDIAL"
            it[PreferencesSettingsStore.Keys.SYNC_INTERVAL] = "EVERY_SECOND"
            it[PreferencesSettingsStore.Keys.COOK_TEXT_SCALE] = 40f
        }

        val settings = PreferencesSettingsStore(dataStore).settings.first()
        assertEquals(TimerMode.IN_APP, settings.timerMode)
        assertEquals(SyncInterval.FIFTEEN_MINUTES, settings.syncInterval)
        assertEquals(2.0f, settings.cookTextScale)
    }
}
