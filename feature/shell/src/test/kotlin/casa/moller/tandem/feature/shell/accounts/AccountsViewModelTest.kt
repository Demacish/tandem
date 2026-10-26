package casa.moller.tandem.feature.shell.accounts

import app.cash.turbine.test
import casa.moller.tandem.core.data.session.Session
import casa.moller.tandem.core.data.session.TokenRevocation
import casa.moller.tandem.core.model.account.AccountSummary
import casa.moller.tandem.core.model.account.Household
import casa.moller.tandem.core.model.account.SpaceRole
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.server.ServerCompatibility
import casa.moller.tandem.core.model.sync.SyncPause
import casa.moller.tandem.core.model.sync.SyncReason
import casa.moller.tandem.core.model.sync.SyncStatus
import casa.moller.tandem.core.testing.MainDispatcherRule
import casa.moller.tandem.core.testing.data.FakeAccountDirectory
import casa.moller.tandem.core.testing.data.FakeSyncRepository
import casa.moller.tandem.feature.shell.ACCOUNT
import casa.moller.tandem.feature.shell.FakeSessionRepository
import casa.moller.tandem.feature.shell.R
import casa.moller.tandem.feature.shell.ShellMessage
import casa.moller.tandem.feature.shell.ShellMessageQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
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
 * Phase 2 (konto lane): Mer → Konton och servrar. Switching and removing go through
 * [FakeAccountDirectory]; a removal never discards unsent changes the dialog has not shown.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val active = AccountSummary(
        ACCOUNT,
        isActive = true,
        household = Household(3, "Familjen Exempel"),
        role = SpaceRole.ADMIN,
        compatibility = ServerCompatibility.SUPPORTED,
    )
    private val guest = AccountSummary(
        ACCOUNT.copy(id = "account-2", baseUrl = "https://recept.exempel.se", displayName = null),
        isActive = false,
        role = SpaceRole.GUEST,
        pendingChanges = 2,
        compatibility = ServerCompatibility.SUPPORTED,
    )
    private val other = AccountSummary(ACCOUNT.copy(id = "account-3", baseUrl = "https://test.example.com"), isActive = false)

    private val directory = FakeAccountDirectory(listOf(active, guest, other))
    private val sessions = FakeSessionRepository()
    private val sync = FakeSyncRepository()
    private val messages = ShellMessageQueue()
    private val applicationScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())

    private fun TestScope.newViewModel(): AccountsViewModel {
        val vm = AccountsViewModel(directory, sessions, sync, messages, applicationScope)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        return vm
    }

    @Test
    fun showsTheActiveAccountAndTheOthers() = runTest {
        val state = newViewModel().uiState.value

        assertFalse(state.loading)
        assertEquals(ACCOUNT.id, state.active?.id)
        assertEquals("Familjen Exempel", state.active?.householdName)
        assertEquals(SpaceRole.ADMIN, state.active?.role)
        assertEquals(listOf("account-2", "account-3"), state.others.map { it.id })
        assertTrue(state.others.first().isGuest)
        assertEquals("recept.exempel.se", state.others.first().serverAddress)
        assertEquals(2, state.others.first().pendingChanges)
        assertTrue(state.canSendNow)
    }

    @Test
    fun demoModeIsNeverTheActiveAccount() = runTest {
        sessions.state.value = Session.Demo
        directory.accounts.update { list -> list.map { it.copy(isActive = false) } }

        val state = newViewModel().uiState.value

        assertTrue(state.isDemo)
        assertNull(state.active)
        assertEquals(3, state.others.size)
    }

    @Test
    fun sendFirstIsOfferedOnlyWhenASyncCouldSend() = runTest {
        sync.setStatus(SyncStatus(pause = SyncPause.OFFLINE))

        assertFalse(newViewModel().uiState.value.canSendNow)
    }

    @Test
    fun sendFirstIsNotOfferedInSafeMode() = runTest {
        directory.accounts.update { list ->
            list.map { if (it.isActive) it.copy(compatibility = ServerCompatibility.UNTESTED_NEWER) else it }
        }

        assertFalse(newViewModel().uiState.value.canSendNow)
    }

    @Test
    fun switchingMakesTheOtherAccountActiveAndSaysSo() = runTest {
        val vm = newViewModel()

        vm.onAction(AccountsAction.SwitchTo("account-3"))

        assertEquals(listOf("account-3"), directory.switches)
        assertEquals("account-3", vm.uiState.value.active?.id)
        assertEquals(ShellMessage.SWITCHED_ACCOUNT, messages.messages.first())
    }

    @Test
    fun aFailedSwitchSaysSo() = runTest {
        directory.failure = TandemError.Unknown("tandem.test")
        val vm = newViewModel()

        vm.onAction(AccountsAction.SwitchTo("account-3"))

        assertEquals(ShellMessage.SWITCH_FAILED, messages.messages.first())
        assertFalse(vm.uiState.value.busy)
    }

    @Test
    fun removingWithUnsentChangesWarnsAndOnlyDiscardsWhatWasShown() = runTest {
        val vm = newViewModel()

        vm.onAction(AccountsAction.RequestRemoval("account-2"))
        assertEquals(2, vm.uiState.value.removal?.warnedPending)
        assertTrue("nothing removed by opening the dialog", directory.removals.isEmpty())

        vm.onAction(AccountsAction.ConfirmRemoval(revokeToken = true))

        assertEquals(listOf(Triple("account-2", true, true)), directory.removals)
        assertNull(vm.uiState.value.removal)
        assertEquals(listOf(ACCOUNT.id, "account-3"), (listOfNotNull(vm.uiState.value.active) + vm.uiState.value.others).map { it.id })
        assertEquals(ShellMessage.ACCOUNT_REMOVED, messages.messages.first())
    }

    @Test
    fun changesThatArriveWhileTheDialogIsOpenAreShownBeforeAnythingIsRemoved() = runTest {
        val vm = newViewModel()
        vm.onAction(AccountsAction.RequestRemoval("account-2"))

        directory.accounts.update { list -> list.map { if (it.account.id == "account-2") it.copy(pendingChanges = 3) else it } }
        vm.onAction(AccountsAction.ConfirmRemoval(revokeToken = false))

        assertTrue("the new count is shown first", directory.removals.isEmpty())
        assertEquals(3, vm.uiState.value.removal?.warnedPending)

        vm.onAction(AccountsAction.ConfirmRemoval(revokeToken = false))

        assertEquals(listOf(Triple("account-2", false, true)), directory.removals)
    }

    @Test
    fun anAccountWithoutUnsentChangesIsRemovedWithoutDiscarding() = runTest {
        val vm = newViewModel()

        vm.onAction(AccountsAction.RequestRemoval("account-3"))
        assertEquals(0, vm.uiState.value.removal?.warnedPending)
        vm.onAction(AccountsAction.ConfirmRemoval(revokeToken = true))

        assertEquals(listOf(Triple("account-3", true, false)), directory.removals)
    }

    @Test
    fun loggingOutTheActiveAccountUsesTheLogoutMessages() = runTest {
        directory.revocation = TokenRevocation.FAILED
        val vm = newViewModel()

        vm.onAction(AccountsAction.RequestRemoval(ACCOUNT.id))
        vm.onAction(AccountsAction.ConfirmRemoval(revokeToken = true))

        assertEquals(ShellMessage.LOGGED_OUT_TOKEN_NOT_REVOKED, messages.messages.first())
        assertEquals("the first remaining account is active", "account-2", vm.uiState.value.active?.id)
    }

    @Test
    fun exportSharesTheAccountsUnsentChanges() = runTest {
        val vm = newViewModel()
        vm.onAction(AccountsAction.RequestRemoval("account-2"))

        vm.effect.test {
            vm.onAction(AccountsAction.ExportPending)
            val effect = awaitItem() as AccountsEffect.SharePending
            assertTrue(effect.json.contains("\"count\": 2"))
        }
        assertEquals(listOf("account-2"), directory.exports)
        assertEquals("the dialog stays open", "account-2", vm.uiState.value.removal?.account?.id)
    }

    @Test
    fun dismissingTheDialogRemovesNothing() = runTest {
        val vm = newViewModel()
        vm.onAction(AccountsAction.RequestRemoval("account-2"))

        vm.onAction(AccountsAction.DismissRemoval)

        assertNull(vm.uiState.value.removal)
        assertTrue(directory.removals.isEmpty())
    }

    @Test
    fun syncNowAsksForTheUsersOwnSync() = runTest {
        val vm = newViewModel()

        vm.effect.test {
            vm.onAction(AccountsAction.SyncNow)
            assertEquals(AccountsEffect.Message(R.string.accounts_sync_started), awaitItem())
        }
        assertEquals(listOf(SyncReason.USER_REFRESH), sync.requests)
    }

    @Test
    fun removalMessagesForAnotherAccount() {
        assertEquals(ShellMessage.ACCOUNT_REMOVED, AccountsViewModel.messageFor(TokenRevocation.REVOKED, wasActive = false))
        assertEquals(ShellMessage.ACCOUNT_REMOVED_PASTED_TOKEN, AccountsViewModel.messageFor(TokenRevocation.NOT_OWNED, wasActive = false))
        assertEquals(
            ShellMessage.ACCOUNT_REMOVED_TOKEN_NOT_REVOKED,
            AccountsViewModel.messageFor(TokenRevocation.FAILED, wasActive = false),
        )
        assertEquals(ShellMessage.LOGGED_OUT, AccountsViewModel.messageFor(TokenRevocation.SKIPPED, wasActive = true))
    }
}
