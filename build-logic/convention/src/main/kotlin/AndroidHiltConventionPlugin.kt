import casa.moller.tandem.buildlogic.lib
import casa.moller.tandem.buildlogic.libs
import casa.moller.tandem.buildlogic.withAndroid
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * `tandem.android.hilt`: KSP + Hilt (no kapt). Adds hilt-android and ksp(hilt-compiler).
 * Apply together with `tandem.android.application` or `tandem.android.library`.
 */
class AndroidHiltConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            withAndroid {
                pluginManager.apply("com.google.devtools.ksp")
                pluginManager.apply("com.google.dagger.hilt.android")
                dependencies {
                    add("implementation", libs.lib("hilt-android"))
                    add("ksp", libs.lib("hilt-compiler"))
                }
            }
        }
    }
}
