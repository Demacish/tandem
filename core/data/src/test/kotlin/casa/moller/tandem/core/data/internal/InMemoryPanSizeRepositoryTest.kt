package casa.moller.tandem.core.data.internal

import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.settings.AppSettings
import casa.moller.tandem.core.model.settings.HandsfreeSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Fas 5 (kok lane): the chosen tin per source and recipe; handsfree is stored with the settings. */
class InMemoryPanSizeRepositoryTest {

    private val store = FakeSettingsStore(AppSettings(activeAccountId = "a"))
    private val settings = DefaultSettingsRepository(store)
    private val pans = InMemoryPanSizeRepository(settings)

    @Test
    fun aChosenTinIsSharedUntilReset() = runTest {
        assertNull(pans.observePanSize(7).first())
        pans.setPanSize(7, 20)
        assertEquals(20, pans.observePanSize(7).first())
        assertNull(pans.observePanSize(8).first())
        pans.setPanSize(7, null)
        assertNull(pans.observePanSize(7).first())
        pans.setPanSize(7, 0)
        assertNull(pans.observePanSize(7).first())
    }

    @Test
    fun everySourceHasItsOwnTins() = runTest {
        pans.setPanSize(7, 20)
        store.state.value = store.state.value.copy(activeAccountId = "b")
        assertNull(pans.observePanSize(7).first())
        pans.setPanSize(7, 26)
        store.state.value = store.state.value.copy(demoMode = true)
        assertNull(pans.observePanSize(7).first())
        store.state.value = AppSettings(activeAccountId = "a")
        assertEquals(20, pans.observePanSize(7).first())
    }

    @Test
    fun handsfreeIsStored() = runTest {
        val chosen = HandsfreeSettings(enabled = true, voice = true, readSteps = true)
        assertEquals(Outcome.Success(Unit), settings.setHandsfree(chosen))
        assertEquals(chosen, settings.settings.first().handsfree)
        assertEquals(true, chosen.listens)
        assertEquals(false, chosen.copy(enabled = false).listens)
    }
}
