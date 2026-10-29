package casa.moller.tandem.feature.scan.engine

import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import casa.moller.tandem.core.domain.scan.TextFragment
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

// Fas S: the on-device recognisers with the BUNDLED ML Kit models (no download through Play
// services, works offline and in the sideload variant; docs/BYGGE.md "Fas S – kontrakt"). The
// grund lane added them so R8 sees the libraries; the skanning lane uses them in the scanners.
// Nothing here stores or logs an image or its text.

/** EAN-13/EAN-8/UPC-A/UPC-E barcodes (food packages). Create lazily, [close] when the screen goes. */
class BarcodeEngine {
    private val scanner by lazy {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E)
                .build(),
        )
    }

    /**
     * A CameraX analyzer that reports every recognised code (digits only) to [onCode], on the main
     * thread. One frame at a time: the frame is closed when ML Kit is done with it, and CameraX
     * drops the frames that came meanwhile (`STRATEGY_KEEP_ONLY_LATEST`).
     */
    @OptIn(ExperimentalGetImage::class)
    fun analyzer(onCode: (String) -> Unit): ImageAnalysis.Analyzer = ImageAnalysis.Analyzer { proxy: ImageProxy ->
        val media = proxy.image
        if (media == null) {
            proxy.close()
            return@Analyzer
        }
        scanner.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
            .addOnSuccessListener { codes -> codes.digitsOnly().forEach(onCode) }
            .addOnCompleteListener { proxy.close() }
    }

    /** The codes in one still image (a photo from the system photo picker). */
    suspend fun read(image: InputImage): List<String> = suspendCancellableCoroutine { continuation ->
        scanner.process(image)
            .addOnSuccessListener { codes -> continuation.resume(codes.digitsOnly()) }
            .addOnFailureListener { continuation.resumeWithException(it) }
    }

    fun close() = scanner.close()

    private fun List<Barcode>.digitsOnly(): List<String> =
        mapNotNull { it.rawValue?.trim() }.filter { code -> code.isNotEmpty() && code.all(Char::isDigit) }.distinct()
}

/**
 * Latin text (receipts), on the device: every recognised line with its box, for core:domain
 * `ReceiptLayout` (rows by geometry). The image is only read, never kept.
 */
class ReceiptTextEngine {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    suspend fun read(image: InputImage): List<TextFragment> = suspendCancellableCoroutine { continuation ->
        recognizer.process(image)
            .addOnSuccessListener { text ->
                val fragments = text.textBlocks.flatMap { block -> block.lines }.mapNotNull { line ->
                    val box = line.boundingBox ?: return@mapNotNull null
                    TextFragment(line.text, box.left, box.top, box.right, box.bottom)
                }
                continuation.resume(fragments)
            }
            .addOnFailureListener { continuation.resumeWithException(it) }
    }

    /** The text of a photo CameraX just took; the frame is closed (and gone) when this returns. */
    @OptIn(ExperimentalGetImage::class)
    suspend fun read(proxy: ImageProxy): List<TextFragment> = try {
        val media = proxy.image
        if (media == null) emptyList() else read(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
    } finally {
        proxy.close()
    }

    fun close() = recognizer.close()
}
