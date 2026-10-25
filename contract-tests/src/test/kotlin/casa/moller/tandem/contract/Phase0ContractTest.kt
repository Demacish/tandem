package casa.moller.tandem.contract

import java.io.File
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * Establishes and records the server facts Phase 0 depends on (plan 3.1, 4.2, Fas 0 "Klart när" #1):
 * public server settings, `api-token-auth` (encodings, fields, errors, token reuse), a per-device
 * token from `/api/access-token/`, identity endpoints, invalid/missing/expired/revoked tokens,
 * 404 bodies and the login throttle.
 *
 * The steps share state and run in order against ONE fresh Tandoor (contract.yml). Every exchange
 * is recorded, sanitized, under `build/contract-recordings/<tag>/`; `observed-facts.json` holds
 * the summary that `api-schema/fixtures/<version>/FACTS.md` is written from.
 *
 * **Tandoor 2.7+** removed `api-token-auth` ("deprecate old token view"). From 2.7 on the same
 * steps run against allauth headless' app API instead (the login the app uses there, see
 * `TandoorAuthenticator`): `POST /_allauth/app/v1/auth/login` gives a session token
 * (`meta.session_token`), which is sent as `X-Session-Token` to create the device token and is
 * then logged out (`DELETE /_allauth/app/v1/auth/session`). The facts of that branch are under
 * `login.allauth.*`, `logout.*` and the same keys as 2.6 where the step is the same. allauth
 * allows 3 logins per minute and IP (`ACCOUNT_RATE_LIMITS`), so its logins are paced.
 *
 * Skipped unless TANDOOR_URL is set. Never run against a production server: it revokes tokens and
 * deliberately exhausts the login throttle.
 */
