import casa.moller.tandem.buildlogic.lib
import casa.moller.tandem.buildlogic.libs
import dev.detekt.gradle.Detekt
import dev.detekt.gradle.extensions.DetektExtension
import dev.detekt.gradle.plugin.getSupportedKotlinVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType

/**
 * `tandem.detekt`: detekt (plan 5.6 "Statiskt", from the end of Phase 1) with the Compose rules
 * (`io.nlopez.compose.rules`), configured once for every module:
 *
 * - **Rules:** detekt's defaults (`buildUponDefaultConfig`) with the overrides in
 *   `config/detekt/detekt.yml`: formatting is ktlint's job, rules that only make noise in Compose
 *   code are off or ignore `@Composable`/`@Preview`, and a short list of Compose correctness rules is
 *   on. Every override in the file says why.
 * - **What is analysed:** everything under the module's `src/` (main, test, debug, flavors), in
 *   detekt's *light* mode: no type resolution, so it needs no compilation and runs in seconds.
 *   Only the plain `detekt` task is used; the per-variant/source-set tasks with type resolution
 *   (`detektMain`, ...) are never run (`detekt.android.disabled=true` in gradle.properties).
 * - **Baseline:** `<module>/detekt-baseline.xml` holds the findings that existed when detekt was
 *   introduced (Fas 1b). New code must be clean; fix a finding, or suppress it right where it is
 *   with `@Suppress("RuleId")` and a comment that says why. Never add new code to a baseline.
 *   `./gradlew detektBaseline` rewrites the baselines (only when a rule is switched on).
 * - **CI:** `ciLint` depends on `detekt` in every module (pr.yml); reports (HTML, SARIF,
 *   checkstyle) land in `build/reports/detekt/`.
 *
 * Applied automatically by tandem.android.application, tandem.android.library, tandem.jvm.library
 * and tandem.android.test.
 */
class DetektConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            // On the build classpath through the root build's `apply false` declaration.
            pluginManager.apply("dev.detekt")
            val configFile = rootProject.layout.projectDirectory.file("config/detekt/detekt.yml")
            extensions.configure<DetektExtension> {
                toolVersion.set(libs.findVersion("detekt").get().requiredVersion)
                config.setFrom(configFile)
                buildUponDefaultConfig.set(true)
                // Every source set (main, test, debug, flavors, ...); build/ is excluded by the task.
                source.setFrom(layout.projectDirectory.dir("src"))
                baseline.set(layout.projectDirectory.file("detekt-baseline.xml"))
                parallel.set(false)
            }
            dependencies {
                add("detektPlugins", libs.lib("compose-rules-detekt"))
            }
            // detekt runs the Kotlin compiler it was built with (2.4.10); never let another plugin's
            // version alignment upgrade it to the project's Kotlin (detekt refuses a mismatch).
            configurations.matching { it.name == "detekt" || it.name == "detektPlugins" }.configureEach {
                resolutionStrategy.eachDependency {
                    if (requested.group == "org.jetbrains.kotlin") useVersion(getSupportedKotlinVersion())
                }
            }
            tasks.withType<Detekt>().configureEach {
                reports {
                    html { required.set(true) }
                    sarif { required.set(true) }
                    checkstyle { required.set(true) }
                    markdown { required.set(false) }
                }
            }
            // ciLint is registered by the convention plugins (or app/build.gradle.kts) after this.
            tasks.matching { it.name == "ciLint" }.configureEach {
                dependsOn("detekt")
            }
        }
    }
}
