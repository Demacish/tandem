package casa.moller.tandem.feature.recipes.recipeimport

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import casa.moller.tandem.core.data.recipe.RecipeImportProblem
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import kotlin.time.Duration.Companion.days

/**
 * Fas 3 (import lane): the photo or PDF the AI import sends, read on a worker thread. A photo is
 * decoded with the EXIF orientation applied and scaled while decoding to at most [MAX_EDGE_PX]
 * on the long side, then encoded anew as a JPEG at [JPEG_QUALITY] (no metadata goes along, as
 * the fridge photo of Fas S). A PDF goes as it is, up to the repository's size limit.
 *
 * Photographed pages wait as cache files, and the file that was sent stays in the cache while the
 * import or the editor reviews it (plan §8.4: the original beside the AI's reading). Those files
 * are deleted when the page closes; [sweep] takes what a killed process left.
 */
internal object ImportFiles {
    const val JPEG: String = "image/jpeg"
    const val PDF: String = "application/pdf"
    const val MAX_EDGE_PX: Int = 1600
    const val JPEG_QUALITY: Int = 85

    /** The folder of the session's files, in the cache. */
    private const val DIRECTORY = "recipe-import"

    /** A file older than this has no page left to show it: its process is gone. */
    private val STALE_AFTER_MILLIS = 1.days.inWholeMilliseconds

    /** The file behind [uri] as the action to send, or null when it cannot be read or is neither an image nor a PDF. */
    fun read(context: Context, uri: Uri, mimeType: String?): RecipeImportAction.ImportFile? = try {
        val type = typeOf(context, uri, mimeType)
        when {
            type == PDF -> readPdf(context, uri)
            type?.startsWith("image/") == true -> readImage(context, uri)
            else -> null
        }?.let { file -> keepingSource(context, file) }
    } catch (e: IOException) {
        null
    } catch (e: SecurityException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    /** The type of [uri]: the one declared, else the content provider's. */
    fun typeOf(context: Context, uri: Uri, mimeType: String?): String? = mimeType ?: context.contentResolver.getType(uri)

    /** An image as a page waiting to be sent (scaled, a cache file): its path, or null when it cannot be read. */
    fun storePage(context: Context, uri: Uri): String? = try {
        keep(context, readImage(context, uri).bytes, JPEG_EXTENSION)
    } catch (e: IOException) {
        null
    } catch (e: SecurityException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    /**
     * The [pages] as what the AI import gets: one page as it is, several joined into one PDF
     * ([JpegPdf]). The files stay until the import has answered. Null when a page cannot be read.
     */
    fun join(context: Context, pages: List<String>): RecipeImportAction.ImportFile? = try {
        val jpegs = pages.map { File(it).readBytes() }
        when (jpegs.size) {
            0 -> null
            1 -> RecipeImportAction.ImportFile(jpegs.single(), JPEG, "recept.jpg", keep(context, jpegs.single(), JPEG_EXTENSION))
            else -> JpegPdf.join(jpegs)?.let { pdf ->
                RecipeImportAction.ImportFile(pdf, PDF, "recept.pdf", keep(context, pdf, PDF_EXTENSION))
            }
        }
    } catch (e: IOException) {
        null
    }

    /** Deletes a file of this session; a path that is gone already is fine. */
    fun delete(path: String?) {
        if (path != null) runCatching { File(path).delete() }
    }

    /** Takes what a killed process left behind. */
    fun sweep(context: Context) {
        val oldest = System.currentTimeMillis() - STALE_AFTER_MILLIS
        File(context.cacheDir, DIRECTORY).listFiles()?.filter { it.lastModified() < oldest }?.forEach { it.delete() }
    }

    /**
     * The sent file as bitmaps for the review, at most [MAX_PREVIEW_PAGES]: a photo, or a PDF's
     * pages through [PdfRenderer]. Empty when it cannot be read. Off the main thread.
     */
    fun previews(path: String): List<Bitmap> = try {
        if (path.endsWith(PDF_EXTENSION)) pdfPages(File(path)) else listOfNotNull(photo(path))
    } catch (e: IOException) {
        emptyList()
    } catch (e: SecurityException) {
        emptyList()
    } catch (e: OutOfMemoryError) {
        emptyList()
    }

    private fun photo(path: String): Bitmap? = BitmapFactory.decodeFile(path)

    private fun pdfPages(file: File): List<Bitmap> =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                (0 until minOf(renderer.pageCount, MAX_PREVIEW_PAGES)).map { index ->
                    renderer.openPage(index).use { page ->
                        val (width, height) = targetSize(page.width, page.height)
                        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                }
            }
        }

    /** [bytes] as a new file of this session. */
    private fun keep(context: Context, bytes: ByteArray, extension: String): String {
        val directory = File(context.cacheDir, DIRECTORY).apply { mkdirs() }
        val file = File(directory, UUID.randomUUID().toString() + extension)
        file.writeBytes(bytes)
        return file.path
    }

    private fun keepingSource(context: Context, file: RecipeImportAction.ImportFile): RecipeImportAction.ImportFile {
        val extension = if (file.mimeType == PDF) PDF_EXTENSION else JPEG_EXTENSION
        return RecipeImportAction.ImportFile(file.bytes, file.mimeType, file.fileName, keep(context, file.bytes, extension))
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
    private const val JPEG_EXTENSION = ".jpg"
    private const val PDF_EXTENSION = ".pdf"
    private const val MAX_PREVIEW_PAGES = 10
}
