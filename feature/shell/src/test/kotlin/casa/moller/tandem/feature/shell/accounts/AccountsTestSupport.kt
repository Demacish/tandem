package casa.moller.tandem.feature.shell.accounts

import androidx.compose.runtime.Composable
import casa.moller.tandem.core.model.account.SpaceRole
import casa.moller.tandem.core.model.account.TokenOrigin
import casa.moller.tandem.core.model.server.ServerCompatibility

// Phase 2 (konto lane): fixed states for the accounts page's tests and screenshots. Only
// example.com/exempel.se addresses and made-up names.

internal val SAMPLE_ACTIVE = AccountItem(
    id = "account-1",
    displayName = "Anna Andersson",
    username = "anna",
    serverUrl = "https://tandoor.example.com",
    serverAddress = "tandoor.example.com",
    spaceName = "Hemma",
    householdName = "Familjen Exempel",
    role = SpaceRole.ADMIN,
    serverVersion = "2.6.13",
    compatibility = ServerCompatibility.SUPPORTED,
    tokenOrigin = TokenOrigin.CREATED,
    pendingChanges = 0,
    isActive = true,
)

/** A second server where Anna is a guest, with two unsent changes. */
internal val SAMPLE_GUEST = AccountItem(
    id = "account-2",
    displayName = "Anna",
    username = "anna",
    serverUrl = "https://recept.exempel.se/tandoor",
    serverAddress = "recept.exempel.se/tandoor",
    spaceName = "Receptklubben",
    householdName = null,
    role = SpaceRole.GUEST,
    serverVersion = "2.7.1",
    compatibility = ServerCompatibility.SUPPORTED,
    tokenOrigin = TokenOrigin.PASTED,
    pendingChanges = 2,
    isActive = false,
)

/** A test server on a version Tandem has not tested (safe mode). */
internal val SAMPLE_TEST_SERVER = AccountItem(
    id = "account-3",
    displayName = "Bo",
    username = "bo",
    serverUrl = "https://test.example.com",
    serverAddress = "test.example.com",
    spaceName = "Test",
    householdName = "Testhushållet",
    role = SpaceRole.USER,
    serverVersion = "2.8.0",
    compatibility = ServerCompatibility.UNTESTED_NEWER,
    tokenOrigin = TokenOrigin.CREATED,
    pendingChanges = 0,
    isActive = false,
)

internal val SAMPLE_ACCOUNTS = AccountsUiState(
    loading = false,
    active = SAMPLE_ACTIVE,
    others = listOf(SAMPLE_GUEST, SAMPLE_TEST_SERVER),
    canSendNow = true,
)

/** Only one account on the device. */
internal val SAMPLE_SINGLE = AccountsUiState(loading = false, active = SAMPLE_ACTIVE.copy(pendingChanges = 3), canSendNow = true)

/** Demo mode with two saved accounts: none is active. */
internal val SAMPLE_DEMO = AccountsUiState(
    loading = false,
    isDemo = true,
    others = listOf(SAMPLE_ACTIVE.copy(isActive = false), SAMPLE_GUEST),
)

/** "Remove account?" for the guest account with its two unsent changes. */
internal val SAMPLE_REMOVE_PENDING = SAMPLE_ACCOUNTS.copy(removal = RemovalPrompt(SAMPLE_GUEST))

/** "Log out?" for the active account with three unsent changes. */
internal val SAMPLE_LOGOUT_PENDING = SAMPLE_SINGLE.copy(removal = RemovalPrompt(SAMPLE_SINGLE.active!!))

/** The accounts page with a fixed state, for the shell's tests (no Hilt). */
internal class FixedAccountsPage(
    private val state: AccountsUiState = SAMPLE_ACCOUNTS,
    private val onAction: (AccountsAction) -> Unit = {},
) : AccountsPage {
    @Composable
    override fun Accounts(onNavigateBack: () -> Unit, onAddAccount: () -> Unit, onConnectTandoor: () -> Unit) {
        AccountsScreen(
            state = state,
            onAction = onAction,
            onAddAccount = onAddAccount,
            onConnectTandoor = onConnectTandoor,
            onNavigateBack = onNavigateBack,
        )
    }
}
