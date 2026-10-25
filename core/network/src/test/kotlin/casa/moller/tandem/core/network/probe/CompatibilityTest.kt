package casa.moller.tandem.core.network.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompatibilityTest {

    @Test
    fun parsesVersions() {
        assertEquals(TandoorVersion(2, 6, 13), TandoorVersion.parse("2.6.13"))
        assertEquals(TandoorVersion(2, 6, 13), TandoorVersion.parse(" v2.6.13 "))
        assertEquals(TandoorVersion(2, 7, 0), TandoorVersion.parse("2.7"))
        assertEquals(TandoorVersion(2, 7, 0), TandoorVersion.parse("2.7.0-rc1"))
        assertEquals(TandoorVersion(3, 0, 1), TandoorVersion.parse("3.0.1+build.5"))
        assertNull(TandoorVersion.parse(null))
        assertNull(TandoorVersion.parse(""))
        assertNull(TandoorVersion.parse("develop"))
        assertNull(TandoorVersion.parse("abc1234"))
        assertNull(TandoorVersion.parse("2"))
        assertTrue(TandoorVersion(2, 6, 13) < TandoorVersion(2, 6, 15))
        assertTrue(TandoorVersion(2, 10, 0) > TandoorVersion(2, 9, 9))
    }

    @Test
    fun classifiesCompatibility() {
        val table = mapOf(
            "2.6.0" to Compatibility.SUPPORTED,
            "2.6.13" to Compatibility.SUPPORTED,
            "2.6.15" to Compatibility.SUPPORTED,
            // Phase 1: only the tested versions are SUPPORTED; later 2.6.x run in safe mode.
            "2.6.16" to Compatibility.UNTESTED_NEWER,
            "2.6.99" to Compatibility.UNTESTED_NEWER,
            "2.7.0" to Compatibility.UNTESTED_NEWER,
            "2.7.0-beta1" to Compatibility.UNTESTED_NEWER,
            "2.7.1" to Compatibility.SUPPORTED,
            "2.7.2" to Compatibility.UNTESTED_NEWER,
            "2.8.0" to Compatibility.UNTESTED_NEWER,
            "3.0.0" to Compatibility.UNTESTED_NEWER,
            "2.5.3" to Compatibility.UNTESTED_OLDER,
            "2.5.99" to Compatibility.UNTESTED_OLDER,
            "2.0.0" to Compatibility.UNTESTED_OLDER,
            "1.99.99" to Compatibility.TOO_OLD,
            "1.5.19" to Compatibility.TOO_OLD,
            "1.5.9" to Compatibility.TOO_OLD,
            "develop" to Compatibility.UNRECOGNIZED,
            "" to Compatibility.UNRECOGNIZED,
        )
        table.forEach { (version, expected) ->
            assertEquals("version '$version'", expected, TandoorCompatibility.classify(version))
        }
    }
}
