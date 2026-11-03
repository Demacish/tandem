package casa.moller.tandem.feature.recipes.recipeimport

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import casa.moller.tandem.core.data.recipe.RecipeImportProblem
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Fas 3 (import lane): the photo or PDF the AI import sends, read on a worker thread. A photo is
 * decoded with the EXIF orientation applied and scaled while decoding to at most [MAX_EDGE_PX]
 * on the long side, then encoded anew as a JPEG at [JPEG_QUALITY] (no metadata goes along, as
 * the fridge photo of Fas S). A PDF goes as it is, up to the repository's size limit. Nothing is
 * kept on the device.
 */
internal object ImportFiles {
    const val JPEG: String = "image/jpeg"
    const val PDF: String = "application/pdf"
    const val MAX_EDGE_PX: Int = 1600
    const val JPEG_QUALITY: Int = 85

    /** The file behind [uri] as the action to send, or null when it cannot be read or is neither an image nor a PDF. */
    fun read(context: Context, uri: Uri, mimeType: String?): RecipeImportAction.ImportFile? = try {
        val type = mimeType ?: context.contentResolver.getType(uri)
        when {
            type == PDF -> readPdf(context, uri)
            type?.startsWith("image/") == true -> readImage(context, uri)
            else -> null
        }
    } catch (e: IOException) {
        null
    } catch (e: SecurityException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun readPdf(context: Context, uri: Uri): RecipeImportAction.ImportFile? {
        val bytes = context.contentResolver.openInputStream(uri)?.use { stream ->
            readUpTo(stream, RecipeImportProblem.MAX_FILE_BYTES + 1)
        } ?: return null
        return RecipeImportAction.ImportFile(bytes, PDF, "recept.pdf")
    }

    private fun readImage(context: Context, uri: Uri): RecipeImportAction.ImportFile {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val (width, height) = targetSize(info.size.width, info.size.height)
            decoder.setTargetSize(width, height)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val out = ByteArrayOutputStream()
        try {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) throw IOException("could not encode")
        } finally {
            bitmap.recycle()
        }
        return RecipeImportAction.ImportFile(out.toByteArray(), JPEG, "recept.jpg")
    }

    /** [width] × [height] scaled down so the long side is at most [MAX_EDGE_PX] (never up). */
    fun targetSize(width: Int, height: Int): Pair<Int, Int> {
        val longSide = maxOf(width, height)
        if (longSide <= MAX_EDGE_PX || longSide == 0) return width to height
        val scale = MAX_EDGE_PX.toDouble() / longSide
        return maxOf(1, (width * scale).toInt()) to maxOf(1, (height * scale).toInt())
    }

    private fun readUpTo(stream: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER)
        var total = 0
        while (total <= limit) {
            val read = stream.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            total += read
        }
        return out.toByteArray()
    }

    private const val BUFFER = 16 * 1024
}
