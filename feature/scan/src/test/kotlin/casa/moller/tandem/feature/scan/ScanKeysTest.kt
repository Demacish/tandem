package casa.moller.tandem.feature.scan

import casa.moller.tandem.core.ui.navigation.FullScreenDestination
import casa.moller.tandem.feature.scan.navigation.AiSettingsKey
import casa.moller.tandem.feature.scan.navigation.BarcodeScanKey
import casa.moller.tandem.feature.scan.navigation.FridgeScanKey
import casa.moller.tandem.feature.scan.navigation.ReceiptScanKey
import casa.moller.tandem.feature.scan.navigation.ScanReviewKey
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Fas S scan keys survive process death; the camera pages and the review are full screen. */
class ScanKeysTest {
    private fun <T> roundTrip(serializer: KSerializer<T>, key: T): T =
        Json.decodeFromString(serializer, Json.encodeToString(serializer, key))

    @Test
    fun keysSurviveSerialization() {
        assertEquals(BarcodeScanKey(2), roundTrip(BarcodeScanKey.serializer(), BarcodeScanKey(2)))
        assertEquals(ReceiptScanKey(), roundTrip(ReceiptScanKey.serializer(), ReceiptScanKey()))
        assertEquals(FridgeScanKey(-1), roundTrip(FridgeScanKey.serializer(), FridgeScanKey(-1)))
        assertEquals(ScanReviewKey("abc"), roundTrip(ScanReviewKey.serializer(), ScanReviewKey("abc")))
        assertEquals(AiSettingsKey, roundTrip(AiSettingsKey.serializer(), AiSettingsKey))
    }

    @Test
    fun cameraPagesAreFullScreenAndTheSettingsAreNot() {
        assertTrue((BarcodeScanKey() as Any) is FullScreenDestination)
        assertTrue((ScanReviewKey("x") as Any) is FullScreenDestination)
        assertFalse((AiSettingsKey as Any) is FullScreenDestination)
    }
}
