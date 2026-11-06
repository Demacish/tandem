package casa.moller.tandem.smoke

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.util.regex.Pattern
import org.junit.rules.ExternalResource

/**
 * Fas 4, komplettering 3: a SYNTHETIC file in the emulator's Downloads and the system file picker
 * (DocumentsUI), as [Gallery] does for the photo picker. The test APK inserts its own file into
 * MediaStore (no storage permission needed) and deletes it afterwards.
 */
class Downloads(private val context: Context) : ExternalResource() {
    private val inserted = mutableListOf<Uri>()

    override fun after() {
        inserted.forEach { uri -> runCatching { context.contentResolver.delete(uri, null, null) } }
        inserted.clear()
    }

    /** Adds a file called [name] with [content] to Downloads. */
    fun add(name: String, mimeType: String, content: ByteArray) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, "Download")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = requireNotNull(resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)) {
            "MediaStore refused the test file"
        }
        inserted += uri
        requireNotNull(resolver.openOutputStream(uri)).use { it.write(content) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
    }

    /**
     * Picks [name] in the file picker the app just opened: from the recent files it starts on, else
     * from Downloads through the drawer. Every attempt leaves a screenshot and the window hierarchy.
     */
    fun pick(app: TandemApp, name: String) {
        val device = app.device
        if (device.wait(Until.hasObject(PICKER), PICKER_TIMEOUT_MS) != true) {
            app.capture("file-picker-missing")
            throw AssertionError("The system file picker did not open")
        }
        val file = By.pkg(PICKER_PACKAGE).text(name)
        if (device.wait(Until.hasObject(file), RECENT_TIMEOUT_MS) != true) openDownloads(app)
        val deadline = SystemClock.uptimeMillis() + PICKER_TIMEOUT_MS
        var attempt = 0
        while (device.hasObject(PICKER)) {
            if (SystemClock.uptimeMillis() > deadline) {
                app.capture("file-picker-stuck")
                throw AssertionError("$name could not be picked in the file picker")
            }
            val entry = device.findObject(file)
            if (entry == null) {
                SystemClock.sleep(POLL_MS)
                continue
            }
            app.capture("file-picker-${attempt++}")
            runCatching { entry.click() }
            device.wait(Until.gone(PICKER), TAP_WAIT_MS)
        }
    }

    private fun openDownloads(app: TandemApp) {
        val device = app.device
        app.capture("file-picker-recent")
        clickIfShown(device, ROOTS)
        device.wait(Until.hasObject(DOWNLOADS), TAP_WAIT_MS)
        app.capture("file-picker-roots")
        clickIfShown(device, DOWNLOADS)
    }

    private fun clickIfShown(device: UiDevice, selector: BySelector) {
        device.findObject(selector)?.let { runCatching { it.click() } }
    }

    private companion object {
        const val PICKER_TIMEOUT_MS = 30_000L
        const val RECENT_TIMEOUT_MS = 5_000L
        const val TAP_WAIT_MS = 5_000L
        const val POLL_MS = 500L

        /** `com.google.android.documentsui` on Google's images, `com.android.documentsui` on AOSP. */
        val PICKER_PACKAGE: Pattern = Pattern.compile(".*documentsui.*")
        val PICKER: BySelector = By.pkg(PICKER_PACKAGE)
        val ROOTS: BySelector = By.pkg(PICKER_PACKAGE).desc("Show roots")
        val DOWNLOADS: BySelector = By.pkg(PICKER_PACKAGE).text("Downloads")
    }
}
