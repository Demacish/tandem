import casa.moller.tandem.buildlogic.withAndroid
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType

/**
 * `tandem.android.roborazzi`: Roborazzi screenshot tests on Robolectric (JVM, no emulator).
 * Apply together with `tandem.android.compose` (which already adds the roborazzi test
 * libraries).
 *
 * **Record or verify** (docs/BYGGE.md "Skärmbilder"), switched by ONE Gradle property,
 * `tandem.screenshots.verify` (root gradle.properties, overridable with `-P`):
 * - `false`: `ciTest` runs `recordRoborazzi<Variant>`, which writes every screenshot to
 *   `build/outputs/roborazzi/` (pr.yml uploads them as the `screenshots` artifact).
 * - `true`: `ciTest` runs `verifyRoborazzi<Variant>`. The committed goldens in
 *   `<module>/src/test/screenshots/` are first copied to `build/outputs/roborazzi/` (the tests
 *   capture to `$DEFAULT_ROBORAZZI_OUTPUT_DIR_PATH/<dir>/<name>.png`, the golden is the file at that
 *   path), and every capture is compared with its golden: a changed or MISSING golden fails the
 *   test and leaves `<name>_actual.png` and `<name>_compare.png` next to it.
 *
 * `updateRoborazziGoldens` copies the recorded screenshots into `src/test/screenshots/` (and drops
 * goldens that are no longer recorded). Goldens are only recorded in CI (Actions -> Screenshots ->
 * Run workflow), never on a laptop: fonts and rendering differ between machines.
 *
 * Debug builds of library modules also get Android's pseudo-locales (`en-XA` long text, `ar-XB`
 * right-to-left, plan 6.7) so screenshot tests can use `@Config(qualifiers = "en-rXA-...")` and
 * `"ar-rXB-ldrtl-..."`. The app's `localeFilters` keep them out of every APK.
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
            val verify = providers.gradleProperty(VERIFY_PROPERTY).map { it.trim().toBoolean() }.getOrElse(false)

            tasks.withType<Test>().configureEach {
                // Real rendering (fonts, shadows, vectors) instead of Robolectric's legacy
                // no-op graphics. Tests can still override this with @GraphicsMode.
                systemProperty("robolectric.graphicsMode", "NATIVE")
                // Pixel copy through the hardware renderer gives screenshots that match a
                // device more closely (elevation shadows, rounded clips).
                systemProperty("robolectric.pixelCopyRenderMode", "hardware")
            }

            pluginManager.withPlugin("com.android.library") {
                withAndroid { android -> android.buildTypes.getByName("debug").isPseudoLocalesEnabled = true }
            }

            val goldens = layout.projectDirectory.dir(GOLDEN_DIR)
            val outputs = layout.buildDirectory.dir(OUTPUT_DIR)
            if (verify) {
                val restore = tasks.register<Copy>("restoreRoborazziGoldens") {
                    group = "verification"
                    description = "Copies the committed goldens ($GOLDEN_DIR) to build/$OUTPUT_DIR for verifyRoborazzi."
                    from(goldens)
                    into(outputs)
                }
                tasks.withType<Test>().configureEach { dependsOn(restore) }
                // Roborazzi's own restore (intermediates -> outputs, only into an empty folder) must
                // see the goldens, never put an older recording in their place.
                tasks.matching { it.name == "restoreOutputDirRoborazzi" }.configureEach { dependsOn(restore) }
            }
            tasks.register<Sync>("updateRoborazziGoldens") {
                group = "verification"
                description = "Replaces the goldens in $GOLDEN_DIR with the screenshots recorded in build/$OUTPUT_DIR."
                from(outputs) {
                    include("**/*.png")
                    exclude("**/*_actual.png", "**/*_compare.png")
                }
                into(goldens)
                // Always from a fresh recording (run it with -Ptandem.screenshots.verify=false).
                dependsOn(tasks.matching { it.name == "recordRoborazziDebug" || it.name == "recordRoborazziSideloadDebug" })
            }

            // ciTest (registered later by the library plugin or app/build.gradle.kts) records or
            // verifies the variant whose unit tests it runs: Debug, or SideloadDebug in :app.
            val mode = if (verify) "verify" else "record"
            val roborazziTasks = tasks.matching { it.name == "${mode}RoborazziDebug" || it.name == "${mode}RoborazziSideloadDebug" }
            tasks.matching { it.name == "ciTest" }.configureEach { dependsOn(roborazziTasks) }
        }
    }

    private companion object {
        const val VERIFY_PROPERTY = "tandem.screenshots.verify"
        const val GOLDEN_DIR = "src/test/screenshots"
        const val OUTPUT_DIR = "outputs/roborazzi"
    }
}
