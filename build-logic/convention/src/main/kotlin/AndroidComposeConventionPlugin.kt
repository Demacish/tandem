import casa.moller.tandem.buildlogic.addBundle
import casa.moller.tandem.buildlogic.bundle
import casa.moller.tandem.buildlogic.lib
import casa.moller.tandem.buildlogic.libs
import casa.moller.tandem.buildlogic.withAndroid
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * `tandem.android.compose`: Compose compiler plugin, Compose BOM, the compose-ui bundle
 * (incl. material3 1.5.0-beta01), tooling and Compose UI test libraries.
 * Apply together with `tandem.android.application` or `tandem.android.library`.
 */
class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
            withAndroid { android ->
                android.buildFeatures.compose = true
            }
            dependencies {
                val bom = platform(libs.lib("androidx-compose-bom"))
                add("implementation", bom)
                addBundle("implementation", libs.bundle("compose-ui"))
                add("debugImplementation", libs.lib("androidx-compose-ui-tooling"))
                // Registers the empty ComponentActivity used by createComposeRule() in tests.
                add("debugImplementation", libs.lib("androidx-compose-ui-test-manifest"))

                add("testImplementation", bom)
                add("testImplementation", libs.lib("androidx-compose-ui-test-junit4"))
                add("testImplementation", libs.lib("androidx-compose-ui-test-junit4-accessibility"))
                add("testImplementation", libs.lib("roborazzi"))
                add("testImplementation", libs.lib("roborazzi-compose"))
                add("testImplementation", libs.lib("roborazzi-junit-rule"))
            }
        }
    }
}
