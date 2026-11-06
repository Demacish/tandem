package casa.moller.tandem.core.sync.drink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import casa.moller.tandem.core.data.files.CacheDirectories
import casa.moller.tandem.core.model.recipe.StepFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fas S (drycker lane): the photo of a drink created in the app (plan §8.8: "skalad till 1600 px,
 * JPEG 85 %"). The chosen `content://` image is decoded once, scaled so its longer side is at
 * most [MAX_SIDE] px and written as a JPEG into app-private storage (`files/drink-images/`, never
 * backed up: the whole `file` domain is excluded), where the `RECIPE_IMAGE` sender reads it and
 * deletes it after the upload. Nothing leaves the app except that upload to the user's Tandoor.
 */
internal interface DrinkImageStore {
    /** An app-private copy of [uri] for [accountId], or null when it cannot be read. */
    suspend fun copy(accountId: String, uri: String): File?

    /**
     * Fas 4 (#22): an app-private copy of [uri], a step's file of any type, byte for byte, with the
     * extension of [name] (Tandoor tells a picture by it); null when it cannot be read or is larger
     * than `StepFile.MAX_BYTES`. It lives with the photos, so it goes with them.
     */
    suspend fun copyFile(accountId: String, uri: String, name: String): File?

    /** Removes a copy that is no longer needed (uploaded, or the drink was cancelled). */
    suspend fun delete(path: String)

    /** Fas S review (fix-data): every copy of [accountId] (the account's rows are gone: removed, or its data cleared). */
    suspend fun deleteAccount(accountId: String)

    /**
     * Every copy of every account (no account is left: logout, "Rensa appdata"), and (Fas S
     * komplettering) the camera originals of "Ny dryck" in `cache/drink-camera/`.
     */
    suspend fun deleteAll()

    companion object {
        const val MAX_SIDE: Int = 1600
        const val JPEG_QUALITY: Int = 85

        /** The target size of an image of [width] × [height]: the longer side at most [MAX_SIDE], never enlarged. */
        fun scaledSize(width: Int, height: Int): Pair<Int, Int> {
            val longer = max(width, height)
            if (longer <= MAX_SIDE || longer <= 0) return width to height
            val factor = MAX_SIDE.toDouble() / longer
            return (width * factor).roundToInt().coerceAtLeast(1) to (height * factor).roundToInt().coerceAtLeast(1)
        }
    }
}

@Singleton
internal class AndroidDrinkImageStore @Inject constructor(@ApplicationContext private val context: Context) : DrinkImageStore {
    override suspend fun copy(accountId: String, uri: String): File? = withContext(Dispatchers.IO) {
        try {
            val source = ImageDecoder.createSource(context.contentResolver, Uri.parse(uri))
            val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val (width, height) = DrinkImageStore.scaledSize(info.size.width, info.size.height)
                decoder.setTargetSize(width, height)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
            }
            val directory = accountDirectory(accountId).apply { mkdirs() }
            val file = File(directory, "drink-${System.nanoTime()}.jpg")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, DrinkImageStore.JPEG_QUALITY, it) }
            bitmap.recycle()
            file
        } catch (e: IOException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: SecurityException) {
            null
        }
    }

    override suspend fun copyFile(accountId: String, uri: String, name: String): File? = withContext(Dispatchers.IO) {
        val target = File(accountDirectory(accountId).apply { mkdirs() }, "step-${System.nanoTime()}${extensionOf(name)}")
        val copied = try {
            context.contentResolver.openInputStream(Uri.parse(uri))?.use { input ->
                target.outputStream().use { output -> copyWithin(input, output) }
            } == true
        } catch (e: IOException) {
            false
        } catch (e: IllegalArgumentException) {
            false
        } catch (e: SecurityException) {
            false
        }
        if (!copied) target.delete()
        target.takeIf { copied }
    }

    /** False when [input] holds more than `StepFile.MAX_BYTES`. */
    private fun copyWithin(input: InputStream, output: OutputStream): Boolean {
        val buffer = ByteArray(COPY_BUFFER)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return true
            total += read
            if (total > StepFile.MAX_BYTES) return false
            output.write(buffer, 0, read)
        }
    }

    private fun extensionOf(name: String): String =
        name.substringAfterLast('.', "").filter { it.isLetterOrDigit() }.take(MAX_EXTENSION).takeIf { it.isNotEmpty() }?.let { ".$it" }.orEmpty()

    override suspend fun delete(path: String) {
        withContext(Dispatchers.IO) {
            val file = File(path)
            // Only files of this store are ever deleted.
            if (file.canonicalPath.startsWith(File(context.filesDir, DIRECTORY).canonicalPath)) file.delete()
        }
    }

    override suspend fun deleteAccount(accountId: String) {
        withContext(Dispatchers.IO) { accountDirectory(accountId).deleteRecursively() }
    }

    override suspend fun deleteAll() {
        withContext(Dispatchers.IO) {
            File(context.filesDir, DIRECTORY).deleteRecursively()
            File(context.cacheDir, CacheDirectories.DRINK_CAMERA).deleteRecursively()
        }
    }

    private fun accountDirectory(accountId: String): File = File(context.filesDir, "$DIRECTORY/${accountId.hashCode().toUInt()}")

    private companion object {
        const val DIRECTORY = "drink-images"
        const val COPY_BUFFER = 64 * 1024
        const val MAX_EXTENSION = 12
    }
}