// LargeClass: one ordered scenario against one server whose steps share state (tokens, ids), with
// a branch per login mechanism (2.6 api-token-auth, 2.7 allauth); splitting it would scatter that state.
@Suppress("LargeClass")
@EnabledIfEnvironmentVariable(named = "TANDOOR_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class Phase0ContractTest {
    private val env = ContractEnv
    private val sanitizer = Sanitizer(listOf(env.password, env.noSpacePassword))
    private val http = TandoorHttp(env.baseUrl, sanitizer, env.recordDir)
    private val facts = Facts()

    private var loginCalls = 0
    private var loginToken: String? = null
    private var loginTokenId: Long? = null
    private var userId: Long? = null
    private var deviceToken: String? = null
    private var deviceTokenId: Long? = null

    /** Tandoor 2.7+: password login through allauth headless (`api-token-auth` is gone). */
    private var allauth = false

    /** Tandoor 2.7+: the allauth session token of the login (stands in for the login token). */
    private var loginSession: String? = null

    /** When each allauth login was sent (epoch millis), for the 3 per minute limit. */
    private val allauthLogins = ArrayDeque<Long>()

    @BeforeAll
    fun setUp() {
        assertTrue(env.username.isNotBlank(), "TANDOOR_USERNAME must be set")
        assertTrue(env.password.isNotBlank(), "TANDOOR_PASSWORD must be set")
        assertTrue(env.noSpaceUsername.isNotBlank(), "TANDOOR_NOSPACE_USERNAME must be set")
        assertTrue(env.noSpacePassword.isNotBlank(), "TANDOOR_NOSPACE_PASSWORD must be set")
        env.recordDir.deleteRecursively()
        env.recordDir.mkdirs()
        facts["tandoorTag"] = env.tag
    }

    @AfterAll
    fun writeFacts() {
        facts.write(File(env.recordDir, "observed-facts.json"), sanitizer)
    }

    // --- a. Server settings --------------------------------------------------------------------

    @Test
    @Order(10)
    fun serverSettingsArePublicAndReportTheVersion() {
        val ex = http.call("server-settings-no-auth", "GET", "/api/server-settings/current/")
        facts["serverSettings.noAuth"] = describe(ex, includeBody = false)
        assertEquals(200, ex.status, ex.toString())
        val body = ex.jsonObject
        facts["serverSettings.fieldTypes"] = Facts.fieldTypes(body)
        val version = body.string("version")
        facts["serverSettings.version"] = version
        assertTrue(!version.isNullOrBlank(), "server-settings/current has no version: $body")
        if (env.expectedVersion.isNotBlank()) assertEquals(env.expectedVersion, version, "version")
        allauth = usesAllauthLogin(version)
        facts["login.mechanism"] = if (allauth) "allauth-app" else "api-token-auth"
    }

    @Test
    @Order(11)
    fun serverSettingsWithAnInvalidToken() {
        val ex = http.call(
            "server-settings-invalid-token",
            "GET",
            "/api/server-settings/current/",
            bearer = UNKNOWN_TOKEN,
        )
        facts["serverSettings.invalidToken"] = describe(ex, includeBody = false)
        // Observed in 2.6.13: the public endpoint ignores a bad token (no 403).
        assertEquals(200, ex.status, ex.toString())
    }

    // --- b. api-token-auth (2.6) / allauth headless (2.7+) ------------------------------------

    /**
     * Tandoor 2.7+: `api-token-auth` answers a redirect to the web login page. The app reads that
     * redirect (and 404/405) as "this server has no token login" and uses allauth instead.
     */
    @Test
    @Order(19)
    fun legacyTokenLoginIsGone() {
        assumeTrue(allauth, "api-token-auth exists before Tandoor 2.7")
        val ex = http.call(
            "login-legacy-gone",
            "POST",
            "/api-token-auth/",
            body = jsonBody(credentials(env.password)),
            recordBody = recordedCredentials(),
        )
        facts["login.legacy"] = describe(ex, includeBody = false)
        facts["login.legacy.location"] = ex.header("Location")
        assertTrue(ex.status in 300..399, ex.toString())
        assertTrue(ex.header("Location").orEmpty().contains("/login"), "Location: ${ex.header("Location")}")
    }

    @Test
    @Order(20)
    fun loginWithJson() {
        if (allauth) {
            allauthLoginWithJson()
            return
        }
        val ex = login("login-json", jsonBody(credentials(env.password)), recordedCredentials())
        facts["login.json"] = describe(ex, includeBody = false)
        assertEquals(200, ex.status, ex.toString())
        val body = ex.jsonObject
        facts["login.fieldTypes"] = Facts.fieldTypes(body)
        val token = body.string("token")
        assertNotNull(token, "token missing: $body")
        assertTrue(Sanitizer.TOKEN.matches(token!!), "token format")
        sanitizer.fakeTokenFor(token)
        loginToken = token
        loginTokenId = body.long("id")
        userId = body.long("user_id")
        facts["login.scope"] = body.string("scope")
        facts["login.expires"] = body.string("expires")
        facts["login.expiresInDays"] = daysUntil(body.string("expires"))
        facts["login.testEqualsUserId"] = body.long("test") == userId
        assertNotNull(loginTokenId, "id missing: $body")
        assertNotNull(userId, "user_id missing: $body")
        assertEquals("read write app", body.string("scope"), "scope")
    }

    /** Tandoor 2.7+: the app's login, `POST /_allauth/app/v1/auth/login` with JSON. */
    private fun allauthLoginWithJson() {
        val ex = allauthLogin("login-json", jsonBody(credentials(env.password)), recordedCredentials())
        facts["login.allauth"] = describe(ex, includeBody = false)
        assertEquals(200, ex.status, ex.toString())
        val body = ex.jsonObject
        facts["login.allauth.fieldTypes"] = Facts.fieldTypes(body)
        val meta = body["meta"] as? JsonObject
        val data = body["data"] as? JsonObject
        val user = data?.get("user") as? JsonObject
        facts["login.allauth.metaFieldTypes"] = meta?.let { Facts.fieldTypes(it) }
        facts["login.allauth.dataFieldTypes"] = data?.let { Facts.fieldTypes(it) }
        facts["login.allauth.userFieldTypes"] = user?.let { Facts.fieldTypes(it) }
        facts["login.allauth.isAuthenticated"] = meta?.get("is_authenticated")
        facts["login.allauth.status.field"] = body["status"]
        val session = meta?.string("session_token")
        assertTrue(!session.isNullOrBlank(), "meta.session_token missing: ${sanitizer.sanitize(body)}")
        sanitizer.addSecret(session!!)
        facts["login.allauth.sessionTokenLength"] = session.length
        loginSession = session
        userId = user?.long("id")
        facts["login.allauth.userIdPresent"] = userId != null
        assertNotNull(userId, "data.user.id missing: ${sanitizer.sanitize(body)}")
        assertEquals(true, (meta?.get("is_authenticated") as? JsonPrimitive)?.content?.toBooleanStrictOrNull(), "meta.is_authenticated")
    }

    @Test
    @Order(21)
    fun loginWithFormEncoding() {
        assumeFalse(allauth, "the app sends JSON only; allauth's app API reads JSON bodies")
        val form = FormBody.Builder().add("username", env.username).add("password", env.password).build()
        checkAlternativeLogin("login-form", form)
    }

    @Test
    @Order(22)
    fun loginWithMultipart() {
        assumeFalse(allauth, "the app sends JSON only; allauth's app API reads JSON bodies")
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("username", env.username)
            .addFormDataPart("password", env.password)
            .build()
        checkAlternativeLogin("login-multipart", multipart)
    }

    private fun checkAlternativeLogin(name: String, body: RequestBody) {
        val ex = login(name, body, recordedCredentials())
        val token = ex.json?.let { it as? JsonObject }?.string("token")
        facts["$name.status"] = ex.status
        facts["$name.returnsSameTokenAsJsonLogin"] = token != null && token == loginToken
        facts["$name.returnsSameIdAsJsonLogin"] = (ex.json as? JsonObject)?.long("id") == loginTokenId
        assertEquals(200, ex.status, ex.toString())
        // A valid read+write token already exists, so the server hands out the same one again.
        assertEquals(loginToken, token, "$name must return the existing login token")
    }

    @Test
    @Order(23)
    fun loginWithWrongPassword() {
        val ex = passwordLogin(
            "login-wrong-password",
            jsonBody(credentials("wrong-$WRONG_PASSWORD_SUFFIX")),
            recordedCredentials("wrong-$WRONG_PASSWORD_SUFFIX"),
        )
        facts["login.wrongPassword"] = describe(ex)
        assertEquals(400, ex.status, ex.toString())
    }

    @Test
    @Order(24)
    fun loginWithMissingFields() {
        val empty = buildJsonObject { }
        val ex = passwordLogin("login-missing-fields", jsonBody(empty), empty)
        facts["login.missingFields"] = describe(ex)
        assertEquals(400, ex.status, ex.toString())
    }

    // --- c. Per-device token -------------------------------------------------------------------

    @Test
    @Order(30)
    fun createDeviceToken() {
        val login = requireLoginCredential()
        val expires = OffsetDateTime.now(ZoneOffset.UTC).plusYears(1).truncatedTo(ChronoUnit.SECONDS)
        val request = buildJsonObject {
            put("scope", "read write")
            put("expires", expires.toString())
        }
        val ex = http.call(
            "access-token-create",
            "POST",
            "/api/access-token/",
            authorization = loginAuthorization(),
            body = jsonBody(request),
            recordBody = request,
            extraHeaders = loginHeaders(),
        )
        facts["deviceToken.create"] = describe(ex, includeBody = false)
        assertEquals(201, ex.status, ex.toString())
        val body = ex.jsonObject
        facts["deviceToken.fieldTypes"] = Facts.fieldTypes(body)
        val token = body.string("token")
        val plaintext = token != null && Sanitizer.TOKEN.matches(token)
        facts["deviceToken.tokenIsPlaintextInCreateResponse"] = plaintext
        assertTrue(plaintext, "create response must carry the plaintext token: $body")
        assertNotEquals(login, token, "device token must differ from the login token")
        sanitizer.fakeTokenFor(token!!)
        deviceToken = token
        deviceTokenId = body.long("id")
        assertNotNull(deviceTokenId, "id missing: $body")
        facts["deviceToken.scope"] = body.string("scope")
        facts["deviceToken.expiresSent"] = expires.toString()
        facts["deviceToken.expiresReturned"] = body.string("expires")
        facts["deviceToken.expiresInDays"] = daysUntil(body.string("expires"))
        assertEquals("read write", body.string("scope"), "scope")
    }

    @Test
    @Order(31)
    fun accessTokenListMasksTokensAfterFifteenSeconds() {
        val device = requireDeviceToken()
        val fresh = http.call("access-token-list-fresh", "GET", "/api/access-token/", bearer = device)
        assertEquals(200, fresh.status, fresh.toString())
        facts["accessTokenList.isArray"] = fresh.json is JsonArray
        facts["accessTokenList.fresh"] = summarizeTokens(fresh)

        Thread.sleep(MASK_DELAY.toMillis())
        val later = http.call("access-token-list-after-16s", "GET", "/api/access-token/", bearer = device)
        assertEquals(200, later.status, later.toString())
        facts["accessTokenList.after16s"] = summarizeTokens(later)
        val entries = (later.json as JsonArray).map { it as JsonObject }
        val known = listOfNotNull(loginToken, deviceToken)
        for (entry in entries) {
            val shown = entry.string("token").orEmpty()
            assertTrue(Sanitizer.MASKED_TOKEN.matches(shown), "expected a masked token after 16 s: $shown")
            assertTrue(known.any { it.takeLast(4) == shown.takeLast(4) }, "masked token keeps the last 4 characters")
        }
    }

    // --- d. Identity ---------------------------------------------------------------------------

    @Test
    @Order(40)
    fun identityEndpointsWithDeviceToken() {
        val device = requireDeviceToken()
        val user = http.call("user-by-id", "GET", "/api/user/$userId/", bearer = device)
        facts["user.byId"] = describe(user, includeBody = false)
        assertEquals(200, user.status, user.toString())
        facts["user.fieldTypes"] = Facts.fieldTypes(user.jsonObject)
        assertEquals(userId, user.jsonObject.long("id"), "user id")

        val preference = http.call("user-preference-list", "GET", "/api/user-preference/", bearer = device)
        facts["userPreference.list"] = describe(preference, includeBody = false)
        assertEquals(200, preference.status, preference.toString())
        val preferences = preference.json as? JsonArray
        facts["userPreference.isArray"] = preferences != null
        facts["userPreference.count"] = preferences?.size
        val first = preferences?.firstOrNull() as? JsonObject
        facts["userPreference.fieldTypes"] = first?.let { Facts.fieldTypes(it) }
        facts["userPreference.userField"] = first?.get("user")

        val space = http.call("space-current", "GET", "/api/space/current/", bearer = device)
        facts["space.current"] = describe(space, includeBody = false)
        assertEquals(200, space.status, space.toString())
        facts["space.fieldTypes"] = Facts.fieldTypes(space.jsonObject)
        facts["space.id"] = space.jsonObject.long("id")
        facts["space.householdSetupCompleted"] = space.jsonObject["household_setup_completed"]

        val userSpaces = http.call("user-space-all-personal", "GET", "/api/user-space/all_personal/", bearer = device)
        facts["userSpace.allPersonal"] = describe(userSpaces, includeBody = false)
        assertEquals(200, userSpaces.status, userSpaces.toString())
        val firstUserSpace = (userSpaces.json as? JsonArray)?.firstOrNull() as? JsonObject
        facts["userSpace.fieldTypes"] = firstUserSpace?.let { Facts.fieldTypes(it) }
        facts["userSpace.household"] = firstUserSpace?.get("household")
        facts["userSpace.groups"] = firstUserSpace?.get("groups")
        facts["userSpace.space"] = firstUserSpace?.get("space")
        facts["userSpace.active"] = firstUserSpace?.get("active")

        requireLoginCredential()
        val withLoginToken = http.call(
            "space-current-login-token",
            "GET",
            "/api/space/current/",
            authorization = loginAuthorization(),
            record = false,
            extraHeaders = loginHeaders(),
        )
        facts["space.current.withLoginToken.status"] = withLoginToken.status
    }

    // --- h. An account without a space ---------------------------------------------------------

    /**
     * A user without a UserSpace (created by an admin, never logged in to the web UI) can log in
     * and create tokens, but the space and the user by id refuse it with 403 + JSON, just like an
     * invalid token, and `user-preference` fails with 500. The token list (owner check only)
     * still answers. The app uses this to show "the account has no space" instead of "log in
     * again" (plan 6.2): right after creating a token, or when the token list works.
     *
     * Not recorded as numbered fixtures (that would renumber the baseline files); the exchanges
     * are kept, sanitized, in observed-facts.json under `noSpace.*`.
     */
    @Test
    @Order(45)
    fun accountWithoutASpace() {
        val credentials = buildJsonObject {
            put("username", env.noSpaceUsername)
            put("password", env.noSpacePassword)
        }
        if (allauth) {
            allauthAccountWithoutASpace(credentials)
            return
        }
        val login = login("login-no-space", jsonBody(credentials), recordedCredentials(), record = false)
        facts["noSpace.login"] = describe(login, includeBody = false)
        assertEquals(200, login.status, login.toString())
        val noSpaceLoginToken = login.jsonObject.string("token")!!
        val noSpaceUserId = login.jsonObject.long("user_id")
        facts["noSpace.login.scope"] = login.jsonObject.string("scope")

        val request = buildJsonObject {
            put("scope", "read write")
            put("expires", OffsetDateTime.now(ZoneOffset.UTC).plusYears(1).truncatedTo(ChronoUnit.SECONDS).toString())
        }
        val create = http.call(
            "no-space-access-token-create",
            "POST",
            "/api/access-token/",
            bearer = noSpaceLoginToken,
            body = jsonBody(request),
            recordBody = request,
            record = false,
        )
        facts["noSpace.accessTokenCreate"] = describe(create)
        assertEquals(201, create.status, create.toString())
        val device = create.jsonObject.string("token")!!

        val tokenList = http.call("no-space-access-token-list", "GET", "/api/access-token/", bearer = device, record = false)
        val preference = http.call("no-space-user-preference", "GET", "/api/user-preference/", bearer = device, record = false)
        val user = http.call("no-space-user-by-id", "GET", "/api/user/$noSpaceUserId/", bearer = device, record = false)
        val space = http.call("no-space-space-current", "GET", "/api/space/current/", bearer = device, record = false)
        facts["noSpace.accessTokenList"] = describe(tokenList, includeBody = false)
        facts["noSpace.userPreference"] = describe(preference)
        facts["noSpace.userById"] = describe(user)
        facts["noSpace.spaceCurrent"] = describe(space)

        val cleanup = http.call(
            "no-space-revoke-device-token",
            "DELETE",
            "/api/access-token/${create.jsonObject.long("id")}/",
            bearer = device,
            record = false,
        )
        facts["noSpace.revokeDeviceTokenStatus"] = cleanup.status

        // The space and the user by id are refused like an invalid token: 403 + JSON {detail}.
        assertTokenRejected(space)
        assertTokenRejected(user)
        // The token list needs no space: it tells "no space" apart from "invalid token".
        assertEquals(200, tokenList.status, tokenList.toString())
        // Observed in 2.6.13: user-preference crashes (500 HTML) without a space, so the app never
        // asks it before the space read succeeded. If this changes, update FACTS.md h.
        assertEquals(500, preference.status, preference.toString())
        assertEquals(204, cleanup.status, cleanup.toString())
    }

    /**
     * Tandoor 2.7+: the password is accepted (a session token), but `ScopeMiddleware` refuses
     * every `/api/` request of a user without an active UserSpace with 403 + JSON, also the token
     * create. Right after a successful login that 403 can only mean "no space", which is what the
     * app reports.
     */
    private fun allauthAccountWithoutASpace(credentials: JsonObject) {
        val login = allauthLogin("login-no-space", jsonBody(credentials), recordedCredentials(), record = false)
        facts["noSpace.login"] = describe(login, includeBody = false)
        assertEquals(200, login.status, login.toString())
        val session = (login.jsonObject["meta"] as? JsonObject)?.string("session_token")
        assertTrue(!session.isNullOrBlank(), "meta.session_token missing")
        sanitizer.addSecret(session!!)
        val headers = mapOf(SESSION_HEADER to session)
        val request = buildJsonObject {
            put("scope", "read write")
            put("expires", OffsetDateTime.now(ZoneOffset.UTC).plusYears(1).truncatedTo(ChronoUnit.SECONDS).toString())
        }
        val create = http.call(
            "no-space-access-token-create",
            "POST",
            "/api/access-token/",
            body = jsonBody(request),
            recordBody = request,
            record = false,
            extraHeaders = headers,
        )
        val space = http.call("no-space-space-current", "GET", "/api/space/current/", record = false, extraHeaders = headers)
        facts["noSpace.accessTokenCreate"] = describe(create)
        facts["noSpace.spaceCurrent"] = describe(space)
        val logout = http.call("no-space-logout", "DELETE", ALLAUTH_SESSION, record = false, extraHeaders = headers)
        facts["noSpace.logoutStatus"] = logout.status
        assertTokenRejected(create)
        assertTokenRejected(space)
    }

    // --- e. Invalid and missing tokens ---------------------------------------------------------

    @Test
    @Order(50)
    fun invalidAndMissingTokens() {
        val cases = linkedMapOf(
            "missing" to null,
            "unknownWellFormed" to "Bearer $UNKNOWN_TOKEN",
            "garbage" to "Bearer not-a-token",
            "emptyBearer" to "Bearer ",
        )
        val statuses = mutableSetOf<Int>()
        for ((case, authorization) in cases) {
            val get = http.call("invalid-token-$case-get", "GET", "/api/space/current/", authorization = authorization)
            val postBody = buildJsonObject { put("scope", "read") }
            val post = http.call(
                "invalid-token-$case-post",
                "POST",
                "/api/access-token/",
                authorization = authorization,
                body = jsonBody(postBody),
                recordBody = postBody,
            )
            facts["invalidToken.$case.get"] = describe(get)
            facts["invalidToken.$case.post"] = describe(post)
            statuses += get.status
            statuses += post.status
            assertTokenRejected(get)
            assertTokenRejected(post)
        }
        facts["invalidToken.statuses"] = statuses.sorted()

        // The `detail` text follows Accept-Language, so clients must never match on it.
        val swedish = http.call(
            "invalid-token-accept-language-sv",
            "GET",
            "/api/space/current/",
            bearer = UNKNOWN_TOKEN,
            extraHeaders = mapOf("Accept-Language" to "sv"),
        )
        facts["invalidToken.acceptLanguageSv"] = describe(swedish)
        facts["invalidToken.acceptLanguageSv.contentLanguage"] = swedish.header("Content-Language")
        assertTokenRejected(swedish)
    }

    /** Observed in 2.6.13 for missing, unknown, garbage, expired and revoked tokens alike. */
    private fun assertTokenRejected(ex: Exchange) {
        assertEquals(403, ex.status, ex.toString())
        assertTrue(ex.json is JsonObject && ex.jsonObject.string("detail") != null, "JSON {detail}: $ex")
        assertNull(ex.header("WWW-Authenticate"), "WWW-Authenticate")
    }

    @Test
    @Order(51)
    fun expiredToken() {
        val device = requireDeviceToken()
        val request = buildJsonObject {
            put("scope", "read write")
            put("expires", OffsetDateTime.now(ZoneOffset.UTC).minusDays(1).truncatedTo(ChronoUnit.SECONDS).toString())
        }
        val created = http.call(
            "expired-token-create",
            "POST",
            "/api/access-token/",
            bearer = device,
            body = jsonBody(request),
            recordBody = request,
        )
        facts["expiredToken.create"] = describe(created, includeBody = false)
        // Not an assumption: if a newer Tandoor refused past expiries, this fact (and the 403 for
        // an expired token that ErrorClassifier relies on) must be re-established, not skipped.
        assertEquals(201, created.status, created.toString())
        val token = created.jsonObject.string("token")!!
        sanitizer.fakeTokenFor(token)
        val use = http.call("expired-token-get", "GET", "/api/space/current/", bearer = token)
        facts["expiredToken.use"] = describe(use)
        val cleanup = http.call(
            "expired-token-delete",
            "DELETE",
            "/api/access-token/${created.jsonObject.long("id")}/",
            bearer = device,
        )
        facts["expiredToken.deleteStatus"] = cleanup.status
        assertTokenRejected(use)
        assertEquals(204, cleanup.status, cleanup.toString())
    }

    // --- g. Not found ---------------------------------------------------------------------------

    @Test
    @Order(55)
    fun notFoundBodies() {
        val device = requireDeviceToken()
        val recipe = http.call("not-found-recipe", "GET", "/api/recipe/$MISSING_ID/", bearer = device)
        facts["notFound.recipe"] = describe(recipe)
        val token = http.call("not-found-access-token", "GET", "/api/access-token/$MISSING_ID/", bearer = device)
        facts["notFound.accessToken"] = describe(token)
        val path = http.call("not-found-unknown-path", "GET", "/api/this-endpoint-does-not-exist/", bearer = device)
        facts["notFound.unknownPath"] = describe(path)
        facts["notFound.unknownPath.location"] = path.header("Location")
        assertEquals(404, recipe.status, recipe.toString())
        assertEquals(404, token.status, token.toString())
        assertTrue(recipe.json is JsonObject && recipe.jsonObject.string("detail") != null, "JSON {detail}: $recipe")
        // Observed in 2.6.13: an unknown /api/ path is NOT a JSON 404 but a redirect to the web
        // login page (HTML). Clients must not follow redirects blindly on API calls.
        assertEquals(302, path.status, path.toString())
        // 2.6: /accounts/login/ (allauth's page); 2.7: the new web app's /account/login/.
        val loginPage = if (allauth) "/account/login/" else "/accounts/login/"
        assertTrue(path.header("Location").orEmpty().startsWith(loginPage), "Location: ${path.header("Location")}")
    }

    // --- f. Revocation and token reuse ---------------------------------------------------------

    @Test
    @Order(60)
    fun revokingTheLoginTokenLeavesTheDeviceTokenWorking() {
        if (allauth) {
            loggingOutTheSessionLeavesTheDeviceTokenWorking()
            return
        }
        val device = requireDeviceToken()
        val login = requireLoginToken()
        val delete = http.call("revoke-login-token", "DELETE", "/api/access-token/$loginTokenId/", bearer = device)
        facts["revokeLoginToken.delete"] = describe(delete)
        assertEquals(204, delete.status, delete.toString())

        val revoked = http.call("revoked-login-token-get", "GET", "/api/space/current/", bearer = login)
        facts["revokeLoginToken.revokedTokenUse"] = describe(revoked)
        val stillWorks = http.call("device-token-after-login-token-revoked", "GET", "/api/space/current/", bearer = device)
        facts["revokeLoginToken.deviceTokenStillWorks"] = stillWorks.status == 200
        assertTokenRejected(revoked)
        assertEquals(200, stillWorks.status, stillWorks.toString())
    }

    /**
     * Tandoor 2.7+: what the app does after creating the device token. allauth answers a logout
     * with 401 (the response describes the now unauthenticated state); the session token is then
     * refused like an invalid token, and the device token keeps working.
     */
    private fun loggingOutTheSessionLeavesTheDeviceTokenWorking() {
        val device = requireDeviceToken()
        val session = requireLoginCredential()
        val headers = mapOf(SESSION_HEADER to session)
        val logout = http.call("logout-session", "DELETE", ALLAUTH_SESSION, extraHeaders = headers)
        facts["logout.session"] = describe(logout)
        assertEquals(401, logout.status, logout.toString())

        val revoked = http.call("logged-out-session-get", "GET", "/api/space/current/", extraHeaders = headers)
        facts["logout.sessionUse"] = describe(revoked)
        val stillWorks = http.call("device-token-after-logout", "GET", "/api/space/current/", bearer = device)
        facts["revokeLoginToken.deviceTokenStillWorks"] = stillWorks.status == 200
        assertTokenRejected(revoked)
        assertEquals(200, stillWorks.status, stillWorks.toString())
    }

    @Test
    @Order(61)
    fun loginAfterTheLoginTokenWasRevoked() {
        if (allauth) {
            loginAgainAfterLogout()
            return
        }
        val device = requireDeviceToken()
        val ex = login("login-after-login-token-revoked", jsonBody(credentials(env.password)), recordedCredentials())
        assertEquals(200, ex.status, ex.toString())
        val body = ex.jsonObject
        val token = body.string("token")!!
        sanitizer.fakeTokenFor(token)
        facts["loginAfterRevoke.returnsDeviceToken"] = token == device
        facts["loginAfterRevoke.returnsNewToken"] = token != device && token != loginToken
        facts["loginAfterRevoke.scope"] = body.string("scope")
        facts["loginAfterRevoke.expiresInDays"] = daysUntil(body.string("expires"))
        // Observed in 2.6.13 (CustomAuthToken: first non-expired token with read+write): once the
        // login token is gone, a password login hands out the DEVICE token in plaintext. Plan 3.1
        // step 4 (deleting the login token) must therefore not be implemented as written.
        assertEquals(device, token, "login after revoking the login token returns the device token")
    }

    /** Tandoor 2.7+: every login is a new session (no token reuse as with `api-token-auth`). */
    private fun loginAgainAfterLogout() {
        requireDeviceToken()
        val ex = allauthLogin("login-after-logout", jsonBody(credentials(env.password)), recordedCredentials())
        assertEquals(200, ex.status, ex.toString())
        val session = (ex.jsonObject["meta"] as? JsonObject)?.string("session_token")
        assertTrue(!session.isNullOrBlank(), "meta.session_token missing")
        sanitizer.addSecret(session!!)
        facts["loginAfterRevoke.returnsNewSession"] = session != loginSession
        val logout = http.call(
            "logout-after-login-again",
            "DELETE",
            ALLAUTH_SESSION,
            record = false,
            extraHeaders = mapOf(SESSION_HEADER to session),
        )
        facts["loginAfterRevoke.logoutStatus"] = logout.status
        assertNotEquals(loginSession, session, "a new login gives a new session")
    }

    @Test
    @Order(70)
    fun revokingTheDeviceTokenWithItself() {
        val device = requireDeviceToken()
        val delete = http.call("revoke-device-token", "DELETE", "/api/access-token/$deviceTokenId/", bearer = device)
        facts["revokeDeviceToken.delete"] = describe(delete)
        assertEquals(204, delete.status, delete.toString())

        val get = http.call("revoked-device-token-get", "GET", "/api/space/current/", bearer = device)
        facts["revokeDeviceToken.get"] = describe(get)
        val postBody = buildJsonObject { put("scope", "read") }
        val post = http.call(
            "revoked-device-token-post",
            "POST",
            "/api/access-token/",
            bearer = device,
            body = jsonBody(postBody),
            recordBody = postBody,
        )
        facts["revokeDeviceToken.post"] = describe(post)
        assertTokenRejected(get)
        assertTokenRejected(post)
    }

    // --- Login throttle (last: it uses up the 10/day budget of this runner IP) -----------------

    @Test
    @Order(90)
    fun loginThrottle() {
        if (allauth) {
            allauthLoginThrottle()
            return
        }
        val before = loginCalls
        var throttled: Exchange? = null
        var allowed = loginCalls
        while (throttled == null && loginCalls < before + MAX_THROTTLE_ATTEMPTS) {
            val ex = login(
                "login-throttle",
                jsonBody(credentials("wrong-$WRONG_PASSWORD_SUFFIX")),
                recordedCredentials("wrong-$WRONG_PASSWORD_SUFFIX"),
                record = false,
            )
            if (ex.status == 429) throttled = ex else allowed = loginCalls
        }
        facts["throttle.allowedLoginCallsBefore429"] = allowed
        assertNotNull(throttled, "no 429 after ${loginCalls - before} extra login calls")
        // Record the 429 once (the repeated 400s add nothing).
        val recorded = login(
            "login-throttled",
            jsonBody(credentials("wrong-$WRONG_PASSWORD_SUFFIX")),
            recordedCredentials("wrong-$WRONG_PASSWORD_SUFFIX"),
        )
        facts["throttle.response"] = describe(recorded)
        facts["throttle.retryAfterHeader"] = recorded.header("Retry-After")
        assertEquals(429, recorded.status, recorded.toString())
        assertEquals(10, allowed, "api-token-auth allows 10 calls per day and IP (AuthTokenThrottle)")
        assertNotNull(recorded.header("Retry-After"), "Retry-After header")
    }

    /**
     * Tandoor 2.7+: `ACCOUNT_RATE_LIMITS["login"] = "3/m/ip"`, every attempt counts. Waits until
     * the window of the earlier logins has passed, then sends wrong passwords until the 429.
     */
    private fun allauthLoginThrottle() {
        allauthLogins.lastOrNull()?.let { last ->
            val wait = last + RATE_WINDOW.toMillis() - System.currentTimeMillis()
            if (wait > 0) Thread.sleep(wait)
        }
        allauthLogins.clear()
        var throttled: Exchange? = null
        var allowed = 0
        var attempts = 0
        while (throttled == null && attempts < MAX_THROTTLE_ATTEMPTS) {
            attempts += 1
            val ex = allauthLogin(
                "login-throttle",
                jsonBody(credentials("wrong-$WRONG_PASSWORD_SUFFIX")),
                recordedCredentials("wrong-$WRONG_PASSWORD_SUFFIX"),
                record = false,
                paced = false,
            )
            if (ex.status == 429) throttled = ex else allowed += 1
        }
        facts["throttle.allowedLoginCallsBefore429"] = allowed
        assertNotNull(throttled, "no 429 after $attempts allauth logins")
        val recorded = allauthLogin(
            "login-throttled",
            jsonBody(credentials("wrong-$WRONG_PASSWORD_SUFFIX")),
            recordedCredentials("wrong-$WRONG_PASSWORD_SUFFIX"),
            paced = false,
        )
        facts["throttle.response"] = describe(recorded)
        facts["throttle.retryAfterHeader"] = recorded.header("Retry-After")
        assertEquals(429, recorded.status, recorded.toString())
        assertEquals(3, allowed, "allauth allows 3 logins per minute and IP (ACCOUNT_RATE_LIMITS)")
    }

    // --- Helpers --------------------------------------------------------------------------------

    private fun login(name: String, body: RequestBody, recordBody: JsonElement, record: Boolean = true): Exchange {
        loginCalls += 1
        return http.call(name, "POST", "/api-token-auth/", body = body, recordBody = recordBody, record = record)
    }

    /** The password login of this server version: `api-token-auth` (2.6) or allauth (2.7+). */
    private fun passwordLogin(name: String, body: RequestBody, recordBody: JsonElement, record: Boolean = true): Exchange =
        if (allauth) allauthLogin(name, body, recordBody, record) else login(name, body, recordBody, record)

    /**
     * `POST /_allauth/app/v1/auth/login`. With [paced], waits so that at most 3 logins fall into
     * any minute (allauth's limit for every attempt, success or not).
     */
    private fun allauthLogin(
        name: String,
        body: RequestBody,
        recordBody: JsonElement,
        record: Boolean = true,
        paced: Boolean = true,
    ): Exchange {
        if (paced) {
            val now = System.currentTimeMillis()
            while (allauthLogins.isNotEmpty() && allauthLogins.first() < now - RATE_WINDOW.toMillis()) allauthLogins.removeFirst()
            if (allauthLogins.size >= ALLAUTH_LOGINS_PER_MINUTE) {
                val wait = allauthLogins.first() + RATE_WINDOW.toMillis() - now
                if (wait > 0) Thread.sleep(wait)
                allauthLogins.removeFirst()
            }
        }
        allauthLogins.addLast(System.currentTimeMillis())
        loginCalls += 1
        val ex = http.call(name, "POST", ALLAUTH_LOGIN, body = body, recordBody = recordBody, record = false)
        // The session token must be known to the sanitizer before the exchange is written.
        ((ex.json as? JsonObject)?.get("meta") as? JsonObject)?.string("session_token")?.let { sanitizer.addSecret(it) }
        if (record) http.record(ex)
        return ex
    }

    /** `Authorization` for the login credential: the login token (2.6), none with allauth. */
    private fun loginAuthorization(): String? = if (allauth) null else loginToken?.let { "Bearer $it" }

    /** Extra headers for the login credential: `X-Session-Token` with allauth (2.7+). */
    private fun loginHeaders(): Map<String, String> =
        if (allauth) loginSession?.let { mapOf(SESSION_HEADER to it) }.orEmpty() else emptyMap()

    /** The login token (2.6) or the allauth session token (2.7+). */
    private fun requireLoginCredential(): String = if (allauth) {
        assumeTrue(loginSession != null, "login failed in an earlier step")
        loginSession!!
    } else {
        requireLoginToken()
    }

    private fun credentials(password: String) = buildJsonObject {
        put("username", env.username)
        put("password", password)
    }

    private fun recordedCredentials(password: String = "<password>") = buildJsonObject {
        put("username", "<username>")
        put("password", password)
    }

    private fun jsonBody(json: JsonObject): RequestBody = json.toString().toRequestBody(JSON)

    private fun requireLoginToken(): String {
        assumeTrue(loginToken != null, "login failed in an earlier step")
        return loginToken!!
    }

    private fun requireDeviceToken(): String {
        assumeTrue(deviceToken != null, "device token creation failed in an earlier step")
        return deviceToken!!
    }

    private fun describe(ex: Exchange, includeBody: Boolean = true): Map<String, Any?> = buildMap<String, Any?> {
        put("status", ex.status)
        put("contentType", ex.contentType)
        put("isJson", ex.json != null)
        put("wwwAuthenticate", ex.header("WWW-Authenticate"))
        if (includeBody) put("body", ex.json ?: JsonPrimitive(ex.bodyText.take(200)))
    }

    private fun summarizeTokens(ex: Exchange): List<Map<String, Any?>> = (ex.json as? JsonArray).orEmpty().map { element ->
        val entry = element as JsonObject
        val token = entry.string("token").orEmpty()
        mapOf(
            "id" to entry.long("id"),
            "scope" to entry.string("scope"),
            "plaintext" to Sanitizer.TOKEN.matches(token),
            "masked" to Sanitizer.MASKED_TOKEN.matches(token),
            "token" to token,
        )
    }

    private fun daysUntil(isoDateTime: String?): Long? = runCatching {
        Duration.between(OffsetDateTime.now(), OffsetDateTime.parse(isoDateTime)).toDays()
    }.getOrNull()

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

    private companion object {
        val JSON = "application/json".toMediaType()

        /** Well-formed but never issued (the fake prefix keeps it unchanged in recordings). */
        const val UNKNOWN_TOKEN = "tda_00000000_0000_0000_0000_00000000dead"
        const val WRONG_PASSWORD_SUFFIX = "not-the-password"
        const val MISSING_ID = 999_999
        const val MAX_THROTTLE_ATTEMPTS = 15
        val MASK_DELAY: Duration = Duration.ofSeconds(16)

        /** allauth headless, app client (Tandoor 2.7+). */
        const val ALLAUTH_LOGIN = "/_allauth/app/v1/auth/login"
        const val ALLAUTH_SESSION = "/_allauth/app/v1/auth/session"
        const val SESSION_HEADER = "X-Session-Token"
        const val ALLAUTH_LOGINS_PER_MINUTE = 3

        /** allauth's rate window (one minute) plus a margin for the server's clock. */
        val RATE_WINDOW: Duration = Duration.ofSeconds(62)

        /** True from Tandoor 2.7.0 on (`api-token-auth` removed), also for `2.7.1-dev` and the like. */
        fun usesAllauthLogin(version: String?): Boolean {
            val parts = Regex("^(\\d+)\\.(\\d+)").find(version.orEmpty())?.groupValues ?: return false
            val major = parts[1].toInt()
            val minor = parts[2].toInt()
            return major > 2 || (major == 2 && minor >= 7)
        }
    }
}
