package casa.moller.tandem.core.domain.storereminder

import casa.moller.tandem.core.model.shopping.GeoPoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Fas 5 (butik lane, plan §7 #34): a store's place from pasted text. The places here are made up,
 * out at sea in the Atlantic (no real store, no real home).
 */
class GeoTextTest {
    private fun assertPlace(lat: Double, lon: Double, text: String) {
        val point = GeoText.parse(text)
        assertNotNull(point, "a place in \"$text\"")
        assertEquals(lat, point!!.latitude, DELTA, "latitude of \"$text\"")
        assertEquals(lon, point.longitude, DELTA, "longitude of \"$text\"")
    }

    @Test
    fun geoLinks() {
        assertPlace(LAT, LON, "geo:12.3456,-45.6789")
        assertPlace(LAT, LON, "geo:12.3456,-45.6789?z=17")
        assertPlace(LAT, LON, "geo:12.3456,-45.6789,12.5;u=35")
        assertPlace(LAT, LON, "GEO:12.3456,-45.6789")
        assertPlace(LAT, LON, "geo:0,0?q=12.3456,-45.6789(Testbutiken)")
        assertPlace(LAT, LON, "geo:0,0?q=12.3456%2C-45.6789")
        // A search word in the query: the link's own place counts.
        assertPlace(LAT, LON, "geo:12.3456,-45.6789?q=Testbutiken")
        assertNull(GeoText.parse("geo:0,0?q=Testbutiken"), "a search is no place")
        assertNull(GeoText.parse("geo:0,0"))
    }

    @Test
    fun mapLinks() {
        assertPlace(LAT, LON, "https://maps.example.com/maps/place/Testbutiken/@12.3,-45.6,17z/data=!3d12.3456!4d-45.6789")
        assertPlace(LAT, LON, "https://maps.example.com/maps/@12.3456,-45.6789,17z")
        assertPlace(LAT, LON, "https://maps.example.com/?q=12.3456,-45.6789")
        assertPlace(LAT, LON, "https://maps.example.com/?ll=12.3456%2C-45.6789&z=16")
        assertPlace(LAT, LON, "https://www.openstreetmap.org/?mlat=12.3456&mlon=-45.6789#map=17/12.3/-45.6")
        assertPlace(LAT, LON, "https://www.openstreetmap.org/#map=17/12.3456/-45.6789")
        assertNull(GeoText.parse("https://maps.example.com/short/abc123"), "a short link holds no place")
    }

    @Test
    fun plainNumbersWithDots() {
        assertPlace(LAT, LON, "12.3456, -45.6789")
        assertPlace(LAT, LON, "12.3456,-45.6789")
        assertPlace(LAT, LON, "12.3456 -45.6789")
        assertPlace(LAT, LON, "12.3456;-45.6789")
        assertPlace(LAT, LON, "  (12.3456, -45.6789)  ")
        assertPlace(LAT, LON, "12.3456 −45.6789")
    }

    @Test
    fun swedishDecimalCommas() {
        assertPlace(LAT, LON, "12,3456 -45,6789")
        assertPlace(LAT, LON, "12,3456; -45,6789")
        assertNull(GeoText.parse("12,3456, -45,6789"), "commas both ways are ambiguous")
    }

    @Test
    fun compassLettersAndDegrees() {
        assertPlace(LAT, LON, "12.3456 N, 45.6789 W")
        assertPlace(LAT, LON, "12.3456° N 45.6789° W")
        assertPlace(LAT, LON, "12,3456 N 45,6789 V")
        assertPlace(-LAT, LON, "12.3456 S, 45.6789 W")
        assertPlace(LAT, -LON, "12.3456 N, 45.6789 E")
        assertPlace(LAT, -LON, "12.3456 N, 45.6789 Ö")
        assertNull(GeoText.parse("-12.3456 S, 45.6789 W"), "a minus and south together")
    }

    @Test
    fun degreesMinutesSeconds() {
        assertPlace(LAT, LON, "12°20'44.2\"N 45°40'44.0\"W")
        assertPlace(LAT, LON, "45°40'44.0\"W, 12°20'44.2\"N")
        assertPlace(LAT, LON, "12°20′44,2″N 45°40′44,0″W")
        assertNull(GeoText.parse("12°20'44.2\"N 45°40'44.0\"N"), "two latitudes")
        assertNull(GeoText.parse("12°75'44.2\"N 45°40'44.0\"W"), "75 minutes")
    }

    @Test
    fun notAPlace() {
        val junk = listOf("", "   ", "Testbutiken", "12, 45", "12 45", "1234.5, 45.6") +
            listOf("95.0, 45.6", "45.6, 190.5", "0.0, 0.0", "geo:", "12.3456")
        for (text in junk) {
            assertNull(GeoText.parse(text), "\"$text\"")
        }
    }

    @Test
    fun aPlaceNeverPrintsItsCoordinates() {
        val text = GeoPoint(LAT, LON).toString()
        assertFalse(text.contains("12.3"), text)
        assertFalse(text.contains("45.6"), text)
    }

    private companion object {
        const val LAT = 12.3456
        const val LON = -45.6789
        const val DELTA = 0.0001
    }
}
