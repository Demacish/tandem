import androidx.room3.gradle.RoomExtension
import casa.moller.tandem.buildlogic.addBundle
import casa.moller.tandem.buildlogic.bundle
import casa.moller.tandem.buildlogic.lib
import casa.moller.tandem.buildlogic.libs
import casa.moller.tandem.buildlogic.withAndroid
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType

/**
 * `tandem.android.room`: Room 3 (`androidx.room3`) with KSP for an Android library
 * (`:core:database`). Apply together with `tandem.android.library`.
 *
 * - Schemas are exported to `<module>/schemas/<database class>/<version>.json` and committed.
 *   pr.yml fails when a build changes or adds a schema file that is not committed (the CI
 *   artifact `room-schemas` then holds the new files, see docs/BYGGE.md "Room").
 * - Unit tests (Robolectric) get the schema folder as the system property `tandem.roomSchemaDir`,
 *   for migration tests (`MigrationTestHelper` with a schema directory).
 * - Adds room3-runtime + room3-paging (bundle `room3`), `ksp(room3-compiler)` and room3-testing for tests.
 */
class AndroidRoomConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            val schemaDir = layout.projectDirectory.dir("schemas")
            withAndroid {
                pluginManager.apply("com.google.devtools.ksp")
                pluginManager.apply("androidx.room3")
                extensions.configure<RoomExtension> {
                    schemaDirectory(schemaDir)
                }
                dependencies {
                    addBundle("implementation", libs.bundle("room3"))
                    add("ksp", libs.lib("androidx-room3-compiler"))
                    add("testImplementation", libs.lib("androidx-room3-testing"))
                }
            }
            // Not declared as a task input on purpose: the folder is written by Room's schema copy
            // task, and an undeclared producer would fail Gradle's task validation.
            val schemaPath = schemaDir.asFile.absolutePath
            tasks.withType<Test>().configureEach {
                systemProperty("tandem.roomSchemaDir", schemaPath)
            }
        }
    }
}
