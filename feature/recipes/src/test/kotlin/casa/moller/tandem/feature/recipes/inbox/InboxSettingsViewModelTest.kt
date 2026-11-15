package casa.moller.tandem.feature.recipes.inbox

import androidx.lifecycle.SavedStateHandle
import casa.moller.tandem.core.data.server.LocalNetworkAccess
import casa.moller.tandem.core.model.inbox.InboxProblem
import casa.moller.tandem.core.testing.MainDispatcherRule
import casa.moller.tandem.core.testing.data.FakeRecipeInboxRepository
import casa.moller.tandem.core.testing.data.SampleInbox
import casa.moller.tandem.feature.recipes.R
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Fas 5 (rss lane, plan §7 #35, §5.5): Mer → "Inkorg för recept": connecting checks the key first and
 * says why it failed, the key is never kept in the saved state, the categories are chosen and saved,
 * the switches go to the repository, and "Koppla från" asks first.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InboxSettingsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val inbox = FakeRecipeInboxRepository(initial = SampleInbox.notSetUp, posts = emptyList())
    private val handle = SavedStateHandle()

    private fun viewModel(localNetwork: LocalNetworkAccess = LocalNetworkAccess.NotNeeded) =
        InboxSettingsViewModel(handle, inbox, localNetwork)

    /** Fas 5 (sdk37 lane): Android 17's local network permission, scripted. */
    private class FakeLocalNetwork(private val localHosts: Set<String>, var granted: Boolean = false) : LocalNetworkAccess {
        override val permission: String = "android.permission.ACCESS_LOCAL_NETWORK"

        override fun permissionMissing(): Boolean = !granted

        override suspend fun needsPermission(serverAddress: String): Boolean = !granted && serverAddress in localHosts
    }

    private fun TestScope.collect(flow: Flow<*>) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect {} }
    }

    private fun <T> TestScope.effectsOf(flow: Flow<T>): List<T> {
        val effects = mutableListOf<T>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.toList(effects) }
        return effects
    }

    @Test
    fun aWrongKeyIsRefusedAndTheRightOneConnects() = runTest {
        val vm = viewModel()
        collect(vm.uiState)
        assertFalse(vm.uiState.value.canConnect)

        vm.onAction(InboxSettingsAction.SetAddress("https://rss.example.com/"))
        vm.onAction(InboxSettingsAction.SetApiKey("fel"))
        vm.onAction(InboxSettingsAction.Connect)
        assertEquals(InboxProblem.AUTH, vm.uiState.value.connectProblem)
        assertFalse(vm.uiState.value.settings.connected)

        vm.onAction(InboxSettingsAction.SetApiKey("miniflux-test-key"))
        assertNull("typing clears the problem", vm.uiState.value.connectProblem)
        vm.onAction(InboxSettingsAction.Connect)
        val state = vm.uiState.value
        assertTrue(state.settings.connected)
        assertEquals("", state.apiKey)
        assertEquals(listOf("Recept", "Nyheter", "Efterrätter"), state.categoryChoices.map { it.title })
        // The key never went into the saved state; the address did.
        assertTrue(handle.keys().none { handle.get<Any>(it) == "miniflux-test-key" })
        assertEquals("https://rss.example.com/", handle.get<String>("inbox_settings_address"))
    }

    @Test
    fun anAddressWithoutHttpsIsExplained() = runTest {
        val vm = viewModel()
        collect(vm.uiState)
        vm.onAction(InboxSettingsAction.SetAddress("http://rss.example.com"))
        vm.onAction(InboxSettingsAction.SetApiKey("miniflux-test-key"))
        vm.onAction(InboxSettingsAction.Connect)
        assertEquals(InboxProblem.INVALID_URL, vm.uiState.value.connectProblem)
    }

    @Test
    fun theCategoriesAreChosenAndSaved() = runTest {
        inbox.mutableState.value = SampleInbox.inUse
        val vm = viewModel()
        collect(vm.uiState)
        val effects = effectsOf(vm.effects)
        // Connected: the server's categories with their counts are loaded once.
        assertEquals(listOf("categories"), inbox.calls)
        assertEquals(setOf(2L, 4L), vm.uiState.value.selected)
        assertFalse(vm.uiState.value.selectionChanged)

        vm.onAction(InboxSettingsAction.ToggleCategory(4, chosen = false))
        vm.onAction(InboxSettingsAction.ToggleCategory(3, chosen = true))
        assertTrue(vm.uiState.value.selectionChanged)
        vm.onAction(InboxSettingsAction.SaveCategories)

        assertEquals("choose [2, 3]", inbox.calls.last())
        assertEquals(listOf(2L, 3L), vm.uiState.value.settings.categories.map { it.id })
        assertFalse(vm.uiState.value.selectionChanged)
        assertEquals(InboxSettingsEffect.Message(R.string.inbox_settings_categories_saved), effects.single())
    }

    @Test
    fun categoriesThatCannotBeLoadedCanBeRetried() = runTest {
        inbox.mutableState.value = SampleInbox.inUse
        inbox.categoriesFailure = InboxProblem.UNREACHABLE
        val vm = viewModel()
        collect(vm.uiState)
        assertEquals(InboxProblem.UNREACHABLE, vm.uiState.value.categoriesProblem)
        // The chosen ones are still shown.
        assertEquals(listOf("Recept", "Efterrätter"), vm.uiState.value.categoryChoices.map { it.title })

        inbox.categoriesFailure = null
        vm.onAction(InboxSettingsAction.RetryCategories)
        assertNull(vm.uiState.value.categoriesProblem)
        assertEquals(3, vm.uiState.value.categoryChoices.size)
    }

    @Test
    fun theSwitchesAndDisconnecting() = runTest {
        inbox.mutableState.value = SampleInbox.inUse
        val vm = viewModel()
        collect(vm.uiState)
        val effects = effectsOf(vm.effects)

        vm.onAction(InboxSettingsAction.SetMarkRead(false))
        vm.onAction(InboxSettingsAction.SetShowOnToday(false))
        assertFalse(vm.uiState.value.settings.markReadOnImport)
        assertFalse(vm.uiState.value.settings.showOnToday)

        vm.onAction(InboxSettingsAction.OpenInbox)
        assertEquals(InboxSettingsEffect.OpenInbox, effects.single())

        vm.onAction(InboxSettingsAction.AskDisconnect)
        assertTrue(vm.uiState.value.confirmDisconnect)
        vm.onAction(InboxSettingsAction.DismissDialog)
        assertFalse("disconnect" in inbox.calls)
        vm.onAction(InboxSettingsAction.AskDisconnect)
        vm.onAction(InboxSettingsAction.ConfirmDisconnect)
        assertTrue("disconnect" in inbox.calls)
        assertFalse(vm.uiState.value.settings.connected)
    }

    // --- Fas 5 (sdk37 lane): a Miniflux at home on Android 17 (docs/BYGGE.md "Target SDK 37"). ---

    @Test
    fun aMinifluxAtHomeAsksForTheLocalNetworkBeforeTheKeyIsSent() = runTest {
        val localNetwork = FakeLocalNetwork(localHosts = setOf("https://192.168.1.30/"))
        val vm = viewModel(localNetwork)
        collect(vm.uiState)
        val effects = effectsOf(vm.effects)
        vm.onAction(InboxSettingsAction.SetAddress("https://192.168.1.30/"))
        vm.onAction(InboxSettingsAction.SetApiKey("miniflux-test-key"))
        vm.onAction(InboxSettingsAction.Connect)
        assertEquals(InboxProblem.LOCAL_NETWORK, vm.uiState.value.connectProblem)
        assertTrue("nothing went to Miniflux", inbox.calls.isEmpty())

        vm.onAction(InboxSettingsAction.AllowLocalNetwork)
        assertEquals(listOf(InboxSettingsEffect.RequestLocalNetwork("android.permission.ACCESS_LOCAL_NETWORK")), effects)
        // Allowed: "Anslut" goes on by itself.
        localNetwork.granted = true
        vm.onAction(InboxSettingsAction.LocalNetworkResult(granted = true))
        assertNull(vm.uiState.value.connectProblem)
        assertTrue(vm.uiState.value.settings.connected)
    }

    @Test
    fun aRefusedLocalNetworkOffersAndroidsSettings() = runTest {
        val vm = viewModel(FakeLocalNetwork(localHosts = setOf("https://rss.local/")))
        collect(vm.uiState)
        val effects = effectsOf(vm.effects)
        vm.onAction(InboxSettingsAction.SetAddress("https://rss.local/"))
        vm.onAction(InboxSettingsAction.SetApiKey("miniflux-test-key"))
        vm.onAction(InboxSettingsAction.Connect)
        vm.onAction(InboxSettingsAction.LocalNetworkResult(granted = false))
        assertEquals(InboxProblem.LOCAL_NETWORK, vm.uiState.value.connectProblem)
        assertTrue(vm.uiState.value.localNetworkRefused)
        assertFalse(vm.uiState.value.settings.connected)
        vm.onAction(InboxSettingsAction.OpenAppSettings)
        assertEquals(listOf(InboxSettingsEffect.OpenAppSettings), effects)
    }
}
