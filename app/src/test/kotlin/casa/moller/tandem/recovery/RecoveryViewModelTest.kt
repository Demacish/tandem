package casa.moller.tandem.recovery

import app.cash.turbine.test
import casa.moller.tandem.core.data.sync.PendingChangesExporter
import casa.moller.tandem.core.model.error.Outcome
import java.util.Optional
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** "Export pending changes" on the recovery screen (feature #37). */
@OptIn(ExperimentalCoroutinesApi::class)
class RecoveryViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun withoutAnExporterTheExportIsUnavailable() = runTest {
        val viewModel = RecoveryViewModel(Optional.empty())
        viewModel.exportState.test {
            val state = expectMostRecentItem()
            assertFalse(state.available)
            assertFalse(state.canExport)
            cancelAndIgnoreRemainingEvents()
        }
        viewModel.effects.test {
            viewModel.exportPendingChanges()
            expectNoEvents()
        }
    }

    @Test
    fun pendingChangesAreCountedAndShared() = runTest {
        val exporter = FakeExporter()
        val viewModel = RecoveryViewModel(Optional.of(exporter))
        viewModel.exportState.test {
            assertEquals(PendingExportState(available = true, pendingChanges = 2), expectMostRecentItem())
            exporter.count.value = 0
            assertFalse(awaitItem().canExport)
            exporter.count.value = 3
            assertTrue(awaitItem().canExport)
            cancelAndIgnoreRemainingEvents()
        }
        viewModel.effects.test {
            viewModel.exportPendingChanges()
            assertEquals(RecoveryEffect.SharePendingChanges(FakeExporter.JSON), awaitItem())
        }
        assertEquals(1, exporter.exports)
    }

    @Test
    fun aBrokenOutboxNeverCrashesTheScreen() = runTest {
        val viewModel = RecoveryViewModel(Optional.of(BrokenExporter()))
        viewModel.exportState.test {
            val state = expectMostRecentItem()
            assertTrue(state.available)
            assertNull(state.pendingChanges)
            cancelAndIgnoreRemainingEvents()
        }
        viewModel.effects.test {
            viewModel.exportPendingChanges()
            assertEquals(RecoveryEffect.ExportFailed, awaitItem())
        }
    }

    private class FakeExporter : PendingChangesExporter {
        val count = MutableStateFlow(2)
        var exports = 0

        override fun observePendingCount(): Flow<Int> = count

        override suspend fun exportAsJson(): Outcome<String> {
            exports++
            return Outcome.Success(JSON)
        }

        companion object {
            const val JSON = """[{"type":"cook_log","recipe":1}]"""
        }
    }

    private class BrokenExporter : PendingChangesExporter {
        override fun observePendingCount(): Flow<Int> = flow<Int> { error("outbox unreadable") }

        override suspend fun exportAsJson(): Outcome<String> = error("outbox unreadable")
    }
}
