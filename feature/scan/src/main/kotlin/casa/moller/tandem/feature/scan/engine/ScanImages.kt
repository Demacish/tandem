package casa.moller.tandem.feature.scan.engine

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import casa.moller.tandem.feature.scan.fridge.FridgePhoto
import com.google.mlkit.vision.common.InputImage
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fas S review (fix-ui): the images the receipt and barcode scanners read, decoded OFF the main
 * thread and downsampled. `InputImage.fromFilePath` (a full-size decode, an EXIF read and a rotated
 * copy, on the calling thread) blocked the UI for a cloud item of the photo picker and could run
 * out of memory on a 50 MP photo; `fromMediaImage` on a captured JPEG did the same full decode on
 * the main thread. Nothing here keeps or writes the image.
 */
internal object ScanImages {
    /** Receipts: small print stays readable (the synthetic receipts are read the same at this size). */
    const val RECEIPT_MAX_EDGE: Int = 2560

    /** Barcodes: the bars need far fewer pixels. */
    const val BARCODE_MAX_EDGE: Int = 1600

    /** A picked image (system photo picker): ImageDecoder applies the EXIF orientation and scales while decoding. */
    suspend fun fromUri(context: Context, uri: Uri, maxEdge: Int): InputImage = withContext(Dispatchers.IO) {
        // ML Kit (also its InputImage) needs its context; started here, not at app start.
        MlKitStartup.ensure(context.applicationContext)
        val source = ImageDecoder.createSource(context.applicationContext.contentResolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val (width, height) = FridgePhoto.targetSize(info.size.width, info.size.height, maxEdge)
            decoder.setTargetSize(width, height)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        InputImage.fromBitmap(bitmap, 0)
    }

    /**
     * A JPEG CameraX just took (its bytes were copied and the frame closed at once), decoded with
     * a power-of-two sample size; ML Kit turns it by [rotationDegrees].
     */
    suspend fun fromJpeg(context: Context, jpeg: ByteArray, rotationDegrees: Int, maxEdge: Int): InputImage =
        withContext(Dispatchers.Default) { decodeJpeg(context, jpeg, rotationDegrees, maxEdge) }

    private fun decodeJpeg(context: Context, jpeg: ByteArray, rotationDegrees: Int, maxEdge: Int): InputImage {
        MlKitStartup.ensure(context.applicationContext)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("not an image")
        val options = BitmapFactory.Options().apply { inSampleSize = FridgePhoto.sampleSize(bounds.outWidth, bounds.outHeight, maxEdge) }
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options) ?: throw IOException("not an image")
        return InputImage.fromBitmap(bitmap, rotationDegrees)
    }
}
