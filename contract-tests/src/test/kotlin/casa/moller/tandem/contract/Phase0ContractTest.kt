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
 * Skipped unless TANDOOR_URL is set. Never run against a production server: it revokes tokens and
 * deliberately exhausts the 10/day login throttle.
 */
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

    // --- b. api-token-auth ---------------------------------------------------------------------

    @Test
    @Order(20)
    fun loginWithJson() {
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

    @Test
    @Order(21)
    fun loginWithFormEncoding() {
        val form = FormBody.Builder().add("username", env.username).add("password", env.password).build()
        checkAlternativeLogin("login-form", form)
    }

    @Test
    @Order(22)
    fun loginWithMultipart() {
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
        val ex = login(
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
        val ex = login("login-missing-fields", jsonBody(empty), empty)
        facts["login.missingFields"] = describe(ex)
        assertEquals(400, ex.status, ex.toString())
    }

    // --- c. Per-device token -------------------------------------------------------------------

    @Test
    @Order(30)
    fun createDeviceToken() {
        val login = requireLoginToken()
        val expires = OffsetDateTime.now(ZoneOffset.UTC).plusYears(1).truncatedTo(ChronoUnit.SECONDS)
        val request = buildJsonObject {
            put("scope", "read write")
            put("expires", expires.toString())
        }
        val ex = http.call(
            "access-token-create",
            "POST",
            "/api/access-token/",
            bearer = login,
            body = jsonBody(request),
            recordBody = request,
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

        val withLoginToken = http.call(
            "space-current-login-token",
            "GET",
            "/api/space/current/",
            bearer = requireLoginToken(),
            record = false,
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
        assertTrue(path.header("Location").orEmpty().startsWith("/accounts/login/"), "Location: ${path.header("Location")}")
    }

    // --- f. Revocation and token reuse ---------------------------------------------------------

    @Test
    @Order(60)
    fun revokingTheLoginTokenLeavesTheDeviceTokenWorking() {
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

    @Test
    @Order(61)
    fun loginAfterTheLoginTokenWasRevoked() {
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

    // --- Helpers --------------------------------------------------------------------------------

    private fun login(name: String, body: RequestBody, recordBody: JsonElement, record: Boolean = true): Exchange {
        loginCalls += 1
        return http.call(name, "POST", "/api-token-auth/", body = body, recordBody = recordBody, record = record)
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
    }
}
