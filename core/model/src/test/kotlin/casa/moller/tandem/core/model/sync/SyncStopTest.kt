package casa.moller.tandem.core.model.sync

import casa.moller.tandem.core.model.error.TandemError
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SyncStopTest {

    @Test
    fun cancelledAndSourceChangedAreTransientStops() {
        assertTrue(SyncStop.Cancelled.isTransientSyncStop)
        assertTrue(SyncStop.SourceChanged.isTransientSyncStop)
        assertTrue(TandemError.Unknown("tandem.sync.cancelled").isTransientSyncStop)
        assertTrue(TandemError.Unknown("tandem.sync.source-changed").isTransientSyncStop)
    }

    @Test
    fun realFailuresAreNot() {
        assertFalse(TandemError.Unknown().isTransientSyncStop)
        assertFalse(TandemError.Unknown("java.io.IOException").isTransientSyncStop)
        assertFalse(TandemError.Decode("tandem.sync.cancelled").isTransientSyncStop)
        assertFalse(TandemError.Blocked.isTransientSyncStop)
        assertFalse(TandemError.ServerError(502).isTransientSyncStop)
    }
}
