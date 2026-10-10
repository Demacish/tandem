package casa.moller.tandem.contract

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/** Runs in pr.yml (no server needed): recordings must never leak tokens or the password. */
class SanitizerTest {
    private val loginToken = "tda_1a2b3c4d_5e6f_4a7b_8c9d_0e1f2a3b4c5d"
    private val deviceToken = "tda_99999999_8888_4777_a666_555555554444"

    @Test
    fun realTokensBecomeNumberedFakesInOrderOfAppearance() {
        val sanitizer = Sanitizer(emptyList())
        val text = "login=$loginToken device=$deviceToken again=$loginToken"
        assertEquals(
            "login=${Sanitizer.fakeToken(1)} device=${Sanitizer.fakeToken(2)} again=${Sanitizer.fakeToken(1)}",
            sanitizer.sanitize(text),
        )
        assertEquals("tda_00000000_0000_0000_0000_000000000002", Sanitizer.fakeToken(2))
    }

    @Test
    fun fakeAndUnknownProbeTokensStayUnchanged() {
        val probe = "tda_00000000_0000_0000_0000_00000000dead"
        assertEquals("Bearer $probe", Sanitizer(emptyList()).sanitize("Bearer $probe"))
    }

    @Test
    fun maskedTokensMapToTheLastFourOfTheirFake() {
        val sanitizer = Sanitizer(emptyList())
        sanitizer.fakeTokenFor(loginToken)
        sanitizer.fakeTokenFor(deviceToken)
        val masked = Sanitizer.MASK_PREFIX + "4444"
        assertEquals(Sanitizer.MASK_PREFIX + "0002", sanitizer.sanitize(masked))
        assertEquals(Sanitizer.MASK_PREFIX + "0000", sanitizer.sanitize(Sanitizer.MASK_PREFIX + "abcd"))
    }

    @Test
    fun passwordIsRedactedEverywhere() {
        val sanitizer = Sanitizer(listOf("s3cret-generated-password"))
        val json = Json.parseToJsonElement(
            """{"password":"s3cret-generated-password","nested":[{"note":"pw=s3cret-generated-password"}]}""",
        )
        val clean = sanitizer.sanitize(json).toString()
        assertFalse(clean.contains("s3cret"), clean)
        assertEquals(
            "pw=<redacted>",
            sanitizer.sanitize(json).jsonObject["nested"]!!.jsonArray[0].jsonObject["note"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun dateTimesKeepTheirFormatButNotTheirValue() {
        assertEquals("2000-01-01T00:00:00.000000+02:00", Sanitizer.normalizeDateTime("2031-10-08T09:12:33.123456+02:00"))
        assertEquals("2000-01-01T00:00:00Z", Sanitizer.normalizeDateTime("2027-10-09T07:00:00Z"))
        assertEquals("2000-01-01T00:00+01:00", Sanitizer.normalizeDateTime("2027-01-09T07:00+01:00"))
        assertEquals("2027-10-09", Sanitizer.normalizeDateTime("2027-10-09"))
        assertEquals("read write", Sanitizer.normalizeDateTime("read write"))
    }

    @Test
    fun numbersAndBooleansAreUntouched() {
        val json = Json.parseToJsonElement("""{"id":3,"active":true,"household":null}""")
        assertEquals(json, Sanitizer(emptyList()).sanitize(json))
    }
}
