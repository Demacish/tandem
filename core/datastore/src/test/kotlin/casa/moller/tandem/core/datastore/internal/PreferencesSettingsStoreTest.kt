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
import casa.moller.tandem.core.model.settings.RecoveryReason
import casa.moller.tandem.core.model.settings.ThemeMode
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
}
