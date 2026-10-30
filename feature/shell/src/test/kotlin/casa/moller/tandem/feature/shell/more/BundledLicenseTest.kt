package casa.moller.tandem.feature.shell.more

import casa.moller.tandem.feature.shell.navigation.BundledLicense
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fas S (integration): every licence row has its text in core:designsystem's `assets/licenses/`. */
class BundledLicenseTest {
    @Test
    fun everyLicenseHasItsText() {
        // Gradle runs unit tests in the module's directory (feature/shell).
        val assets = File("../../core/designsystem/src/main/assets")
        BundledLicense.entries.forEach { license ->
            val file = File(assets, license.assetPath)
            assertTrue("${license.name}: ${file.path} is missing", file.isFile && file.length() > 0)
        }
    }

    @Test
    fun theMlKitNoticeLinksTheTermsAndTheAnthropicTextIsMit() {
        val assets = File("../../core/designsystem/src/main/assets")
        val mlKit = File(assets, BundledLicense.ML_KIT.assetPath).readText()
        val anthropic = File(assets, BundledLicense.ANTHROPIC_JAVA.assetPath).readText()
        assertTrue(mlKit.contains("https://developers.google.com/ml-kit/terms"))
        assertTrue(anthropic.contains("Permission is hereby granted, free of charge"))
    }
}
