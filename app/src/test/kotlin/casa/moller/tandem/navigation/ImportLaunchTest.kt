package casa.moller.tandem.navigation

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import casa.moller.tandem.feature.shell.navigation.ShareImportRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** "Dela till Tandem" (`ACTION_SEND`) as MainActivity reads it (Fas 3, import lane). */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class ImportLaunchTest {
    @Test
    fun aSharedLinkOrTextIsAnImport() {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, " Pannkakor https://example.com/x ")
        assertEquals(ShareImportRequest(text = "Pannkakor https://example.com/x"), ImportLaunch.requestFrom(intent))
        val long = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "x".repeat(ImportLaunch.MAX_TEXT_LENGTH + 5))
        assertEquals(ImportLaunch.MAX_TEXT_LENGTH, ImportLaunch.requestFrom(long)?.text?.length)
    }

    @Test
    fun severalSharedPhotosAreThePagesOfOneRecipe() {
        fun share(type: String, uris: ArrayList<Uri>) =
            Intent(Intent.ACTION_SEND_MULTIPLE).setType(type).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        val uris = arrayListOf(Uri.parse("content://media/1"), Uri.parse("content://media/2"))
        assertEquals(ShareImportRequest(pageUris = uris.map { it.toString() }), ImportLaunch.requestFrom(share("image/*", uris)))

        val unsafe = arrayListOf(Uri.parse("content://media/1"), Uri.parse("file:///data/secret"))
        assertEquals(null, ImportLaunch.requestFrom(share("image/*", unsafe)))
        assertEquals("only images are pages", null, ImportLaunch.requestFrom(share("application/pdf", uris)))
    }

    @Test
    fun aSharedPhotoOrPdfIsAnImportOfTheFile() {
        val photo = Intent(Intent.ACTION_SEND).setType("image/jpeg")
            .putExtra(Intent.EXTRA_STREAM, Uri.parse("content://media/external/images/1"))
        assertEquals(
            ShareImportRequest(fileUri = "content://media/external/images/1", mimeType = "image/jpeg"),
            ImportLaunch.requestFrom(photo),
        )
        val pdf = Intent(Intent.ACTION_SEND).setType("*/*").putExtra(Intent.EXTRA_STREAM, Uri.parse("content://docs/2"))
        assertEquals(ShareImportRequest(fileUri = "content://docs/2", mimeType = null), ImportLaunch.requestFrom(pdf))
    }

    @Test
    fun otherIntentsAndUnsafeStreamsAreNoImport() {
        assertNull(ImportLaunch.requestFrom(null))
        assertNull(ImportLaunch.requestFrom(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)))
        assertNull(ImportLaunch.requestFrom(Intent(Intent.ACTION_SEND).setType("text/plain")))
        assertNull(ImportLaunch.requestFrom(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "   ")))
        // A file:// uri from another app is never read.
        val file = Intent(Intent.ACTION_SEND).setType("image/jpeg")
            .putExtra(Intent.EXTRA_STREAM, Uri.parse("file:///sdcard/x.jpg"))
        assertNull(ImportLaunch.requestFrom(file))
    }
}
