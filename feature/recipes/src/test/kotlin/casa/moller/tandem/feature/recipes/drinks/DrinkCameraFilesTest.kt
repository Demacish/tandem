package casa.moller.tandem.feature.recipes.drinks

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Fas S review (fix-ui): the camera originals of "Ny dryck" are deleted, and nothing outside their directory ever is. */
@RunWith(AndroidJUnit4::class)
class DrinkCameraFilesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val authority = "content://${context.packageName}${DrinkCameraFiles.AUTHORITY_SUFFIX}/drink_camera"

    private fun capture(): Pair<File, String> {
        val file = DrinkCameraFiles.newFile(context).apply { writeText("jpeg") }
        return file to "$authority/${file.name}"
    }

    @Test
    fun aCameraFileIsFoundAndDeletedAPickedPhotoIsNot() {
        val (file, uri) = capture()
        assertEquals(file.canonicalPath, DrinkCameraFiles.fileOf(context, uri)?.canonicalPath)
        DrinkCameraFiles.delete(context, uri)
        assertFalse(file.exists())

        // The photo picker's uri, another provider and a path out of the directory are left alone.
        assertNull(DrinkCameraFiles.fileOf(context, "content://media/picker/0/com.android.providers.media.photopicker/media/42"))
        assertNull(DrinkCameraFiles.fileOf(context, "content://other.example.com/drink_camera/${file.name}"))
        val outside = File(context.cacheDir, "keep-me.jpg").apply { writeText("x") }
        assertNull(DrinkCameraFiles.fileOf(context, "$authority/..%2Fkeep-me.jpg"))
        DrinkCameraFiles.delete(context, "$authority/..%2Fkeep-me.jpg")
        assertTrue(outside.exists())
        assertNull(DrinkCameraFiles.fileOf(context, null))
    }

    @Test
    fun theSweepKeepsOnlyTheFormsPhoto() {
        val (kept, keptUri) = capture()
        Thread.sleep(2)
        val (left, _) = capture()
        val outside = File(context.cacheDir, "other.jpg").apply { writeText("x") }
        DrinkCameraFiles.sweep(context, keep = listOf(keptUri, null))
        assertTrue(kept.exists())
        assertFalse(left.exists())
        assertTrue(outside.exists())
        DrinkCameraFiles.sweep(context)
        assertFalse(kept.exists())
    }
}
