package casa.moller.tandem.core.data.internal

import app.cash.turbine.test
import casa.moller.tandem.core.data.DataProblem
import casa.moller.tandem.core.data.dataProblem
import casa.moller.tandem.core.data.server.ServerCompatibility
import casa.moller.tandem.core.data.session.Session
import casa.moller.tandem.core.data.session.TokenRevocation
import casa.moller.tandem.core.datastore.StorageHealthMonitor
import casa.moller.tandem.core.model.account.ServerAccount
import casa.moller.tandem.core.model.account.TokenOrigin
import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.error.UnauthorizedSource
import casa.moller.tandem.core.model.error.errorOrNull
import casa.moller.tandem.core.model.error.getOrNull
import casa.moller.tandem.core.model.settings.AppSettings
import casa.moller.tandem.core.model.settings.RecoveryReason
import casa.moller.tandem.core.model.settings.StorageHealth
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultAccountRepositoryTest {

    private val remote = FakeTandoorRemote()
    private val accounts = FakeSecureAccountStore()
    private val settings = FakeSettingsStore()
    private val health = StorageHealthMonitor()
    private var ids = 0
    private val repository = DefaultAccountRepository(
        remote = remote,
        accountStore = accounts,
        settingsStore = settings,
        health = health,
        now = { NOW },
        newId = { "account-${++ids}" },
    )

    @Test
    fun probeDelegatesToRemote() = runTest {
        val result = repository.probe("tandoor.example.com")
        assertEquals(SERVER, result.getOrNull())
        assertEquals(listOf("tandoor.example.com"), remote.probes)
    }

    @Test
    fun loginStoresAccountAndTokenAndMakesItActive() = runTest {
        val account = repository.login(SERVER, " anna ", "secret").getOrNull()!!

        assertEquals("account-1", account.id)
        assertEquals("https://tandoor.example.com", account.baseUrl)
        assertEquals(7L, account.userId)
        assertEquals(1L, account.spaceId)
        assertEquals("Home", account.spaceName)
        assertEquals(42L, account.tokenId)
        assertEquals("2.6.13", account.serverVersion)
        assertEquals(NOW, account.addedAt)
        assertEquals(TokenOrigin.CREATED, account.tokenOrigin)
        assertEquals(listOf("anna" to "secret"), remote.logins)
        assertEquals(mapOf("account-1" to "tda_device_token_0001"), accounts.tokens())
        assertEquals(
            AppSettings.Default.copy(activeAccountId = "account-1", onboardingCompleted = true),
            settings.state.value,
        )
        assertEquals(
            Session.LoggedIn(account, "2.6.13", ServerCompatibility.SUPPORTED),
            repository.session.first(),
        )
    }

    @Test
    fun loginSwitchesOffDemoMode() = runTest {
        settings.state.value = AppSettings.Default.copy(demoMode = true)
        repository.login(SERVER, "anna", "secret")
        assertFalse(settings.state.value.demoMode)
        assertTrue(repository.session.first() is Session.LoggedIn)
    }

    @Test
    fun wrongPasswordIsReturnedAndNothingIsStored() = runTest {
        val error = TandemError.BadRequest(mapOf(TandemError.BadRequest.NON_FIELD_ERRORS to listOf("x")))
        remote.loginResult = Outcome.Failure(error)

        assertEquals(error, repository.login(SERVER, "anna", "wrong").errorOrNull())
        assertTrue(accounts.tokens().isEmpty())
        assertEquals(Session.NoAccount, repository.session.first())
    }

    @Test
    fun throttledLoginIsNotRetried() = runTest {
        remote.loginResult = Outcome.Failure(TandemError.Throttled(retryAfter = 5.hours))

        val error = repository.login(SERVER, "anna", "secret").errorOrNull()

        assertEquals(TandemError.Throttled(5.hours), error)
        assertEquals(1, remote.logins.size)
    }

    @Test
    fun tooOldServerIsRefusedWithoutAnyRequest() = runTest {
        val old = SERVER.copy(version = "1.5.19", compatibility = ServerCompatibility.TOO_OLD)

        assertEquals(DataProblem.SERVER_TOO_OLD, repository.login(old, "anna", "secret").errorOrNull()?.dataProblem())
        assertEquals(DataProblem.SERVER_TOO_OLD, repository.loginWithToken(old, "tda_x").errorOrNull()?.dataProblem())
        assertTrue(remote.logins.isEmpty())
        assertTrue(remote.tokenLogins.isEmpty())
    }

    @Test
    fun untestedNewerServerCanLogIn() = runTest {
        val newer = SERVER.copy(version = "2.7.0", compatibility = ServerCompatibility.UNTESTED_NEWER)
        remote.loginResult = Outcome.Success(credentials(serverVersion = "2.7.0"))

        repository.login(newer, "anna", "secret")

        val session = repository.session.first() as Session.LoggedIn
        assertEquals(ServerCompatibility.UNTESTED_NEWER, session.compatibility)
        assertEquals("2.7.0", session.serverVersion)
    }

    @Test
    fun blankCredentialsAreRejectedLocally() = runTest {
        val error = repository.login(SERVER, "  ", "").errorOrNull() as TandemError.BadRequest
        assertEquals(setOf("username", "password"), error.fieldErrors.keys)
        assertTrue(repository.loginWithToken(SERVER, " ").errorOrNull() is TandemError.BadRequest)
        assertTrue(remote.logins.isEmpty())
        assertTrue(remote.tokenLogins.isEmpty())
    }

    @Test
    fun accountWithoutSpaceFailsAndTheNewTokenIsRevoked() = runTest {
        remote.loginResult = Outcome.Success(credentials(spaceId = null))

        val error = repository.login(SERVER, "anna", "secret").errorOrNull()

        assertEquals(DataProblem.NO_SPACE, error?.dataProblem())
        assertEquals(listOf(Triple(SERVER.baseUrl, "tda_device_token_0001", 42L)), remote.revocations)
        assertTrue(accounts.tokens().isEmpty())
    }

    @Test
    fun storageFailureRevokesTheTokenTheAppCreated() = runTest {
        accounts.failWrites = true

        val error = repository.login(SERVER, "anna", "secret").errorOrNull()

        assertEquals(DataProblem.SECURE_STORAGE, error?.dataProblem())
        assertEquals(1, remote.revocations.size)
        assertEquals(AppSettings.Default, settings.state.value)
    }

    @Test
    fun storageFailureNeverRevokesAPastedToken() = runTest {
        accounts.failWrites = true
        remote.tokenLoginResult = Outcome.Success(credentials(token = "tda_pasted", tokenId = 9))

        val error = repository.loginWithToken(SERVER, "tda_pasted").errorOrNull()

        assertEquals(DataProblem.SECURE_STORAGE, error?.dataProblem())
        assertTrue(remote.revocations.isEmpty())
    }

    @Test
    fun loginWithPastedTokenStoresAccountWithUnknownTokenId() = runTest {
        remote.tokenLoginResult = Outcome.Success(credentials(token = "tda_pasted", tokenId = null))

        val account = repository.loginWithToken(SERVER, "tda_pasted").getOrNull()!!

        assertNull(account.tokenId)
        assertEquals(TokenOrigin.PASTED, account.tokenOrigin)
        assertEquals(listOf("tda_pasted"), remote.tokenLogins)
        assertEquals("tda_pasted", accounts.tokens()[account.id])
    }

    @Test
    fun invalidPastedTokenIsReturned() = runTest {
        val error = TandemError.Unauthorized(UnauthorizedSource.TANDOOR)
        remote.tokenLoginResult = Outcome.Failure(error)
        assertEquals(error, repository.loginWithToken(SERVER, "tda_bad").errorOrNull())
        assertTrue(accounts.tokens().isEmpty())
    }

    @Test
    fun loggingInAgainReplacesTheSameAccount() = runTest {
        val first = repository.login(SERVER, "anna", "secret").getOrNull()!!
        remote.loginResult = Outcome.Success(credentials(token = "tda_device_token_0002", tokenId = 43))

        val second = repository.login(SERVER, "anna", "secret").getOrNull()!!

        assertEquals(first.id, second.id)
        assertEquals(first.addedAt, second.addedAt)
        assertEquals(43L, second.tokenId)
        assertEquals(mapOf(first.id to "tda_device_token_0002"), accounts.tokens())
        // The replaced device token is no longer used by anything: Tandem revokes its own token.
        assertEquals(listOf(Triple("https://tandoor.example.com", "tda_device_token_0001", 42L)), remote.revocations)
    }

    @Test
    fun replacingAPastedTokenNeverRevokesIt() = runTest {
        remote.tokenLoginResult = Outcome.Success(credentials(token = "tda_pasted", tokenId = 9))
        repository.loginWithToken(SERVER, "tda_pasted")

        repository.login(SERVER, "anna", "secret")

        assertTrue(remote.revocations.isEmpty())
    }

    @Test
    fun settingsWriteFailureRollsTheAccountBackBeforeRevoking() = runTest {
        settings.failWrites = true

        val error = repository.login(SERVER, "anna", "secret").errorOrNull()

        assertEquals(DataProblem.SECURE_STORAGE, error?.dataProblem())
        // Nothing on the device refers to the token any more, so it is revoked.
        assertTrue(accounts.tokens().isEmpty())
        assertEquals(listOf(Triple(SERVER.baseUrl, "tda_device_token_0001", 42L)), remote.revocations)
        assertEquals(Session.NoAccount, repository.session.first())
    }

    @Test
    fun settingsWriteFailureOnReloginRestoresThePreviousAccount() = runTest {
        val first = repository.login(SERVER, "anna", "secret").getOrNull()!!
        settings.failWrites = true
        remote.loginResult = Outcome.Success(credentials(token = "tda_device_token_0002", tokenId = 43))

        val error = repository.login(SERVER, "anna", "secret").errorOrNull()

        assertEquals(DataProblem.SECURE_STORAGE, error?.dataProblem())
        assertEquals(mapOf(first.id to "tda_device_token_0001"), accounts.tokens())
        assertEquals(42L, accounts.accounts.first().single().tokenId)
        // Only the new, never used token is revoked; the restored one stays valid.
        assertEquals(listOf(Triple(SERVER.baseUrl, "tda_device_token_0002", 43L)), remote.revocations)
        assertEquals(first, (repository.session.first() as Session.LoggedIn).account)
    }

    @Test
    fun failedRollbackKeepsTheStoredTokenValid() = runTest {
        settings.failWrites = true
        accounts.failRemoves = true

        val error = repository.login(SERVER, "anna", "secret").errorOrNull()

        assertEquals(DataProblem.SECURE_STORAGE, error?.dataProblem())
        assertEquals("tda_device_token_0001", accounts.tokens().values.single())
        assertTrue("a stored token is never revoked", remote.revocations.isEmpty())
    }

    @Test
    fun cleanUpRevokeFinishesWhenTheLoginScreenIsCancelled() = runTest {
        remote.loginResult = Outcome.Success(credentials(spaceId = null))
        val gate = CompletableDeferred<Unit>()
        remote.revokeGate = gate

        val job = launch { repository.login(SERVER, "anna", "secret") }
        runCurrent()
        assertEquals("the revoke has started", 1, remote.revocations.size)
        job.cancel() // e.g. the intro's ViewModel is cleared
        gate.complete(Unit)
        job.join()

        assertEquals(1, remote.completedRevocations)
    }

    @Test
    fun sessionIsLiveAndFollowsDemoMode() = runTest {
        repository.session.test {
            assertEquals(Session.NoAccount, awaitItem())
            assertEquals(Outcome.Success(Unit), repository.startDemo())
            assertEquals(Session.Demo, awaitItem())
            assertEquals(Outcome.Success(Unit), repository.leaveDemo())
            assertEquals(Session.NoAccount, awaitItem())
            repository.login(SERVER, "anna", "secret")
            assertTrue(awaitItem() is Session.LoggedIn)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun recoveryWinsOverEverythingAndCanBeAcknowledged() = runTest {
        repository.login(SERVER, "anna", "secret")
        health.report(RecoveryReason.ACCOUNTS_UNREADABLE)

        assertEquals(
            Session.RecoveryNeeded(setOf(RecoveryReason.ACCOUNTS_UNREADABLE)),
            repository.session.first(),
        )
        repository.acknowledgeRecovery()
        assertTrue(repository.session.first() is Session.LoggedIn)
    }

    @Test
    fun staleActiveAccountIdFallsBackToFirstAccount() {
        val account = account("a")
        val session = DefaultAccountRepository.resolveSession(
            AppSettings.Default.copy(activeAccountId = "gone"),
            listOf(account),
            StorageHealth.Healthy,
        )
        assertEquals(Session.LoggedIn(account, "2.6.13", ServerCompatibility.SUPPORTED), session)
        assertEquals(
            Session.NoAccount,
            DefaultAccountRepository.resolveSession(
                AppSettings.Default.copy(activeAccountId = "gone"),
                emptyList(),
                StorageHealth.Healthy,
            ),
        )
    }

    @Test
    fun activeAccountIsChosenById() {
        val a = account("a")
        val b = account("b")
        val session = DefaultAccountRepository.resolveSession(
            AppSettings.Default.copy(activeAccountId = "b"),
            listOf(a, b),
            StorageHealth.Healthy,
        )
        assertEquals(b, (session as Session.LoggedIn).account)
    }

    @Test
    fun unparseableStoredVersionIsUnrecognized() {
        val session = DefaultAccountRepository.resolveSession(
            AppSettings.Default,
            listOf(account("a").copy(serverVersion = "dev-build")),
            StorageHealth.Healthy,
        ) as Session.LoggedIn
        assertEquals(ServerCompatibility.UNRECOGNIZED, session.compatibility)
    }

    @Test
    fun logoutRevokesThisDevicesTokenAndRemovesTheAccount() = runTest {
        repository.login(SERVER, "anna", "secret")

        assertEquals(TokenRevocation.REVOKED, repository.logout(revokeToken = true).getOrNull())

        assertEquals(
            listOf(Triple("https://tandoor.example.com", "tda_device_token_0001", 42L)),
            remote.revocations,
        )
        assertTrue(accounts.tokens().isEmpty())
        assertNull(settings.state.value.activeAccountId)
        assertFalse(settings.state.value.onboardingCompleted)
        assertEquals(Session.NoAccount, repository.session.first())
    }

    @Test
    fun logoutWithoutRevocationLeavesTheServerAlone() = runTest {
        repository.login(SERVER, "anna", "secret")

        assertEquals(TokenRevocation.SKIPPED, repository.logout(revokeToken = false).getOrNull())

        assertTrue(remote.revocations.isEmpty())
        assertEquals(Session.NoAccount, repository.session.first())
    }

    @Test
    fun failedRevocationStillLogsOutLocally() = runTest {
        repository.login(SERVER, "anna", "secret")
        remote.revokeResult = Outcome.Failure(TandemError.Timeout)

        assertEquals(TokenRevocation.FAILED, repository.logout(revokeToken = true).getOrNull())
        assertEquals(Session.NoAccount, repository.session.first())
    }

    @Test
    fun pastedTokenWithoutIdIsNotRevoked() = runTest {
        repository.loginWithToken(SERVER, "tda_pasted")

        assertEquals(TokenRevocation.NOT_OWNED, repository.logout(revokeToken = true).getOrNull())
        assertTrue(remote.revocations.isEmpty())
    }

    @Test
    fun pastedTokenLogoutWithoutRevocationStillSaysTheTokenStaysValid() = runTest {
        // The logout dialog offers no revocation for a pasted token (revokeToken = false); the
        // release smoke test found that the user was then only told "Logged out.".
        repository.loginWithToken(SERVER, "tda_pasted")

        assertEquals(TokenRevocation.NOT_OWNED, repository.logout(revokeToken = false).getOrNull())
        assertTrue(remote.revocations.isEmpty())
        assertEquals(Session.NoAccount, repository.session.first())
    }

    @Test
    fun pastedTokenWithAMatchedIdIsNeverRevoked() = runTest {
        // loginWithPastedToken usually finds the id (exact or unique last-4 match, FACTS c).
        remote.tokenLoginResult = Outcome.Success(credentials(token = "tda_pasted", tokenId = 9))
        repository.loginWithToken(SERVER, "tda_pasted")
        assertEquals(TokenOrigin.PASTED, accounts.accounts.first().single().tokenOrigin)
        assertEquals(9L, accounts.accounts.first().single().tokenId)

        assertEquals(TokenRevocation.NOT_OWNED, repository.logout(revokeToken = true).getOrNull())

        assertTrue("the user's own token stays on the server", remote.revocations.isEmpty())
        assertTrue(accounts.tokens().isEmpty())
        assertEquals(Session.NoAccount, repository.session.first())
    }

    @Test
    fun failedLocalRemovalLogsNothingOutAndRevokesNothing() = runTest {
        repository.login(SERVER, "anna", "secret")
        accounts.failWrites = true

        val error = repository.logout(revokeToken = true).errorOrNull()

        assertEquals(DataProblem.SECURE_STORAGE, error?.dataProblem())
        assertTrue("the stored token must stay valid", remote.revocations.isEmpty())
        assertTrue(repository.session.first() is Session.LoggedIn)
    }

    @Test
    fun settingsFailureAfterTheRemovalStillLogsOutAndRevokes() = runTest {
        repository.login(SERVER, "anna", "secret")
        settings.failWrites = true

        assertEquals(TokenRevocation.REVOKED, repository.logout(revokeToken = true).getOrNull())

        assertTrue(accounts.tokens().isEmpty())
        assertEquals(1, remote.revocations.size)
        assertEquals("the stale active id falls back to no account", Session.NoAccount, repository.session.first())
    }

    @Test
    fun logoutRevokeFinishesWhenTheCallerIsCancelled() = runTest {
        repository.login(SERVER, "anna", "secret")
        val gate = CompletableDeferred<Unit>()
        remote.revokeGate = gate

        val job = launch { repository.logout(revokeToken = true) }
        runCurrent()
        assertTrue("removed locally before the request", accounts.tokens().isEmpty())
        job.cancel()
        gate.complete(Unit)
        job.join()

        assertEquals(1, remote.completedRevocations)
    }

    @Test
    fun logoutSwitchesToTheRemainingAccount() = runTest {
        repository.login(SERVER, "anna", "secret")
        remote.loginResult = Outcome.Success(credentials(baseUrl = "https://other.example.org/", userId = 3))
        val second = repository.login(SERVER, "bo", "secret").getOrNull()!!
        val first = accounts.accounts.first().first()

        repository.logout(revokeToken = false)

        assertEquals(first.id, settings.state.value.activeAccountId)
        assertTrue(settings.state.value.onboardingCompleted)
        assertFalse(accounts.tokens().containsKey(second.id))
    }

    @Test
    fun logoutWithoutAccountIsANoOp() = runTest {
        assertEquals(TokenRevocation.SKIPPED, repository.logout(revokeToken = true).getOrNull())
    }

    @Test
    fun refreshServerInfoStoresTheNewVersion() = runTest {
        repository.login(SERVER, "anna", "secret")
        remote.probeResult = Outcome.Success(SERVER.copy(version = "2.6.15"))

        assertEquals("2.6.15", repository.refreshServerInfo().getOrNull()?.version)

        assertEquals(listOf("https://tandoor.example.com"), remote.probes)
        assertEquals("2.6.15", (repository.session.first() as Session.LoggedIn).serverVersion)
    }

    @Test
    fun refreshServerInfoPassesErrorsOn() = runTest {
        repository.login(SERVER, "anna", "secret")
        remote.probeResult = Outcome.Failure(TandemError.UnknownHost)
        assertEquals(TandemError.UnknownHost, repository.refreshServerInfo().errorOrNull())
        assertEquals("2.6.13", (repository.session.first() as Session.LoggedIn).serverVersion)
    }

    @Test
    fun refreshServerInfoNeedsAnAccount() = runTest {
        assertEquals(DataProblem.NO_ACCOUNT, repository.refreshServerInfo().errorOrNull()?.dataProblem())
        assertTrue(remote.probes.isEmpty())
    }

    @Test
    fun clearAppDataRemovesEverythingAndAcknowledgesRecovery() = runTest {
        repository.login(SERVER, "anna", "secret")
        health.report(RecoveryReason.KEY_UNUSABLE)

        assertEquals(Outcome.Success(Unit), repository.clearAppData())

        assertTrue(accounts.tokens().isEmpty())
        assertEquals(AppSettings.Default, settings.state.value)
        assertEquals(Session.NoAccount, repository.session.first())
        assertTrue(remote.revocations.isEmpty())
    }

    @Test
    fun demoModeFailsCleanlyWhenSettingsCannotBeWritten() = runTest {
        settings.failWrites = true
        assertEquals(DataProblem.SECURE_STORAGE, repository.startDemo().errorOrNull()?.dataProblem())
        assertEquals(Session.NoAccount, repository.session.first())
    }

    private fun account(id: String) = ServerAccount(
        id = id,
        baseUrl = "https://tandoor.example.com",
        username = "anna",
        userId = 7,
        spaceId = 1,
        serverVersion = "2.6.13",
        addedAt = NOW,
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-10-09T12:00:00Z")
    }
}
