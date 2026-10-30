package casa.moller.tandem.core.sync.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Fas S review (fix-data): the codes a barcode lookup accepts, UPC-E expanded to the UPC-A that Open Food Facts indexes. */
class GtinTest {
    @Test
    fun upcEIsExpandedByItsLastBodyDigit() {
        // d6 = 0–2, 3, 4 and 5–9.
        assertEquals("042100005264", Gtin.expandUpcE("04252614"))
        assertEquals("012300000451", Gtin.expandUpcE("01234531"))
        assertEquals("012340000053", Gtin.expandUpcE("01234543"))
        assertEquals("012345000065", Gtin.expandUpcE("01234565"))
        // Only number systems 0 and 1.
        assertNull(Gtin.expandUpcE("24252614"))
        assertNull(Gtin.expandUpcE("0425261"))
    }

    @Test
    fun aValidCodeIsLookedUpAsItIsAndAUpcEAsItsUpcA() {
        assertEquals("2000000000015", Gtin.normalized("2000000000015"))
        assertEquals("042100005264", Gtin.normalized("042100005264"))
        // A valid EAN-8 stays an EAN-8 (never reinterpreted).
        assertEquals("73513537", Gtin.normalized("73513537"))
        assertEquals("042100005264", Gtin.normalized("04252614"))
        assertEquals("012300000451", Gtin.normalized("01234531"))
        // A UPC-E with a wrong check digit, and anything else.
        assertNull(Gtin.normalized("04252615"))
        assertNull(Gtin.normalized("737628064503"))
        assertNull(Gtin.normalized("12345"))
    }
}
