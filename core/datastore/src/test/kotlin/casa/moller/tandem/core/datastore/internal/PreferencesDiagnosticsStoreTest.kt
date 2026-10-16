package casa.moller.tandem.core.datastore.internal

import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.core.datastore.DatastoreFiles
import casa.moller.tandem.core.datastore.DiagnosticsTimings
import casa.moller.tandem.core.datastore.StoreScopes
import casa.moller.tandem.core.model.diagnostics.ColdStartTiming
import casa.moller.tandem.core.model.diagnostics.FullSyncTiming
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PreferencesDiagnosticsStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scopes = StoreScopes()
    private lateinit var file: File

    @Before
    fun setUp() {
        file = File(tmp.newFolder("datastore"), DatastoreFiles.DIAGNOSTICS)
    }

    @After
    fun tearDown() = runBlocking { scopes.closeAll() }

    private fun open(): PreferencesDiagnosticsStore = PreferencesDiagnosticsStore.create(produceFile = { file }, scope = scopes.newScope())

    @Test
    fun nothingMeasuredWithoutFile() = runTest {
        assertEquals(DiagnosticsTimings.None, open().timings.first())
    }

    @Test
    fun timingsSurviveARestart() = runTest {
        val cold = ColdStartTiming(843.milliseconds, Instant.parse("2026-10-16T18:00:00Z"))
        val sync = FullSyncTiming(41.5.seconds, 312, Instant.parse("2026-10-16T18:05:00Z"))
        open().apply {
            recordColdStart(cold)
            recordFullSync(sync)
        }
        scopes.closeAll()

        assertEquals(DiagnosticsTimings(cold, sync), open().timings.first())
    }

    @Test
    fun theLatestMeasurementReplacesTheOlderOne() = runTest {
        val store = open()
        store.recordColdStart(ColdStartTiming(2.seconds, Instant.parse("2026-10-16T18:00:00Z")))
        val newer = ColdStartTiming(900.milliseconds, Instant.parse("2026-10-17T07:00:00Z"))
        store.recordColdStart(newer)

        assertEquals(newer, store.timings.first().lastColdStart)
        assertEquals(null, store.timings.first().lastFullSync)
    }

    @Test
    fun aCorruptFileReadsAsNothingMeasured() = runTest {
        file.writeBytes(byteArrayOf(0x7f, 0x01, 0x02, 0x03))
        assertEquals(DiagnosticsTimings.None, open().timings.first())
    }
}
