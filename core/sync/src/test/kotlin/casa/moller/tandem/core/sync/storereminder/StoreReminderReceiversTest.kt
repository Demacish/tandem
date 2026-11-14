package casa.moller.tandem.core.sync.storereminder

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import casa.moller.tandem.core.datastore.StoreReminderRecord
import casa.moller.tandem.core.domain.storereminder.StoreTransition
import casa.moller.tandem.core.model.shopping.Supermarket
import casa.moller.tandem.core.sync.storereminder.StoreReminderSamples.ACCOUNT
import casa.moller.tandem.core.sync.storereminder.StoreReminderSamples.STORE
import casa.moller.tandem.core.sync.storereminder.StoreReminderSamples.entry
import casa.moller.tandem.core.sync.testing.FakeNetwork
import casa.moller.tandem.core.sync.testing.FakeSources
import casa.moller.tandem.core.sync.work.StoreReminderJob
import casa.moller.tandem.core.sync.work.StoreReminderWork
import casa.moller.tandem.core.sync.work.StoreReminderWorker
import casa.moller.tandem.core.sync.work.WorkManagerStoreReminderWork
import casa.moller.tandem.core.testing.data.FakeShoppingRepository
import casa.moller.tandem.core.testing.data.FakeSupermarketRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fas 5 (butik lane, plan §7 #34): a geofence broadcast becomes the worker's input at once (with
 * the store and the time it came), the location being dropped or a reboot / app update / location
 * toggle becomes a registration, anything else is ignored; the worker's job runs the check (also
 * the release smoke test's input, which names the same keys) and registers again when asked; the
 * system receiver is switched on only while a geofence is wanted.
 */
@RunWith(AndroidJUnit4::class)
class StoreReminderReceiversTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private class RecordingWork : StoreReminderWork {
        val calls = mutableListOf<String>()

        override fun transition(storeIds: List<Long>, transition: StoreTransition, atMillis: Long) {
            calls += "$transition $storeIds"
        }

        override fun register() {
            calls += "register"
        }

