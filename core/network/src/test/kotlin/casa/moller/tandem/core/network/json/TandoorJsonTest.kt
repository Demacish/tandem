package casa.moller.tandem.core.network.json

import casa.moller.tandem.core.network.dto.AccessTokenCreateRequestDto
import casa.moller.tandem.core.network.dto.AccessTokenDto
import casa.moller.tandem.core.network.dto.ServerSettingsDto
import casa.moller.tandem.core.network.dto.SpaceDto
import casa.moller.tandem.core.network.dto.TokenAuthRequestDto
import casa.moller.tandem.core.network.dto.TokenAuthResponseDto
import casa.moller.tandem.core.network.dto.UserDto
import casa.moller.tandem.core.network.dto.UserPreferenceDto
import casa.moller.tandem.core.network.testing.Fixture
import java.time.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TandoorJsonTest {
    private fun <T> decodeFixture(name: String, serializer: KSerializer<T>): T =
        TandoorJson.decodeFromString(serializer, Fixture.load(name).bodyText)

    @Test
    fun serverSettings() {
        val settings = decodeFixture("01-server-settings-no-auth", ServerSettingsDto.serializer())
        assertEquals("2.6.13", settings.version)
        assertFalse(settings.hosted)
        assertFalse(settings.debug)
        // A string on 2.6.13, a number in the schema: both are read.
        assertEquals(5, settings.shoppingMinAutosyncIntervalOrNull())
        val numeric = TandoorJson.decodeFromString(ServerSettingsDto.serializer(), """{"shopping_min_autosync_interval":7}""")
        assertEquals(7, numeric.shoppingMinAutosyncIntervalOrNull())
        assertNull(numeric.version)
    }

    @Test
    fun loginResponse() {
        val login = decodeFixture("03-login-json", TokenAuthResponseDto.serializer())
        assertEquals(1L, login.id)
        assertEquals("tda_00000000_0000_0000_0000_000000000001", login.token)
        assertEquals("read write app", login.scope)
        assertEquals(1L, login.userId)
        assertEquals(1L, login.test)
        assertFalse("toString must not leak the token", login.toString().contains("tda_"))
    }

    @Test
    fun accessTokens() {
        val created = decodeFixture("08-access-token-create", AccessTokenDto.serializer())
        assertEquals(2L, created.id)
        assertEquals("read write", created.scope)
        val masked = decodeFixture("10-access-token-list-after-16s", ListSerializer(AccessTokenDto.serializer()))
        assertEquals(listOf(1L, 2L), masked.map { it.id })
        assertTrue(masked.all { it.token.startsWith("tda_****") })
    }

    @Test
    fun userPreferenceSpaceAndUser() {
        val user = decodeFixture("11-user-by-id", UserDto.serializer())
        assertEquals("contract-admin", user.displayName)
        val prefs = decodeFixture("12-user-preference-list", ListSerializer(UserPreferenceDto.serializer()))
        assertEquals(1, prefs.size)
        assertEquals(1L, prefs.single().user?.id)
        val space = decodeFixture("13-space-current", SpaceDto.serializer())
        assertEquals(1L, space.id)
        assertEquals(false, space.householdSetupCompleted)
        assertEquals(true, space.aiEnabled)
        assertEquals("contract-admin", space.createdBy?.username)
    }

    @Test
    fun toleratesUnknownFieldsNullsAndMissingFields() {
        val user = TandoorJson.decodeFromString(
            UserDto.serializer(),
            """{"id":3,"username":null,"display_name":null,"brand_new_field":{"x":[1,2]},"is_active":null}""",
        )
        assertEquals(UserDto(id = 3), user)
        assertEquals(TokenAuthResponseDto(), TandoorJson.decodeFromString(TokenAuthResponseDto.serializer(), "{}"))
    }

    @Test
    fun requestBodies() {
        assertEquals(
            """{"scope":"read write","expires":"2027-10-09T06:00:00Z"}""",
            TandoorJson.encodeToString(
                AccessTokenCreateRequestDto.serializer(),
                AccessTokenCreateRequestDto("read write", "2027-10-09T06:00:00Z"),
            ),
        )
        val login = TokenAuthRequestDto("anna", "hunter2")
        assertEquals("""{"username":"anna","password":"hunter2"}""", TandoorJson.encodeToString(TokenAuthRequestDto.serializer(), login))
        assertFalse(login.toString().contains("hunter2"))
    }

    @Test
    fun datesInEveryObservedShape() {
        val expected = Instant.parse("2027-10-09T05:46:13Z")
        assertEquals(expected, TandoorDates.parseInstantOrNull("2027-10-09T05:46:13Z"))
        assertEquals(expected, TandoorDates.parseInstantOrNull("2027-10-09T07:46:13+02:00"))
        assertEquals(
            Instant.parse("2027-10-09T05:46:13.123456Z"),
            TandoorDates.parseInstantOrNull("2027-10-09T07:46:13.123456+02:00"),
        )
        assertEquals(
            Instant.parse("2031-10-08T05:46:12.345678Z"),
            TandoorDates.parseInstantOrNull("2031-10-08T05:46:12.345678Z"),
        )
        assertNull(TandoorDates.parseInstantOrNull(null))
        assertNull(TandoorDates.parseInstantOrNull(""))
        assertNull(TandoorDates.parseInstantOrNull("next tuesday"))
        assertEquals("2027-10-09T05:46:13Z", TandoorDates.format(Instant.parse("2027-10-09T05:46:13.987Z")))
    }
}
