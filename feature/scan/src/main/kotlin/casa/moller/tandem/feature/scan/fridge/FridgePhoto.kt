package casa.moller.tandem.feature.scan.fridge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The fridge photo before it is sent (plan §8.8): at most [MAX_EDGE_PX] on the long side, JPEG
 * quality [JPEG_QUALITY], upright, and WITHOUT metadata: the bitmap is encoded anew (no EXIF, no
 * location, no camera data), and [JpegMetadata.strip] removes any APPn/COM segment that might still
 * be there. Everything happens in memory; the photo is never written to a file.
 */
internal object FridgePhoto {
    const val MAX_EDGE_PX: Int = 1600
    const val JPEG_QUALITY: Int = 85

    /** The size [width] × [height] is scaled to: the long side at most [maxEdge], never enlarged. */
    fun targetSize(width: Int, height: Int, maxEdge: Int = MAX_EDGE_PX): Pair<Int, Int> {
        require(width > 0 && height > 0) { "empty image" }
        val longest = max(width, height)
        if (longest <= maxEdge) return width to height
        val scale = maxEdge.toDouble() / longest
        return (width * scale).roundToInt().coerceIn(1, maxEdge) to (height * scale).roundToInt().coerceIn(1, maxEdge)
    }

    /** The largest power of two that keeps the decoded image at least [maxEdge] on its long side. */
    fun sampleSize(width: Int, height: Int, maxEdge: Int = MAX_EDGE_PX): Int {
        var sample = 1
        while (max(width, height) / (sample * 2) >= maxEdge) sample *= 2
        return sample
    }

    /** A camera JPEG ([rotationDegrees] from CameraX's image info) → the photo to send. */
    fun fromCameraJpeg(jpeg: ByteArray, rotationDegrees: Int): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("not an image")
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight) }
        val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options) ?: throw IOException("not an image")
        return encode(scaledAndRotated(decoded, rotationDegrees))
    }

    /** A picked image (system photo picker): ImageDecoder applies the EXIF orientation and scales while decoding. */
    fun fromUri(context: Context, uri: Uri): ByteArray {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val (width, height) = targetSize(info.size.width, info.size.height)
            decoder.setTargetSize(width, height)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        return encode(scaledAndRotated(bitmap, 0))
    }

    /** JPEG at [JPEG_QUALITY] without metadata; recycles [bitmap]. */
    fun encode(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        try {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) throw IOException("could not encode")
        } finally {
            bitmap.recycle()
        }
        return JpegMetadata.strip(out.toByteArray())
    }

    private fun scaledAndRotated(source: Bitmap, rotationDegrees: Int): Bitmap {
        val (width, height) = targetSize(source.width, source.height)
        if (width == source.width && height == source.height && rotationDegrees % FULL_TURN == 0) return source
        val matrix = Matrix().apply {
            postScale(width.toFloat() / source.width, height.toFloat() / source.height)
            postRotate(rotationDegrees.toFloat())
        }
        val result = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        if (result !== source) source.recycle()
        return result
    }

    private const val FULL_TURN = 360
}

/**
 * JPEG segments without the pixels' business: EXIF (APP1, also GPS), XMP, ICC, maker notes (APP2 …
 * APP15) and comments (COM) are dropped; APP0 (JFIF) and everything from the image data on are kept.
 * Pure: also used by the tests to prove that no metadata leaves the phone.
 */
internal object JpegMetadata {
    private const val MARKER = 0xFF
    private const val SOI = 0xD8
    private const val SOS = 0xDA
    private const val APP1 = 0xE1
    private const val APP15 = 0xEF
    private const val COM = 0xFE

    fun strip(jpeg: ByteArray): ByteArray {
        if (jpeg.size < 4 || jpeg.u(0) != MARKER || jpeg.u(1) != SOI) return jpeg
        val out = ByteArrayOutputStream(jpeg.size)
        out.write(jpeg, 0, 2)
        var index = 2
        var end = segmentEnd(jpeg, index)
        while (end != null) {
            if (!isMetadata(jpeg.u(index + 1))) out.write(jpeg, index, end - index)
            index = end
            end = segmentEnd(jpeg, index)
        }
        out.write(jpeg, index, jpeg.size - index)
        return out.toByteArray()
    }

    /** True when an APP1 (EXIF/XMP) … APP15 or COM segment is present before the image data. */
    fun hasMetadata(jpeg: ByteArray): Boolean {
        var index = 2
        while (index + 4 <= jpeg.size && jpeg.u(index) == MARKER) {
            val marker = jpeg.u(index + 1)
            if (marker == SOS) return false
            if (isMetadata(marker)) return true
            index += 2 + ((jpeg.u(index + 2) shl 8) or jpeg.u(index + 3))
        }
        return false
    }

    private fun isMetadata(marker: Int): Boolean = marker in APP1..APP15 || marker == COM

    /** The end of the marker segment at [index], or null at the image data (SOS) or a broken segment. */
    private fun segmentEnd(jpeg: ByteArray, index: Int): Int? {
        if (index + 4 > jpeg.size || jpeg.u(index) != MARKER || jpeg.u(index + 1) == SOS) return null
        val length = (jpeg.u(index + 2) shl 8) or jpeg.u(index + 3)
        val end = index + 2 + length
        return end.takeIf { length >= 2 && it <= jpeg.size }
    }

    private fun ByteArray.u(index: Int): Int = this[index].toInt() and 0xFF
}
