package casa.moller.tandem.smoke

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.util.regex.Pattern
import org.junit.rules.ExternalResource

/**
 * Fas S (integration): SYNTHETIC images in the emulator's gallery and the system photo picker
 * (docs/BYGGE.md "Release-röktest"). The test APK inserts its own images into MediaStore (no
 * storage permission needed for its own files) and deletes them again afterwards; the app picks
 * them with Android's photo picker like a user would, so the bundled ML Kit models, CameraX's
 * neighbours and the photo pipeline run in the R8-minified app.
 *
 * Nothing here is a real receipt or product: the receipt has made-up lines without card or member
 * numbers, the barcode is in GS1's restricted range 20–29.
 */
class Gallery(private val context: Context) : ExternalResource() {
    private val inserted = mutableListOf<Uri>()

    override fun after() {
        inserted.forEach { uri -> runCatching { context.contentResolver.delete(uri, null, null) } }
        inserted.clear()
    }

    /** Adds [bitmap] as the newest photo of the gallery (JPEG) and returns its uri. */
    fun add(name: String, bitmap: Bitmap): Uri {
        // One photo at a time: the picker then shows exactly the one the test means.
        after()
        val resolver = context.contentResolver
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name-$now.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TandemSmoke")
            put(MediaStore.Images.Media.DATE_TAKEN, now)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)) {
            "MediaStore refused the test image"
        }
        inserted += uri
        requireNotNull(resolver.openOutputStream(uri)).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        return uri
    }

    /**
     * Picks the newest photo in the system photo picker the app just opened (single choice: one
     * tap returns to the app). Thumbnails are found by their resource id or description; the
     * picker's own layout differs between Android builds, so a large clickable tile in the grid is
     * the last resort. Every attempt leaves a screenshot and the window hierarchy.
     */
    fun pickNewest(app: TandemApp) {
        val device = app.device
        if (device.wait(Until.hasObject(PICKER), PICKER_TIMEOUT_MS) != true) {
            app.capture("photo-picker-missing")
            throw AssertionError("The system photo picker did not open")
        }
        // The grid loads after the window: wait for a tile.
        val deadline = SystemClock.uptimeMillis() + PICKER_TIMEOUT_MS
        var attempt = 0
        while (device.hasObject(PICKER)) {
            if (SystemClock.uptimeMillis() > deadline) {
                app.capture("photo-picker-stuck")
                throw AssertionError("No photo could be picked in the photo picker")
            }
            val tile = thumbnail(device)
            if (tile == null) {
                SystemClock.sleep(POLL_MS)
                continue
            }
            app.capture("photo-picker-${attempt++}")
            runCatching { tile.click() }
            device.wait(Until.gone(PICKER), TAP_WAIT_MS)
            // A picker in multiple-choice mode needs its "Done"/"Add" button.
            if (device.hasObject(PICKER)) device.findObject(CONFIRM)?.let { runCatching { it.click() } }
        }
    }

    private fun thumbnail(device: UiDevice): UiObject2? {
        THUMBNAILS.forEach { selector -> device.findObjects(selector).firstOrNull()?.let { return it } }
        // Last resort: the top-left large clickable tile below the picker's header.
        val header = device.displayHeight / HEADER_FRACTION
        return device.findObjects(By.pkg(PICKER_PACKAGE).clickable(true))
            .filter { tile ->
                val bounds = runCatching { tile.visibleBounds }.getOrNull() ?: return@filter false
                val large = bounds.width() >= MIN_TILE_PX && bounds.height() >= MIN_TILE_PX
                large && bounds.top > header && tile.text.isNullOrBlank()
            }
            .minByOrNull { tile -> tile.visibleBounds.let { it.top * device.displayWidth + it.left } }
    }

    companion object {
        private const val JPEG_QUALITY = 92
        private const val PICKER_TIMEOUT_MS = 30_000L
        private const val TAP_WAIT_MS = 5_000L
        private const val POLL_MS = 500L
        private const val HEADER_FRACTION = 5
        private const val MIN_TILE_PX = 120

        /** The photo picker lives in the MediaProvider module (`com.google.android.providers.media.module` or AOSP's). */
        private val PICKER_PACKAGE: Pattern = Pattern.compile(".*providers\\.media.*|.*photopicker.*")
        private val PICKER: BySelector = By.pkg(PICKER_PACKAGE)
        private val CONFIRM: BySelector = By.pkg(PICKER_PACKAGE).clickable(true).text(Pattern.compile("Done|Add|Select|Allow"))
        private val THUMBNAILS = listOf(
            By.pkg(PICKER_PACKAGE).res(Pattern.compile(".*:id/icon_thumbnail")),
            By.pkg(PICKER_PACKAGE).desc(Pattern.compile("(?i)photo taken.*")),
            By.pkg(PICKER_PACKAGE).clickable(true).desc(Pattern.compile("(?i).*(photo|image).*")),
        )

        /** A white till receipt with three item lines, a total and a card line (no numbers of a card). */
        fun receipt(): Bitmap {
            val lines = listOf(
                "ICA EXEMPEL" to null,
                "KVITTO 0001" to null,
                "PASTA" to "19,90",
                "PARMESAN" to "39,90",
                "VISPGRÄDDE 5DL" to "21,95",
                "TOTALT 3 VAROR" to "81,75",
                "KORT" to "81,75",
                "TACK FÖR BESÖKET" to null,
            )
            val bitmap = Bitmap.createBitmap(RECEIPT_WIDTH, RECEIPT_TOP + lines.size * RECEIPT_LINE + RECEIPT_TOP, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = RECEIPT_TEXT
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            }
            lines.forEachIndexed { index, (text, price) ->
                val y = RECEIPT_TOP + (index + 1) * RECEIPT_LINE.toFloat()
                canvas.drawText(text, RECEIPT_MARGIN, y, paint)
                if (price != null) canvas.drawText(price, RECEIPT_WIDTH - RECEIPT_MARGIN - paint.measureText(price), y, paint)
            }
            return bitmap
        }

        /** [code] (13 digits) as an EAN-13 barcode on white, with quiet zones. */
        fun ean13(code: String): Bitmap {
            require(code.length == EAN_DIGITS && code.all { it.isDigit() }) { "EAN-13 needs 13 digits: $code" }
            val digits = code.map { it - '0' }
            val parity = PARITY[digits[0]]
            val modules = buildString {
                append("101")
                for (i in 1..6) append(if (parity[i - 1] == 'L') L_CODES[digits[i]] else G_CODES[digits[i]])
                append("01010")
                for (i in 7..12) append(R_CODES[digits[i]])
                append("101")
            }
            val width = (modules.length + 2 * QUIET_MODULES) * MODULE_PX
            val bitmap = Bitmap.createBitmap(width, BAR_HEIGHT + 2 * BAR_MARGIN, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val paint = Paint().apply { color = Color.BLACK }
            modules.forEachIndexed { index, module ->
                if (module == '1') {
                    val left = (QUIET_MODULES + index) * MODULE_PX.toFloat()
                    canvas.drawRect(left, BAR_MARGIN.toFloat(), left + MODULE_PX, (BAR_MARGIN + BAR_HEIGHT).toFloat(), paint)
                }
            }
            return bitmap
        }

        /** A shelf of coloured shapes: something to send as "the fridge" (the fake AI answers anyway). */
        fun fridge(): Bitmap {
            val bitmap = Bitmap.createBitmap(FRIDGE_WIDTH, FRIDGE_HEIGHT, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(0xEE, 0xF2, 0xF5))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            listOf(Color.rgb(0xF5, 0xF5, 0xF0), Color.rgb(0xF2, 0xD3, 0x4F), Color.rgb(0x3A, 0x6E, 0xA5)).forEachIndexed { index, color ->
                paint.color = color
                val left = FRIDGE_MARGIN + index * (FRIDGE_ITEM + FRIDGE_MARGIN)
                val bottom = FRIDGE_HEIGHT - FRIDGE_MARGIN
                canvas.drawRoundRect(left, FRIDGE_MARGIN, left + FRIDGE_ITEM, bottom, FRIDGE_RADIUS, FRIDGE_RADIUS, paint)
            }
            return bitmap
        }

        private const val RECEIPT_WIDTH = 1000
        private const val RECEIPT_TOP = 60
        private const val RECEIPT_LINE = 110
        private const val RECEIPT_TEXT = 56f
        private const val RECEIPT_MARGIN = 50f
        private const val EAN_DIGITS = 13
        private const val QUIET_MODULES = 12
        private const val MODULE_PX = 8
        private const val BAR_HEIGHT = 420
        private const val BAR_MARGIN = 120
        private const val FRIDGE_WIDTH = 1200
        private const val FRIDGE_HEIGHT = 900
        private const val FRIDGE_MARGIN = 60f
        private const val FRIDGE_ITEM = 300f
        private const val FRIDGE_RADIUS = 40f
        private val L_CODES = listOf(
            "0001101", "0011001", "0010011", "0111101", "0100011",
            "0110001", "0101111", "0111011", "0110111", "0001011",
        )
        private val G_CODES = listOf(
            "0100111", "0110011", "0011011", "0100001", "0011101",
            "0111001", "0000101", "0010001", "0001001", "0010111",
        )
        private val R_CODES = listOf(
            "1110010", "1100110", "1101100", "1000010", "1011100",
            "1001110", "1010000", "1000100", "1001000", "1110100",
        )
        private val PARITY = listOf("LLLLLL", "LLGLGG", "LLGGLG", "LLGGGL", "LGLLGG", "LGGLLG", "LGGGLL", "LGLGLG", "LGLGGL", "LGGLGL")
    }
}
