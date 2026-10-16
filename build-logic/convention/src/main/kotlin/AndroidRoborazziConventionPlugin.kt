import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.withType

/**
 * `tandem.android.roborazzi`: Roborazzi screenshot tests on Robolectric (JVM, no emulator).
 * Apply together with `tandem.android.compose` (which already adds the roborazzi test
 * libraries).
 *
 * Screenshots are only RECORDED: root gradle.properties sets roborazzi.test.record=true, so every
 * `ciTest` run writes PNGs to build/outputs/roborazzi/ and CI uploads them as an artifact.
 * Golden comparison (verifyRoborazziDebug against committed images) and an en-XA/ar-XB
 * pseudo-locale variant come at the end of Phase 1 (0.2.0, docs/BYGGE.md).
 *
 * The NATIVE graphics below also serve the ATF accessibility checks (core:testing
 * `assertAccessible`), which measure contrast on a real screenshot; Roborazzi skips them silently
 * in LEGACY mode.
 */
class AndroidRoborazziConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            // On the build classpath through the root build's `apply false` declaration.
            pluginManager.apply("io.github.takahirom.roborazzi")
            tasks.withType<Test>().configureEach {
                // Real rendering (fonts, shadows, vectors) instead of Robolectric's legacy
                // no-op graphics. Tests can still override this with @GraphicsMode.
                systemProperty("robolectric.graphicsMode", "NATIVE")
                // Pixel copy through the hardware renderer gives screenshots that match a
                // device more closely (elevation shadows, rounded clips).
                systemProperty("robolectric.pixelCopyRenderMode", "hardware")
            }
        }
    }
}
