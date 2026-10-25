package casa.moller.tandem.core.network.auth

import casa.moller.tandem.core.model.error.Outcome
import casa.moller.tandem.core.model.error.TandemError
import casa.moller.tandem.core.model.error.UnauthorizedSource
import casa.moller.tandem.core.network.api.TandoorApiFactory
import casa.moller.tandem.core.network.dto.AccessTokenDto
import casa.moller.tandem.core.network.dto.ServerSettingsDto
import casa.moller.tandem.core.network.error.ErrorClassifier
import casa.moller.tandem.core.network.probe.Compatibility
import casa.moller.tandem.core.network.probe.TandoorServer
import casa.moller.tandem.core.network.testing.Fixture
import casa.moller.tandem.core.network.testing.HttpsServerRule
import casa.moller.tandem.core.network.testing.emptyResponse
import casa.moller.tandem.core.network.testing.jsonResponse
import casa.moller.tandem.core.network.testing.testClient
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TandoorAuthenticatorTest {
    @get:Rule
    val tandoor = HttpsServerRule()

    private val now: Instant = Instant.parse("2026-10-09T06:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val authenticator = TandoorAuthenticator(TandoorApiFactory(testClient()), clock, ErrorClassifier(clock))

    private val loginToken = "tda_00000000_0000_0000_0000_000000000001"
    private val deviceToken = "tda_00000000_0000_0000_0000_000000000002"

    /** `expires` of a token that api-token-auth created just now (now + 1825 days). */
    private val freshLoginExpiry = "2031-10-08T06:00:05.123456Z"
    private val oldLoginExpiry = "2030-01-01T00:00:00.000000Z"

    private fun server(path: String = "/", version: String = "2.6.13") = TandoorServer(
        baseUrl = tandoor.url(path),
        version = version,
        compatibility = Compatibility.SUPPORTED,
        settings = ServerSettingsDto(version = version),
    )

    private fun login(expires: String): MockResponse =
        Fixture.load("03-login-json").toMockResponse(mapOf("expires" to JsonPrimitive(expires)))

    private fun deviceTokenCreated(): MockResponse = Fixture.load("08-access-token-create")
        .toMockResponse(mapOf("expires" to JsonPrimitive("2027-10-09T08:00:00+02:00")))

    private fun RecordedRequest.bodyJson(): JsonObject = Json.parseToJsonElement(body!!.utf8()).jsonObject

    private fun RecordedRequest.line() = "$method ${url.encodedPath}"

    @Test
    fun passwordLoginCreatesADeviceTokenAndKeepsTheLoginTokenByDefault() = runBlocking {
        tandoor.enqueue(
            login(freshLoginExpiry),
            deviceTokenCreated(),
            Fixture.load("13-space-current").toMockResponse(),
            Fixture.load("12-user-preference-list").toMockResponse(),
        )

        val result = authenticator.login(server(), "anna", "hunter2")

        val credentials = (result as Outcome.Success).value
        assertEquals(
            DeviceCredentials(
                baseUrl = tandoor.url("/"),
                token = deviceToken,
                tokenId = 2,
                expires = Instant.parse("2027-10-09T06:00:00Z"),
                userId = 1,
                username = "contract-admin",
                displayName = "contract-admin",
                spaceId = 1,
                spaceName = "Contract Space",
                serverVersion = "2.6.13",
            ),
            credentials,
        )
        assertFalse(credentials.toString().contains("tda_"))

        // 1. api-token-auth: JSON, no token.
        val auth = tandoor.takeRequest()
        assertEquals("POST /api-token-auth/", auth.line())
        assertNull(auth.headers["Authorization"])
        assertEquals(JsonPrimitive("anna"), auth.bodyJson()["username"])
        assertEquals(JsonPrimitive("hunter2"), auth.bodyJson()["password"])
        // 2. device token, created with the login token, scope "read write", expires today + 1 year.
        val create = tandoor.takeRequest()
        assertEquals("POST /api/access-token/", create.line())
        assertEquals("Bearer $loginToken", create.headers["Authorization"])
        assertEquals(
            JsonObject(mapOf("scope" to JsonPrimitive("read write"), "expires" to JsonPrimitive("2027-10-09T06:00:00Z"))),
            create.bodyJson(),
        )
        // 3. everything after that with the device token only; nothing is deleted (KEEP).
        val space = tandoor.takeRequest()
        assertEquals("GET /api/space/current/", space.line())
        assertEquals("Bearer $deviceToken", space.headers["Authorization"])
        val user = tandoor.takeRequest()
        assertEquals("GET /api/user-preference/", user.line())
        assertEquals("Bearer $deviceToken", user.headers["Authorization"])
        assertEquals(4, tandoor.server.requestCount)
    }

    @Test
    fun deleteIfFreshDeletesALoginTokenCreatedByThisLogin() = runBlocking {
        tandoor.enqueue(
            login(freshLoginExpiry),
            deviceTokenCreated(),
            Fixture.load("30-revoke-login-token").toMockResponse(),
            Fixture.load("13-space-current").toMockResponse(),
            Fixture.load("12-user-preference-list").toMockResponse(),
        )

        val result = authenticator.login(server(), "anna", "hunter2", LoginTokenPolicy.DELETE_IF_FRESH)

        assertEquals(deviceToken, (result as Outcome.Success).value.token)
        val lines = List(5) { tandoor.takeRequest() }
        assertEquals(
            listOf(
                "POST /api-token-auth/",
                "POST /api/access-token/",
                "DELETE /api/access-token/1/",
                "GET /api/space/current/",
                "GET /api/user-preference/",
            ),
            lines.map { it.line() },
        )
        // The login token is deleted with the device token, never with itself.
        assertEquals("Bearer $deviceToken", lines[2].headers["Authorization"])
    }

    @Test
    fun deleteIfFreshKeepsAnOlderSharedLoginToken() = runBlocking {
        tandoor.enqueue(
            login(oldLoginExpiry),
            deviceTokenCreated(),
            Fixture.load("13-space-current").toMockResponse(),
            Fixture.load("12-user-preference-list").toMockResponse(),
        )

        val result = authenticator.login(server(), "anna", "hunter2", LoginTokenPolicy.DELETE_IF_FRESH)

        assertTrue(result is Outcome.Success)
        val lines = List(4) { tandoor.takeRequest().line() }
        assertFalse(lines.any { it.startsWith("DELETE") })
        assertEquals(4, tandoor.server.requestCount)
    }

    @Test
    fun freshnessWindow() {
        assertTrue(authenticator.isFreshLoginToken(freshLoginExpiry, now))
        assertTrue(authenticator.isFreshLoginToken("2031-10-09T07:59:00+02:00", now)) // plusYears(5), other offset
        assertFalse(authenticator.isFreshLoginToken("2031-10-07T06:00:00Z", now)) // created a day ago
        assertFalse(authenticator.isFreshLoginToken(oldLoginExpiry, now))
        assertFalse(authenticator.isFreshLoginToken(null, now))
        assertFalse(authenticator.isFreshLoginToken("garbage", now))
    }

    @Test
    fun wrongPasswordIsFinalAndNotRetried() = runBlocking {
        tandoor.enqueue(Fixture.load("06-login-wrong-password").toMockResponse(), login(oldLoginExpiry))

        val result = authenticator.login(server(), "anna", "wrong")

        val error = (result as Outcome.Failure).error as TandemError.BadRequest
        assertEquals(listOf("Unable to log in with provided credentials."), error.nonFieldErrors)
        assertEquals(1, tandoor.server.requestCount)
    }

    @Test
    fun throttlingIsFinalAndNotRetried() = runBlocking {
        tandoor.enqueue(Fixture.load("37-login-throttled").toMockResponse(), login(oldLoginExpiry))

        val result = authenticator.login(server(), "anna", "hunter2")

        assertEquals(Outcome.Failure(TandemError.Throttled(86380.seconds)), result)
        assertEquals(1, tandoor.server.requestCount)
    }

    @Test
    fun serverErrorOnLoginIsNotRetriedEither() = runBlocking {
        tandoor.enqueue(emptyResponse(503, "Retry-After" to "5"), login(oldLoginExpiry))

        assertEquals(Outcome.Failure(TandemError.ServerError(503)), authenticator.login(server(), "anna", "hunter2"))
        assertEquals(1, tandoor.server.requestCount)
    }

    @Test
    fun failedDeviceTokenCreationStopsTheLogin() = runBlocking {
        tandoor.enqueue(login(oldLoginExpiry), Fixture.load("16-invalid-token-missing-post").toMockResponse())

        val result = authenticator.login(server(), "anna", "hunter2")

        assertEquals(Outcome.Failure(TandemError.Unauthorized(UnauthorizedSource.TANDOOR)), result)
        assertEquals(2, tandoor.server.requestCount)
    }

    @Test
    fun maskedDeviceTokenIsADecodeError() = runBlocking {
        tandoor.enqueue(
            login(oldLoginExpiry),
            Fixture.load("08-access-token-create")
                .toMockResponse(mapOf("token" to JsonPrimitive("tda_************_******_***********0002"))),
        )

        val result = authenticator.login(server(), "anna", "hunter2")

        assertTrue((result as Outcome.Failure).error is TandemError.Decode)
    }

    @Test
    fun failureAfterCreatingTheDeviceTokenRevokesItAgain() = runBlocking {
        tandoor.enqueue(
            login(oldLoginExpiry),
            deviceTokenCreated(),
            emptyResponse(500),
            Fixture.load("34-revoke-device-token").toMockResponse(),
        )

        val result = authenticator.login(server(), "anna", "hunter2")

        assertEquals(Outcome.Failure(TandemError.ServerError(500)), result)
        val lines = List(4) { tandoor.takeRequest() }
        assertEquals("DELETE /api/access-token/2/", lines[3].line())
        assertEquals("Bearer $deviceToken", lines[3].headers["Authorization"])
    }

    @Test
    fun subPathInstall() = runBlocking {
        tandoor.enqueue(
            login(oldLoginExpiry),
            deviceTokenCreated(),
            Fixture.load("13-space-current").toMockResponse(),
            Fixture.load("12-user-preference-list").toMockResponse(),
        )

        val credentials = (authenticator.login(server("/tandoor/"), "anna", "hunter2") as Outcome.Success).value

        assertEquals(tandoor.url("/tandoor/"), credentials.baseUrl)
        assertEquals(
            listOf(
                "/tandoor/api-token-auth/",
                "/tandoor/api/access-token/",
                "/tandoor/api/space/current/",
                "/tandoor/api/user-preference/",
            ),
            List(4) { tandoor.takeRequest().url.encodedPath },
        )
    }

    // --- Pasted token ---

    @Test
    fun pastedTokenIsValidatedAndMatchedByItsLastFourCharacters() = runBlocking {
        tandoor.enqueue(
            Fixture.load("13-space-current").toMockResponse(),
            Fixture.load("12-user-preference-list").toMockResponse(),
            Fixture.load("10-access-token-list-after-16s").toMockResponse(),
        )

        val result = authenticator.loginWithPastedToken(server(), "  Bearer $deviceToken \n")

        val credentials = (result as Outcome.Success).value
        assertEquals(deviceToken, credentials.token)
        assertEquals(2L, credentials.tokenId)
        assertEquals(Instant.parse("1999-12-31T22:00:00Z"), credentials.expires) // fixture date, +02:00
        assertEquals(1L, credentials.userId)
        assertEquals("contract-admin", credentials.username)
        assertEquals(1L, credentials.spaceId)
        assertEquals("Contract Space", credentials.spaceName)
        assertEquals(
            listOf("GET /api/space/current/", "GET /api/user-preference/", "GET /api/access-token/"),
            List(3) { tandoor.takeRequest().also { r -> assertEquals("Bearer $deviceToken", r.headers["Authorization"]) }.line() },
        )
    }

    @Test
    fun ambiguousPastedTokenHasUnknownExpiry() = runBlocking {
        val list = Fixture.load("10-access-token-list-after-16s")
        val twoWithSameSuffix = JsonArray(
            (list.body as JsonArray) + JsonObject(
                (list.body as JsonArray)[1].jsonObject + mapOf<String, JsonElement>("id" to JsonPrimitive(9)),
            ),
        )
        tandoor.enqueue(
            Fixture.load("13-space-current").toMockResponse(),
            Fixture.load("12-user-preference-list").toMockResponse(),
            jsonResponse(200, twoWithSameSuffix.toString()),
        )

        val credentials = (authenticator.loginWithPastedToken(server(), deviceToken) as Outcome.Success).value

        assertNull(credentials.tokenId)
        assertNull(credentials.expires)
    }

    @Test
    fun invalidPastedTokenIsUnauthorized() = runBlocking {
        // The space read cannot tell an invalid token from an account without a space; the token
        // list (no space needed) can.
        tandoor.enqueue(
            Fixture.load("17-invalid-token-unknownWellFormed-get").toMockResponse(),
            Fixture.load("17-invalid-token-unknownWellFormed-get").toMockResponse(),
        )

        val result = authenticator.loginWithPastedToken(server(), "tda_00000000_0000_0000_0000_00000000dead")

        assertEquals(Outcome.Failure(TandemError.Unauthorized(UnauthorizedSource.TANDOOR)), result)
        assertEquals(
            listOf("GET /api/space/current/", "GET /api/access-token/"),
            List(2) { tandoor.takeRequest().line() },
        )
    }

    @Test
    fun blankPastedTokenSendsNothing() = runBlocking {
        val result = authenticator.loginWithPastedToken(server(), "  ")
        assertTrue((result as Outcome.Failure).error is TandemError.BadRequest)
        assertEquals(0, tandoor.server.requestCount)
    }

    @Test
    fun tokenMatching() {
        val masked = listOf(
            AccessTokenDto(id = 1, token = "tda_************_******_***********0001"),
            AccessTokenDto(id = 2, token = "tda_************_******_***********0002"),
        )
        assertEquals(2L, TandoorAuthenticator.matchToken(masked, deviceToken)?.id)
        assertNull(TandoorAuthenticator.matchToken(masked, "tda_00000000_0000_0000_0000_000000000003"))
        assertNull(TandoorAuthenticator.matchToken(masked, "0002"))
        val plain = listOf(AccessTokenDto(id = 7, token = deviceToken), AccessTokenDto(id = 8, token = "tda_x_0002"))
        assertEquals(7L, TandoorAuthenticator.matchToken(plain, deviceToken)?.id)
        assertEquals("tda_abc", TandoorAuthenticator.cleanPastedToken(" \"Bearer tda_abc\" "))
    }

    // --- Revocation ---

    @Test
    fun revokeDeletesOnlyTheDeviceTokenWithItself() = runBlocking {
        tandoor.enqueue(Fixture.load("34-revoke-device-token").toMockResponse())

        val result = authenticator.revokeDeviceToken(tandoor.url("/"), deviceToken, 2)

        assertEquals(Outcome.Success(RevokeResult.REVOKED), result)
        val request = tandoor.takeRequest()
        assertEquals("DELETE /api/access-token/2/", request.line())
        assertEquals("Bearer $deviceToken", request.headers["Authorization"])
    }

    @Test
    fun revokingAnAlreadyInvalidTokenIsNotAnError() = runBlocking {
        tandoor.enqueue(
            Fixture.load("35-revoked-device-token-get").toMockResponse(),
            Fixture.load("28-not-found-access-token").toMockResponse(),
            emptyResponse(403),
        )
        assertEquals(Outcome.Success(RevokeResult.ALREADY_INVALID), authenticator.revokeDeviceToken(tandoor.url("/"), deviceToken, 2))
        assertEquals(Outcome.Success(RevokeResult.ALREADY_INVALID), authenticator.revokeDeviceToken(tandoor.url("/"), deviceToken, 2))
        // A firewall block is not "revoked": the caller keeps the token and may try again later.
        assertEquals(Outcome.Failure(TandemError.Blocked), authenticator.revokeDeviceToken(tandoor.url("/"), deviceToken, 2))
    }

    @Test
    fun revokeBehindAProxyThatAnswers401IsAFailureNotAlreadyInvalid() = runBlocking {
        // SSO forward-auth in front of Tandoor: the DELETE never reached Tandoor (FACTS e:
        // Tandoor itself never sends 401), so the token may still be valid.
        tandoor.enqueue(
            emptyResponse(401, "WWW-Authenticate" to "Basic realm=\"sso\""),
            jsonResponse(401, """{"error":"unauthorized"}"""),
        )
        val proxy = Outcome.Failure(TandemError.Unauthorized(UnauthorizedSource.PROXY))
        assertEquals(proxy, authenticator.revokeDeviceToken(tandoor.url("/"), deviceToken, 2))
        assertEquals(proxy, authenticator.revokeDeviceToken(tandoor.url("/"), deviceToken, 2))
    }

    // --- Account without a space ---

    private fun refusedByTandoor(): MockResponse = jsonResponse(403, """{"detail":"You do not have permission to perform this action."}""")

    @Test
    fun passwordLoginToAnAccountWithoutASpaceKeepsTheTokenAndReportsNoSpace() = runBlocking {
        // FACTS h: login and token work, the space read is refused with 403 + JSON, and
        // user-preference would answer 500, so it is not asked.
        tandoor.enqueue(login(oldLoginExpiry), deviceTokenCreated(), refusedByTandoor(), emptyResponse(500))

        val credentials = (authenticator.login(server(), "anna", "hunter2") as Outcome.Success).value

        assertNull(credentials.spaceId)
        assertNull(credentials.spaceName)
        assertEquals(2L, credentials.tokenId)
        assertEquals(1L, credentials.userId)
        assertEquals("anna", credentials.username)
        // The token is valid; the data layer decides what to do with it (it revokes it).
        assertEquals(
            listOf("POST /api-token-auth/", "POST /api/access-token/", "GET /api/space/current/"),
            List(3) { tandoor.takeRequest().line() },
        )
        assertEquals(3, tandoor.server.requestCount)
    }

    @Test
    fun aFailedUserReadOnlyCostsTheDisplayName() = runBlocking {
        tandoor.enqueue(
            login(oldLoginExpiry),
            deviceTokenCreated(),
            Fixture.load("13-space-current").toMockResponse(),
            emptyResponse(500),
        )

        val credentials = (authenticator.login(server(), "anna", "hunter2") as Outcome.Success).value

        assertEquals(1L, credentials.spaceId)
        assertEquals(1L, credentials.userId)
        assertEquals("anna", credentials.username)
        assertEquals("anna", credentials.displayName)
        assertEquals(4, tandoor.server.requestCount)
    }

    @Test
    fun passwordLoginWithAnEmptyPreferenceListUsesTheLoginData() = runBlocking {
        tandoor.enqueue(
            login(oldLoginExpiry),
            deviceTokenCreated(),
            Fixture.load("13-space-current").toMockResponse(),
            jsonResponse(200, "[]"),
        )

        val credentials = (authenticator.login(server(), "anna", "hunter2") as Outcome.Success).value

        assertEquals(1L, credentials.userId)
        assertEquals("anna", credentials.username)
        assertEquals(1L, credentials.spaceId)
    }

    @Test
    fun pastedTokenOfAnAccountWithoutASpace() = runBlocking {
        tandoor.enqueue(refusedByTandoor(), Fixture.load("10-access-token-list-after-16s").toMockResponse())

        val credentials = (authenticator.loginWithPastedToken(server(), deviceToken) as Outcome.Success).value

        assertNull(credentials.spaceId)
        assertEquals(2L, credentials.tokenId)
        assertEquals(TandoorAuthenticator.UNKNOWN_USER_ID, credentials.userId)
        assertEquals(
            listOf("GET /api/space/current/", "GET /api/access-token/"),
            List(2) { tandoor.takeRequest().line() },
        )
    }

    // --- Pasted token clean-up ---

    @Test
    fun pastedTokenIsCleanedOfQuotesInvisibleCharactersAndSurroundingText() {
        val clean = TandoorAuthenticator::cleanPastedToken
        assertEquals("tda_x", clean("\u201Ctda_x\u201D"))
        assertEquals("tda_x", clean("\u200Btda_x\uFEFF"))
        assertEquals("tda_x", clean("\u00ABtda_x\u00BB"))
        assertEquals("tda_ab_12", clean("Token f\u00F6r Tandem: tda_ab_12"))
        assertEquals("tda_ab_12", clean("Bearer tda_ab_12\n"))
        assertEquals("something-else", clean(" 'something-else' "))
    }

    @Test
    fun pastedTokenThatCannotBeAHeaderIsRefusedWithoutAnyRequest() = runBlocking {
        for (bad in listOf("t\u00F6ken", "abc\ndef", "abc def", "\u201C\u201D")) {
            val result = authenticator.loginWithPastedToken(server(), bad)
            assertTrue(bad, (result as Outcome.Failure).error is TandemError.BadRequest)
        }
        assertEquals(0, tandoor.server.requestCount)
    }

    // --- Tandoor 2.7+: allauth headless ---

    private val session = "s3ss10nt0k3nfr0m4llauth0000000001"

    private fun allauthLoggedIn(): MockResponse = jsonResponse(
        200,
        """{"status":200,"data":{"user":{"id":1,"display":"contract-admin","has_usable_password":true,""" +
            """"email":"contract@example.invalid","username":"contract-admin"},"methods":[{"method":"password",""" +
            """"at":1761400000.0,"username":"contract-admin"}]},"meta":{"is_authenticated":true,"session_token":"$session"}}""",
    )

    /** allauth's answer to a logout: 401, the now unauthenticated state. */
    private fun allauthLoggedOut(): MockResponse = jsonResponse(
        401,
        """{"status":401,"data":{"flows":[{"id":"login"},{"id":"signup"}]},"meta":{"is_authenticated":false}}""",
    )

    private fun refused(): MockResponse =
        jsonResponse(403, """{"detail":"Authentication credentials were not provided or access is forbidden."}""")

    @Test
    fun tandoor27LogsInWithAllauthCreatesTheDeviceTokenWithTheSessionAndLogsOut() = runBlocking {
        tandoor.enqueue(
            allauthLoggedIn(),
            deviceTokenCreated(),
            allauthLoggedOut(),
            Fixture.load("13-space-current").toMockResponse(),
            Fixture.load("12-user-preference-list").toMockResponse(),
        )

        val result = authenticator.login(server(version = "2.7.1"), "anna", "hunter2")

        val credentials = (result as Outcome.Success).value
        assertEquals(deviceToken, credentials.token)
        assertEquals(2L, credentials.tokenId)
        assertEquals(1L, credentials.userId)
        assertEquals(1L, credentials.spaceId)
        assertEquals("2.7.1", credentials.serverVersion)
        assertFalse(credentials.toString().contains(session))

        // 1. allauth's app login: JSON, no token, no session yet.
        val login = tandoor.takeRequest()
        assertEquals("POST /_allauth/app/v1/auth/login", login.line())
        assertNull(login.headers["Authorization"])
        assertNull(login.headers["X-Session-Token"])
        assertEquals(JsonPrimitive("anna"), login.bodyJson()["username"])
        assertEquals(JsonPrimitive("hunter2"), login.bodyJson()["password"])
        // 2. the device token, created with the session (no Authorization header at all).
        val create = tandoor.takeRequest()
        assertEquals("POST /api/access-token/", create.line())
        assertEquals(session, create.headers["X-Session-Token"])
        assertNull(create.headers["Authorization"])
        assertEquals(JsonPrimitive("read write"), create.bodyJson()["scope"])
        // 3. the session is ended before the device token is used anywhere.
        val logout = tandoor.takeRequest()
        assertEquals("DELETE /_allauth/app/v1/auth/session", logout.line())
        assertEquals(session, logout.headers["X-Session-Token"])
        assertNull(logout.headers["Authorization"])
        // 4. everything else with the device token only.
        for (expected in listOf("GET /api/space/current/", "GET /api/user-preference/")) {
            val request = tandoor.takeRequest()
            assertEquals(expected, request.line())
            assertEquals("Bearer $deviceToken", request.headers["Authorization"])
            assertNull(request.headers["X-Session-Token"])
        }
        assertEquals(5, tandoor.server.requestCount)
    }

    @Test
    fun tandoor27WrongPasswordIsABadRequestAndNothingElseIsSent() = runBlocking {
        tandoor.enqueue(
            jsonResponse(
                400,
                """{"status":400,"errors":[{"message":"The username and/or password you specified are not correct.",""" +
                    """"code":"username_password_mismatch","param":"password"}]}""",
            ),
        )

        val result = authenticator.login(server(version = "2.7.1"), "anna", "wrong")

        assertTrue((result as Outcome.Failure).error is TandemError.BadRequest)
        assertEquals(1, tandoor.server.requestCount)
    }

    @Test
    fun tandoor27ThrottledLogin() = runBlocking {
        tandoor.enqueue(jsonResponse(429, """{"status":429}"""))

        val result = authenticator.login(server(version = "2.7.1"), "anna", "hunter2")

        assertTrue((result as Outcome.Failure).error is TandemError.Throttled)
        assertEquals(1, tandoor.server.requestCount)
    }

    @Test
    fun tandoor27LoginThatNeedsAnotherStepMeansPasteAToken() = runBlocking {
        // allauth: the password is right but a flow is pending (here: verify the e-mail address).
        tandoor.enqueue(
            jsonResponse(
                401,
                """{"status":401,"data":{"flows":[{"id":"login"},{"id":"verify_email","is_pending":true}]},""" +
                    """"meta":{"is_authenticated":false}}""",
            ),
        )

        val result = authenticator.login(server(version = "2.7.1"), "anna", "hunter2")

        assertEquals(TandoorAuthenticator.PASSWORD_LOGIN_UNAVAILABLE, (result as Outcome.Failure).error)
        assertEquals(1, tandoor.server.requestCount)
    }

    @Test
    fun tandoor27WithoutAnAppLoginMeansPasteAToken() = runBlocking {
        tandoor.enqueue(jsonResponse(404, """{"status":404}"""))

        val result = authenticator.login(server(version = "2.7.1"), "anna", "hunter2")

        assertEquals(TandoorAuthenticator.PASSWORD_LOGIN_UNAVAILABLE, (result as Outcome.Failure).error)
    }

    @Test
    fun tandoor27AccountWithoutASpaceIsRefusedAtTheTokenAndHasNoToken() = runBlocking {
        tandoor.enqueue(allauthLoggedIn(), refused(), allauthLoggedOut())

        val result = authenticator.login(server(version = "2.7.1"), "anna", "hunter2")

        val credentials = (result as Outcome.Success).value
        assertNull(credentials.spaceId)
        assertNull(credentials.tokenId)
        assertEquals("", credentials.token)
        assertEquals(1L, credentials.userId)
        assertEquals(
            listOf("POST /_allauth/app/v1/auth/login", "POST /api/access-token/", "DELETE /_allauth/app/v1/auth/session"),
            List(3) { tandoor.takeRequest().line() },
        )
    }

    @Test
    fun tandoor27SessionIsLoggedOutAlsoWhenTheTokenCannotBeCreated() = runBlocking {
        tandoor.enqueue(allauthLoggedIn(), emptyResponse(500), allauthLoggedOut())

        val result = authenticator.login(server(version = "2.7.1"), "anna", "hunter2")

        assertEquals(TandemError.ServerError(500), (result as Outcome.Failure).error)
        assertEquals(
            listOf("POST /_allauth/app/v1/auth/login", "POST /api/access-token/", "DELETE /_allauth/app/v1/auth/session"),
            List(3) { tandoor.takeRequest().line() },
        )
    }

    @Test
    fun aServerWhoseTokenLoginIsGoneIsTriedWithAllauth() = runBlocking {
        // An unparseable version (a development build) tries api-token-auth first; 2.7 redirects it.
        tandoor.enqueue(
            emptyResponse(302, "Location" to "/account/login/?next=/api-token-auth/"),
            allauthLoggedIn(),
            deviceTokenCreated(),
            allauthLoggedOut(),
            Fixture.load("13-space-current").toMockResponse(),
            Fixture.load("12-user-preference-list").toMockResponse(),
        )

        val result = authenticator.login(server(version = "develop"), "anna", "hunter2")

        assertEquals(deviceToken, (result as Outcome.Success).value.token)
        assertEquals("POST /api-token-auth/", tandoor.takeRequest().line())
        assertEquals("POST /_allauth/app/v1/auth/login", tandoor.takeRequest().line())
    }

    @Test
    fun theLoginMechanismFollowsTheVersion() {
        assertFalse(TandoorAuthenticator.usesAllauth("2.6.15"))
        assertFalse(TandoorAuthenticator.usesAllauth("develop"))
        assertFalse(TandoorAuthenticator.usesAllauth(null))
        assertTrue(TandoorAuthenticator.usesAllauth("2.7.0"))
        assertTrue(TandoorAuthenticator.usesAllauth("2.7.1"))
        assertTrue(TandoorAuthenticator.usesAllauth("3.0.0"))
    }
}
