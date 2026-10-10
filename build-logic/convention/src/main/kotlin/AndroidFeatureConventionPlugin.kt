import casa.moller.tandem.buildlogic.addBundle
import casa.moller.tandem.buildlogic.bundle
import casa.moller.tandem.buildlogic.lib
import casa.moller.tandem.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * `tandem.android.feature`: library + compose + hilt, plus what every feature module needs.
 * Dependency rule (plan 5.2): features depend on core:{data,domain,ui,designsystem,model,common},
 * never on core:network or core:database directly.
 */
class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("tandem.android.library")
            pluginManager.apply("tandem.android.compose")
            pluginManager.apply("tandem.android.hilt")
            dependencies {
                add("implementation", project(":core:data"))
                add("implementation", project(":core:domain"))
                add("implementation", project(":core:ui"))
                add("implementation", project(":core:designsystem"))
                add("implementation", project(":core:model"))
                add("implementation", project(":core:common"))

                add("implementation", libs.lib("androidx-hilt-lifecycle-viewmodel-compose"))
                addBundle("implementation", libs.bundle("lifecycle-compose"))
                addBundle("implementation", libs.bundle("navigation3"))
                add("implementation", libs.lib("kotlinx-coroutines-android"))
            }
        }
    }
}
