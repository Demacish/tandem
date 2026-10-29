package casa.moller.tandem.feature.scan.engine

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import casa.moller.tandem.core.model.scan.ReceiptLine
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

// Fas S (grund lane): the on-device recognisers with the BUNDLED ML Kit models (no download through
// Play services, works offline and in the sideload variant). First implementations the skanning lane
// builds on (CameraX binding, the photo picker, throttling); they also keep R8's view of the libraries
// honest in the minified smoke build (docs/BYGGE.md "Fas S – kontrakt").

/** EAN-13/EAN-8/UPC barcodes (food packages). Create lazily, [close] when the screen goes. */
class BarcodeEngine {
    private val scanner by lazy {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E)
                .build(),
        )
    }

    /** A CameraX analyzer that reports every recognised code (digits only) to [onCode]. */
    fun analyzer(onCode: (String) -> Unit): ImageAnalysis.Analyzer = ImageAnalysis.Analyzer { proxy: ImageProxy ->
        val image = InputImage.fromBitmap(proxy.toBitmap(), proxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener { codes -> codes.mapNotNull { it.rawValue }.filter { it.all(Char::isDigit) }.forEach(onCode) }
            .addOnCompleteListener { proxy.close() }
    }

    /** The codes in one still image (a photo from the picker). */
    suspend fun read(image: InputImage): List<String> = suspendCancellableCoroutine { continuation ->
        scanner.process(image)
            .addOnSuccessListener { codes -> continuation.resume(codes.mapNotNull { it.rawValue }) }
            .addOnFailureListener { continuation.resumeWithException(it) }
    }

    fun close() = scanner.close()
}

/** Latin text (receipts) in reading order, one [ReceiptLine] per recognised line. */
class ReceiptTextEngine {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    suspend fun read(image: InputImage): List<ReceiptLine> = suspendCancellableCoroutine { continuation ->
        recognizer.process(image)
            .addOnSuccessListener { text ->
                val lines = text.textBlocks.flatMap { block -> block.lines.map { it.text } }
                continuation.resume(lines.mapIndexed { index, line -> ReceiptLine(index, line) })
            }
            .addOnFailureListener { continuation.resumeWithException(it) }
    }

    fun close() = recognizer.close()
}
