package casa.moller.tandem.core.common.crash

import java.io.File
import java.io.IOException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CrashLogTest {
    @TempDir
    lateinit var tempDir: File

    private val environment = CrashLogEnvironment(
        versionName = "0.0.2",
        versionCode = 2,
        buildType = "release",
        distribution = "sideload",
        androidRelease = "16",
        sdkInt = 36,
        manufacturer = "samsung",
        model = "SM-S921B",
    )

    private val dir: File get() = File(tempDir, CrashLogStore.DIRECTORY_NAME)
    private val store by lazy { CrashLogStore({ dir }) }

    // --- Formatter: header and redaction ---------------------------------------------------

    @Test
    fun headerHasVersionBuildAndroidAndDevice() {
        val text = CrashLogFormatter.format(environment, "main", IllegalStateException("boom"), timeMillis = 0)
        assertTrue(text.startsWith("Tandem crash log\n"), text)
        assertTrue(text.contains("Time (UTC): 1970-01-01T00:00:00Z"), text)
        assertTrue(text.contains("App: Tandem 0.0.2 (versionCode 2), release build, sideload"), text)
        assertTrue(text.contains("Android: 16 (SDK 36)"), text)
        assertTrue(text.contains("Device: samsung SM-S921B"), text)
        assertTrue(text.contains("Thread: main"), text)
        assertTrue(text.contains("java.lang.IllegalStateException: boom"), text)
        assertTrue(text.contains("\tat casa.moller.tandem.core.common.crash.CrashLogTest"), text)
    }

    @Test
    fun messagesNeverKeepTokensUrlsCredentialsOrBodies() {
        val secrets = listOf(
            "tda_11111111_2222_3333_4444_555555555555",
            "https://anna:hemligt@recept.example.com/api/",
            "hemligt",
            "anna@example.com",
            "Bearer abcdefghijklmnop",
            "{\"username\":\"anna\",\"password\":\"pw123456\"}",
            "s3cr3t-pw",
            "192.168.1.20",
        )
        val messages = listOf(
            "Token tda_11111111_2222_3333_4444_555555555555 was rejected",
            "Failed to connect to https://anna:hemligt@recept.example.com/api/space/current/",
            "Authorization: Bearer abcdefghijklmnop",
            "Unexpected JSON token at offset 3: JSON input: {\"username\":\"anna\",\"password\":\"pw123456\"}",
            "request body {\"username\":\"anna\",\"password\":\"pw123456\"} failed",
            "login failed for password=s3cr3t-pw",
            "mail anna@example.com bounced",
            "Unable to resolve host \"recept.example.com\": No address associated with hostname",
            "failed to connect to /192.168.1.20 (port 443)",
        )
        for (message in messages) {
            val redacted = CrashLogFormatter.redact(message)
            for (secret in secrets) assertFalse(redacted.contains(secret), "\"$secret\" left in \"$redacted\"")
            assertFalse(redacted.contains("anna"), redacted)
            assertFalse(redacted.contains("recept.example.com"), redacted)
        }
    }

    @Test
    fun redactionKeepsWhatHelpsToFindTheBug() {
        assertEquals("ej cannot be cast to hm2", CrashLogFormatter.redact("ej cannot be cast to hm2"))
        assertEquals(
            "No virtual method a()V in class Lq33; (declaration of 'q33' appears in base.apk)",
            CrashLogFormatter.redact("No virtual method a()V in class Lq33; (declaration of 'q33' appears in base.apk)"),
        )
        assertEquals("Expected 2 but was 3", CrashLogFormatter.redact("Expected 2 but was 3"))
    }

    @Test
    fun theWholeLogIsRedactedIncludingCausesSuppressedAndTheThreadName() {
        val cause = IOException("POST https://recept.example.com/api-token-auth/ with tda_secret_token_value")
        val crash = RuntimeException("login as \"anna\" failed", cause)
        crash.addSuppressed(IllegalArgumentException("password=hunter2"))
        val text = CrashLogFormatter.format(environment, "OkHttp https://recept.example.com/...", crash, timeMillis = 1)
        for (secret in listOf("recept.example.com", "tda_secret", "anna", "hunter2")) {
            assertFalse(text.contains(secret), "\"$secret\" left in:\n$text")
        }
        assertTrue(text.contains("Caused by: java.io.IOException: POST <url>"), text)
        assertTrue(text.contains("\tSuppressed: java.lang.IllegalArgumentException: password=<redacted>"), text)
        assertTrue(text.contains("Thread: OkHttp <url>"), text)
    }

    @Test
    fun strangeThrowablesAreFormattedWithoutThrowing() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)
        val cyclic = CrashLogFormatter.format(environment, "main", a, timeMillis = 0)
        assertTrue(cyclic.contains("[CIRCULAR REFERENCE: java.lang.RuntimeException]"), cyclic)

        val hostile = object : RuntimeException() {
            override val message: String get() = throw IllegalStateException("no message for you")

            override fun toString(): String = throw IllegalStateException("no toString either")
        }
        val text = CrashLogFormatter.format(environment, "main", hostile, timeMillis = 0)
        assertTrue(text.contains("Tandem crash log"), text)

        var deep: Throwable = RuntimeException("root")
        repeat(1_000) { deep = RuntimeException("level $it", deep) }
        val long = CrashLogFormatter.format(environment, "main", deep, timeMillis = 0)
        assertTrue(long.length <= CrashLogFormatter.MAX_LOG_LENGTH + 32, "length ${long.length}")
        assertTrue(long.contains("more causes left out"), long.takeLast(500))
    }

    // --- Store: rotation ----------------------------------------------------------------------

    @Test
    fun keepsOnlyTheNewestThreeLogs() {
        for (i in 1..5) assertNotNull(store.write("crash $i", timeMillis = 1_000L * i))
        val logs = store.read()
        assertEquals(listOf("crash 5", "crash 4", "crash 3"), logs.map { it.text })
        assertEquals(listOf(5_000L, 4_000L, 3_000L), logs.map { it.timeMillis })
        assertEquals(3, dir.listFiles()!!.size)
    }

    @Test
    fun twoCrashesInTheSameMillisecondAreBothKept() {
        store.write("first", timeMillis = 42)
        store.write("second", timeMillis = 42)
        assertEquals(setOf("first", "second"), store.read().map { it.text }.toSet())
    }

    @Test
    fun deleteAllRemovesEverythingAndToleratesAMissingFolder() {
        store.write("crash", timeMillis = 1)
        File(dir, "crash-0000000000002-00.txt.partial").writeText("half")
        store.deleteAll()
        assertTrue(store.read().isEmpty())
        assertEquals(0, dir.listFiles()!!.size)

        val missing = CrashLogStore({ File(tempDir, "never-created") })
        missing.deleteAll()
        assertTrue(missing.read().isEmpty())
    }

    @Test
    fun foreignAndPartialFilesAreIgnored() {
        dir.mkdirs()
        File(dir, "notes.txt").writeText("not a crash log")
        File(dir, "crash-0000000000001-00.txt.partial").writeText("half")
        store.write("real", timeMillis = 5)
        assertEquals(listOf("real"), store.read().map { it.text })
    }

    @Test
    fun anUnwritableFolderIsNotAnError() {
        val file = File(tempDir, "a-file").apply { writeText("x") }
        val broken = CrashLogStore({ File(file, "sub") })
        assertNull(broken.write("crash", timeMillis = 1))
        assertTrue(broken.read().isEmpty())
        val throwing = CrashLogStore({ throw SecurityException("no storage") })
        assertNull(throwing.write("crash", timeMillis = 1))
        assertTrue(throwing.read().isEmpty())
        throwing.deleteAll()
    }

    // --- Handler: writes once, always chains, never throws ----------------------------------

    @Test
    fun handlerWritesTheLogAndChainsToThePreviousHandler() {
        val seen = mutableListOf<Throwable>()
        val handler = CrashLogHandler(store, { environment }, { _, e -> seen += e }, clock = { 7_000L })
        val crash = IllegalStateException("crash with tda_abcdefabcdef")

        handler.uncaughtException(Thread.currentThread(), crash)

        assertEquals(listOf<Throwable>(crash), seen)
        val log = store.read().single()
        assertEquals(7_000L, log.timeMillis)
        assertTrue(log.text.contains("java.lang.IllegalStateException: crash with <token>"), log.text)
    }

    @Test
    fun handlerOnlyWritesTheFirstCrashButChainsEvery() {
        val seen = mutableListOf<Throwable>()
        val handler = CrashLogHandler(store, { environment }, { _, e -> seen += e })
        handler.uncaughtException(Thread.currentThread(), RuntimeException("first"))
        handler.uncaughtException(Thread.currentThread(), RuntimeException("second"))
        assertEquals(2, seen.size)
        assertEquals(1, store.read().size)
    }

    @Test
    fun aFailingWriterNeverStopsTheChain() {
        val seen = mutableListOf<Throwable>()
        val crash = RuntimeException("crash")
        val brokenEnvironment = CrashLogHandler(store, { throw OutOfMemoryError("while logging") }, { _, e -> seen += e })
        brokenEnvironment.uncaughtException(Thread.currentThread(), crash)
        val brokenStore = CrashLogHandler(CrashLogStore({ throw StackOverflowError() }), { environment }, { _, e -> seen += e })
        brokenStore.uncaughtException(Thread.currentThread(), crash)
        assertEquals(listOf<Throwable>(crash, crash), seen)
    }

    @Test
    fun installNeverStacksHandlers() {
        val original = Thread.getDefaultUncaughtExceptionHandler()
        try {
            val previous = Thread.UncaughtExceptionHandler { _, _ -> }
            Thread.setDefaultUncaughtExceptionHandler(previous)
            val first = CrashLogHandler.install(store) { environment }
            val second = CrashLogHandler.install(store) { environment }
            assertSame(first, second)
            assertSame(first, Thread.getDefaultUncaughtExceptionHandler())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
    }
}
