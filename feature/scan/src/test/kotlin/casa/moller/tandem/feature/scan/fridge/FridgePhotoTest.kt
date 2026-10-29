package casa.moller.tandem.feature.scan.fridge

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** The photo before it leaves the phone: at most 1600 px, JPEG 85 %, upright, no EXIF/location (plan §8.8). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FridgePhotoTest {

    @Test
    fun theLongSideBecomesAtMost1600AndSmallPhotosStay() {
        assertEquals(1600 to 1200, FridgePhoto.targetSize(4000, 3000))
        assertEquals(900 to 1600, FridgePhoto.targetSize(2700, 4800))
        assertEquals(1600 to 1600, FridgePhoto.targetSize(3000, 3000))
        assertEquals(800 to 600, FridgePhoto.targetSize(800, 600))
        assertEquals(1600 to 1, FridgePhoto.targetSize(16000, 2))
        assertEquals(2, FridgePhoto.sampleSize(4000, 3000))
        assertEquals(1, FridgePhoto.sampleSize(3000, 2000))
        assertEquals(1, FridgePhoto.sampleSize(800, 600))
    }

    @Test
    fun aCameraPhotoIsScaledRotatedAndLosesItsExif() {
        val original = withExif(jpeg(width = 3200, height = 2400))
        assertTrue(JpegMetadata.hasMetadata(original))

        val sent = FridgePhoto.fromCameraJpeg(original, rotationDegrees = 90)

        assertFalse(JpegMetadata.hasMetadata(sent))
        assertFalse(String(sent, Charsets.ISO_8859_1).contains("GPS"))
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(sent, 0, sent.size, bounds)
        // Portrait after the rotation, the long side 1600.
        assertEquals(1200, bounds.outWidth)
        assertEquals(1600, bounds.outHeight)
        assertTrue(sent.size < original.size)
    }

    @Test
    fun aSmallUprightPhotoIsReEncodedWithoutMetadata() {
        val sent = FridgePhoto.fromCameraJpeg(withExif(jpeg(width = 640, height = 480)), rotationDegrees = 0)
        assertFalse(JpegMetadata.hasMetadata(sent))
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(sent, 0, sent.size, bounds)
        assertEquals(640, bounds.outWidth)
        assertEquals(480, bounds.outHeight)
    }

    @Test
    fun stripKeepsTheImageAndDropsApp1ToApp15AndComments() {
        val plain = jpeg(width = 64, height = 48)
        val tagged = withExif(plain)
        val stripped = JpegMetadata.strip(tagged)
        assertFalse(JpegMetadata.hasMetadata(stripped))
        assertArrayEquals(JpegMetadata.strip(plain), stripped)
        assertTrue(BitmapFactory.decodeByteArray(stripped, 0, stripped.size) != null)
        // Not a JPEG: left alone.
        val other = byteArrayOf(1, 2, 3, 4, 5)
        assertArrayEquals(other, JpegMetadata.strip(other))
    }

    private fun jpeg(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(200, 220, 240))
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        return out.toByteArray()
    }

    /** [jpeg] with a SYNTHETIC APP1 "Exif" segment (a fake GPS tag text) and a comment right after SOI. */
    private fun withExif(jpeg: ByteArray): ByteArray {
        val exifPayload = "Exif\u0000\u0000GPSLatitude=0.000000;GPSLongitude=0.000000;Make=Example".toByteArray(Charsets.ISO_8859_1)
        val comment = "example comment".toByteArray(Charsets.ISO_8859_1)
        val out = ByteArrayOutputStream()
        out.write(jpeg, 0, 2)
        out.write(segment(0xE1, exifPayload))
        out.write(segment(0xFE, comment))
        out.write(jpeg, 2, jpeg.size - 2)
        return out.toByteArray()
    }

    private fun segment(marker: Int, payload: ByteArray): ByteArray {
        val length = payload.size + 2
        return byteArrayOf(0xFF.toByte(), marker.toByte(), (length shr 8).toByte(), (length and 0xFF).toByte()) + payload
    }
}
