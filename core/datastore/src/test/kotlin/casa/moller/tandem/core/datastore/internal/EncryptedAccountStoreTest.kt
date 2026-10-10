package casa.moller.tandem.core.datastore.internal

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import casa.moller.tandem.core.datastore.DatastoreFiles
import casa.moller.tandem.core.datastore.SecureStorageException
import casa.moller.tandem.core.datastore.StorageHealthMonitor
import casa.moller.tandem.core.datastore.StoreScopes
import casa.moller.tandem.core.datastore.TestAeadProvider
import casa.moller.tandem.core.datastore.crypto.AeadProvider
import casa.moller.tandem.core.model.account.ServerAccount
import casa.moller.tandem.core.model.account.TokenOrigin
import casa.moller.tandem.core.model.settings.RecoveryReason
import java.io.File
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EncryptedAccountStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scopes = StoreScopes()
    private val health = StorageHealthMonitor()
    private lateinit var file: File

    @Before
    fun setUp() {
        file = File(tmp.newFolder("datastore"), DatastoreFiles.SECURE_ACCOUNTS)
    }

    @After
    fun tearDown() = runBlocking { scopes.closeAll() }

    private fun open(
        keys: AeadProvider,
        keyRetryDelaysMillis: List<Long> = EncryptedDocumentSerializer.DEFAULT_KEY_RETRY_DELAYS_MILLIS,
    ): EncryptedAccountStore = EncryptedAccountStore(
        produceFile = { file },
        keys = keys,
        health = health,
        scope = scopes.newScope(),
        keyRetryDelaysMillis = keyRetryDelaysMillis,
    )

    @Test
    fun roundTripSurvivesReopen() = runTest {
        val keys = TestAeadProvider()
        val store = open(keys)
        store.upsert(FIRST, TOKEN_1)

        assertEquals(listOf(FIRST), store.accounts.first())
        assertEquals(TOKEN_1, store.token(FIRST.id))

        scopes.closeAll()
        val reopened = open(keys)
        assertEquals(listOf(FIRST), reopened.accounts.first())
        assertEquals(TOKEN_1, reopened.token(FIRST.id))
        assertFalse(health.health.value.recoveryNeeded)
    }

    @Test
    fun multipleAccountsKeepOrderAndSeparateTokens() = runTest {
        val store = open(TestAeadProvider())
        store.upsert(FIRST, TOKEN_1)
        store.upsert(SECOND, TOKEN_2)
        assertEquals(listOf(FIRST, SECOND), store.accounts.first())

        // Upsert of an existing id replaces it in place, token included.
        val renamed = FIRST.copy(spaceName = "Renamed")
        store.upsert(renamed, TOKEN_3)
        assertEquals(listOf(renamed, SECOND), store.accounts.first())
        assertEquals(TOKEN_3, store.token(FIRST.id))
        assertEquals(TOKEN_2, store.token(SECOND.id))

        store.remove(FIRST.id)
        assertEquals(listOf(SECOND), store.accounts.first())
        assertNull(store.token(FIRST.id))

        store.clearAll()
        assertEquals(emptyList<ServerAccount>(), store.accounts.first())
        assertNull(store.token(SECOND.id))
    }

    @Test
    fun updateChangesMetadataAndKeepsToken() = runTest {
        val store = open(TestAeadProvider())
        store.upsert(FIRST, TOKEN_1)

        val upgraded = FIRST.copy(serverVersion = "2.7.0")
        assertTrue(store.update(upgraded))
        assertEquals(listOf(upgraded), store.accounts.first())
        assertEquals(TOKEN_1, store.token(FIRST.id))

        assertFalse(store.update(SECOND))
        assertEquals(listOf(upgraded), store.accounts.first())
    }

    @Test
    fun blankTokenIsRejected() = runTest {
        val store = open(TestAeadProvider())
        val result = runCatching { store.upsert(FIRST, " ") }
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertEquals(emptyList<ServerAccount>(), store.accounts.first())
    }

    @Test
    fun fileOnDiskContainsNoPlaintext() = runTest {
        val store = open(TestAeadProvider())
        store.upsert(FIRST, TOKEN_1)
        store.accounts.first()

        val bytes = file.readBytes()
        assertTrue(bytes.isNotEmpty())
        val asText = String(bytes, Charsets.ISO_8859_1)
        for (secretOrPlain in listOf(TOKEN_1, "tda_", FIRST.baseUrl, FIRST.username, FIRST.id, "token")) {
            assertFalse("file contains '$secretOrPlain'", asText.contains(secretOrPlain))
        }
    }

    @Test
    fun corruptFileResetsToEmptyAndFlagsRecovery() = runTest {
        file.writeBytes(ByteArray(64) { it.toByte() })
        val store = open(TestAeadProvider())

        assertEquals(emptyList<ServerAccount>(), store.accounts.first())
        assertNull(store.token(FIRST.id))
        assertEquals(setOf(RecoveryReason.ACCOUNTS_UNREADABLE), health.health.value.recoveryReasons)

        // The store is usable again right away.
        store.upsert(FIRST, TOKEN_1)
        assertEquals(TOKEN_1, store.token(FIRST.id))
    }

    @Test
    fun wrongKeyResetsToEmptyAndFlagsRecovery() = runTest {
        open(TestAeadProvider()).upsert(FIRST, TOKEN_1)
        scopes.closeAll()

        val store = open(TestAeadProvider()) // a different keyset, as after a restore
        assertEquals(emptyList<ServerAccount>(), store.accounts.first())
        assertEquals(setOf(RecoveryReason.ACCOUNTS_UNREADABLE), health.health.value.recoveryReasons)

        health.acknowledge()
        assertFalse(health.health.value.recoveryNeeded)
    }

    @Test
    fun differentAssociatedDataIsRejected() = runTest {
        val keys = TestAeadProvider()
        open(keys).upsert(FIRST, TOKEN_1)
        scopes.closeAll()

        val store = EncryptedAccountStore(
            produceFile = { file },
            keys = keys,
            health = health,
            scope = scopes.newScope(),
            associatedData = "another-file".encodeToByteArray(),
        )
        assertEquals(emptyList<ServerAccount>(), store.accounts.first())
        assertEquals(setOf(RecoveryReason.ACCOUNTS_UNREADABLE), health.health.value.recoveryReasons)
    }

    @Test
    fun unusableKeyIsResetAndFlagged() = runTest {
        open(TestAeadProvider()).upsert(FIRST, TOKEN_1)
        scopes.closeAll()

        val keys = TestAeadProvider(failUntilReset = true)
        val store = open(keys)
        assertEquals(emptyList<ServerAccount>(), store.accounts.first())
        assertTrue(RecoveryReason.KEY_UNUSABLE in health.health.value.recoveryReasons)
        assertEquals(1, keys.resetCount)

        store.upsert(SECOND, TOKEN_2)
        assertEquals(listOf(SECOND), store.accounts.first())
        assertEquals(TOKEN_2, store.token(SECOND.id))
    }

    @Test
    fun keyResetDuringAFirstWriteIsNotARecovery() = runTest {
        // Fresh install: the first Keystore use (inside the login's write) fails, the reset and
        // retry work. The login succeeds, so the app must not jump to the recovery screen.
        val keys = TestAeadProvider(failUntilReset = true)
        val store = open(keys)

        store.upsert(FIRST, TOKEN_1)

        assertEquals(1, keys.resetCount)
        assertEquals(listOf(FIRST), store.accounts.first())
        assertFalse(health.health.value.recoveryNeeded)
    }

    @Test
    fun unavailableKeystoreNeverThrowsOnReadAndRefusesWrites() = runTest {
        val store = open(TestAeadProvider(alwaysFail = true))
        assertEquals(emptyList<ServerAccount>(), store.accounts.first())

        val result = runCatching { store.upsert(FIRST, TOKEN_1) }
        assertTrue(result.exceptionOrNull() is SecureStorageException)
        // A failed WRITE goes to the caller (the login step shows "could not be saved"); it does
        // not send the app to the recovery screen in the middle of a login.
        assertFalse(health.health.value.recoveryNeeded)
        assertTrue(!file.exists() || file.length() == 0L)
        assertEquals(emptyList<ServerAccount>(), store.accounts.first())
    }

    @Test
    fun transientKeystoreFailureIsRetriedAndNothingIsLost() = runTest {
        val keys = TestAeadProvider()
        open(keys).upsert(FIRST, TOKEN_1)
        scopes.closeAll()
        val before = file.readBytes()

        keys.transientFailures = 1
        val store = open(keys)

        assertEquals(listOf(FIRST), store.accounts.first())
        assertEquals(TOKEN_1, store.token(FIRST.id))
        assertEquals(0, keys.resetCount)
        assertFalse(health.health.value.recoveryNeeded)
        assertArrayEquals(before, file.readBytes())
    }

    @Test
    fun keystoreFailingLongerKeepsFileAndKeysAndReadsAgainLater() = runTest {
        val keys = TestAeadProvider()
        open(keys).upsert(FIRST, TOKEN_1)
        scopes.closeAll()
        val before = file.readBytes()

        // One read makes 2 attempts (one retry); the first read fails completely, the next works.
        keys.transientFailures = 2
        val store = open(keys, keyRetryDelaysMillis = listOf(1L))

        store.accounts.test {
            assertEquals(emptyList<ServerAccount>(), awaitItem())
            assertEquals(setOf(RecoveryReason.SECURE_STORAGE_UNAVAILABLE), health.health.value.recoveryReasons)
            // The same (long-lived) collector gets the accounts once the key is reachable again.
            assertEquals(listOf(FIRST), awaitItem())
            // Readable again, so the app leaves the recovery screen by itself.
            assertFalse(health.health.value.recoveryNeeded)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(0, keys.resetCount)
        assertArrayEquals(before, file.readBytes())
        assertEquals(TOKEN_1, store.token(FIRST.id))
    }

    @Test
    fun transientKeystoreFailureOnWriteNeverResetsTheKeys() = runTest {
        val keys = TestAeadProvider()
        keys.transientFailures = 10
        val store = open(keys, keyRetryDelaysMillis = listOf(1L))

        val result = runCatching { store.upsert(FIRST, TOKEN_1) }

        assertTrue(result.exceptionOrNull() is SecureStorageException)
        assertEquals(0, keys.resetCount)
        assertFalse(health.health.value.recoveryNeeded)
    }

    @Test
    fun tokenOriginIsStoredAndOldDocumentsReadAsPasted() = runTest {
        val store = open(TestAeadProvider())
        val created = FIRST.copy(tokenOrigin = TokenOrigin.CREATED)
        store.upsert(created, TOKEN_1)
        store.upsert(SECOND.copy(tokenOrigin = TokenOrigin.PASTED), TOKEN_2)
        assertEquals(
            listOf(TokenOrigin.CREATED, TokenOrigin.PASTED),
            store.accounts.first().map { it.tokenOrigin },
        )

        // Written before the field existed, or with a value this version does not know.
        val legacy = """{"id":"a","baseUrl":"https://x","username":"u","userId":1,"spaceId":1,""" +
            """"tokenId":5,"addedAt":"2026-10-09T06:00:00Z","token":"$TOKEN_3"}"""
        assertEquals(
            TokenOrigin.PASTED,
            SecureDocumentJson.decodeFromString(StoredAccount.serializer(), legacy).toModel().tokenOrigin,
        )
        val unknown = legacy.removeSuffix("}") + ""","tokenOrigin":"SOMETHING_NEW"}"""
        assertEquals(
            TokenOrigin.PASTED,
            SecureDocumentJson.decodeFromString(StoredAccount.serializer(), unknown).toModel().tokenOrigin,
        )
    }

    @Test
    fun unavailableKeystoreWithExistingFileNeverThrows() = runTest {
        open(TestAeadProvider()).upsert(FIRST, TOKEN_1)
        scopes.closeAll()

        val store = open(TestAeadProvider(alwaysFail = true))
        assertEquals(emptyList<ServerAccount>(), store.accounts.first())
        assertNull(store.token(FIRST.id))
        assertTrue(RecoveryReason.SECURE_STORAGE_UNAVAILABLE in health.health.value.recoveryReasons)
    }

    @Test
    fun storedAccountToStringRedactsToken() {
        val text = StoredAccount.from(FIRST, TOKEN_1).toString()
        assertFalse(text.contains(TOKEN_1))
        assertTrue(text.contains("<redacted>"))
        assertFalse(SecureDocument(accounts = listOf(StoredAccount.from(FIRST, TOKEN_1))).toString().contains(TOKEN_1))
    }

    private companion object {
        // Fake tokens in Tandoor's format (tda_ + UUID with underscores); not real credentials.
        const val TOKEN_1 = "tda_00000000_0000_4000_8000_000000000001"
        const val TOKEN_2 = "tda_00000000_0000_4000_8000_000000000002"
        const val TOKEN_3 = "tda_00000000_0000_4000_8000_000000000003"

        val FIRST = ServerAccount(
            id = "6f1c1d2e-0000-4000-8000-000000000001",
            baseUrl = "https://recipes.example.org",
            username = "cook",
            displayName = "Kitchen Cook",
            userId = 3,
            spaceId = 1,
            spaceName = "Home",
            tokenId = 12,
            tokenExpires = Instant.parse("2031-10-09T06:00:00.123456Z"),
            serverVersion = "2.6.13",
            addedAt = Instant.parse("2026-10-09T06:00:00Z"),
        )
        val SECOND = ServerAccount(
            id = "6f1c1d2e-0000-4000-8000-000000000002",
            baseUrl = "https://example.net/tandoor",
            username = "guest",
            userId = 9,
            spaceId = 2,
            addedAt = Instant.parse("2026-10-09T07:00:00Z"),
        )
    }
}