        override fun lost() {
            calls += "lost"
        }
    }

    private val work = RecordingWork()

    /** The input each StoreReminderWorker run got (Hilt's factory is not in this test). */
    private val inputs = mutableListOf<Data>()

    private class Done(context: Context, params: WorkerParameters) : Worker(context, params) {
        override fun doWork(): Result = Result.success()
    }

    private val factory = object : WorkerFactory() {
        override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? {
            if (workerClassName != StoreReminderWorker::class.java.name) return null
            inputs += workerParameters.inputData
            return Done(appContext, workerParameters)
        }
    }

    @Before
    fun setUp() {
        val config = Configuration.Builder().setExecutor(SynchronousExecutor()).setWorkerFactory(factory).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        StoreGeofenceReceiver.workFactory = { work }
    }

    @After
    fun tearDown() {
        StoreGeofenceReceiver.reader = PlayGeofenceEventReader
        StoreGeofenceReceiver.workFactory = { WorkManagerStoreReminderWork(it) }
    }

    @Test
    fun aGeofenceBroadcastBecomesWorkAtOnce() {
        val events = ArrayDeque(
            listOf(
                GeofenceEvent.Transition(listOf(STORE), StoreTransition.ENTER),
                GeofenceEvent.Transition(listOf(STORE, 2L), StoreTransition.EXIT),
                GeofenceEvent.NotAvailable,
                GeofenceEvent.Ignored,
            ),
        )
        StoreGeofenceReceiver.reader = GeofenceEventReader { events.removeFirst() }
        repeat(4) { StoreGeofenceReceiver().onReceive(context, Intent()) }
        assertEquals(listOf("ENTER [1]", "EXIT [1, 2]", "lost"), work.calls)
    }

    @Test
    fun aRealIntentWithoutAnEventIsIgnored() {
        StoreGeofenceReceiver().onReceive(context, Intent())
        assertTrue(work.calls.isEmpty())
    }

    @Test
    fun theSystemEventsRegisterAgainAndNothingElseDoes() {
        val receiver = StoreReminderSystemReceiver()
        for (action in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, LocationManager.PROVIDERS_CHANGED_ACTION)) {
            receiver.onReceive(context, Intent(action))
        }
        receiver.onReceive(context, Intent("casa.example.SOMETHING_ELSE"))
        assertEquals(listOf("register", "register", "register"), work.calls)
    }

    @Test
    fun theRealWorkIsExpeditedOneTimeWorkWithTheEvent() {
        val real = WorkManagerStoreReminderWork(context)
        real.transition(listOf(STORE), StoreTransition.ENTER, 1_234L)
        val info = WorkManager.getInstance(context).getWorkInfosByTag(StoreReminderWork.TAG).get().single()
        assertEquals(WorkInfo.State.SUCCEEDED, info.state)
        val input = inputs.single()
        assertEquals(StoreReminderWork.MODE_TRANSITION, input.getString(StoreReminderWork.KEY_MODE))
        assertEquals(listOf(STORE), input.getLongArray(StoreReminderWork.KEY_STORES)?.toList())
        assertEquals("ENTER", input.getString(StoreReminderWork.KEY_TRANSITION))
        assertEquals(1_234L, input.getLong(StoreReminderWork.KEY_AT, 0L))
    }

    @Test
    fun theJobRunsTheCheckWithTheSmokeTestsKeysAndRegistersWhenAsked() = runBlocking {
        val sources = FakeSources(StoreReminderSamples.remote())
        val store = FakeStoreReminderStore()
        store.update(ACCOUNT) { StoreReminderRecord(enabled = true, spots = listOf(StoreReminderSamples.spot)) }
        val notifier = RecordingStoreNotifier()
        val check = StoreReminderCheck(
            sources,
            store,
            FakeShoppingRepository(entries = listOf(entry(1, "mjölk"), entry(2, "bröd"))),
            FakeSupermarketRepository(supermarkets = listOf(Supermarket(STORE, "Testbutiken"))),
            notifier,
            FakeNetwork(online = false),
        )
        val gateway = FakeGeofenceGateway()
        val registrar = StoreReminderRegistrar(
            sources,
            store,
            gateway,
            FakePermissionReader(),
            FakePlayServices(),
            FakeDeviceKey(),
            FakeSystemEventSwitch(),
        )
        val job = StoreReminderJob(check, registrar)

        // The same keys the minified build's SmokeWorkerReceiver writes.
        val enter = Data.Builder()
            .putString("mode", "transition")
            .putLongArray("stores", longArrayOf(STORE))
            .putString("transition", "ENTER")
            .putLong("at", 1_000L)
            .build()
        assertEquals(mapOf(STORE to StoreReminderOutcome.POSTED), job.run(enter))
        assertEquals(mapOf(STORE to StoreReminderOutcome.SAME_VISIT), job.run(enter))
        assertEquals(listOf(Triple(STORE, "Testbutiken", 2)), notifier.posted)

        assertEquals(emptyMap<Long, StoreReminderOutcome>(), job.run(Data.Builder().putString("mode", "register").build()))
        assertEquals(listOf(STORE), gateway.registered.map { it.storeId })
        // The class name the smoke test's receiver starts (kept by consumer-rules.pro, package work).
        assertEquals("casa.moller.tandem.core.sync.work.StoreReminderWorker", StoreReminderWorker::class.java.name)
    }

    @Test
    fun theSystemReceiverIsOnOnlyWhileAGeofenceIsWanted() {
        val switch = ComponentSystemEventSwitch(context)
        val component = ComponentName(context, StoreReminderSystemReceiver::class.java)
        switch.setEnabled(true)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, context.packageManager.getComponentEnabledSetting(component))
        switch.setEnabled(false)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, context.packageManager.getComponentEnabledSetting(component))
    }
}
