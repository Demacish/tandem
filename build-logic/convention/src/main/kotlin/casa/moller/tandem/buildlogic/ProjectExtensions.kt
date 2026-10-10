package casa.moller.tandem.buildlogic

import org.gradle.api.Project
import org.gradle.api.artifacts.ExternalModuleDependencyBundle
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.getByType

/** The `libs` version catalog (gradle/libs.versions.toml). */
internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.lib(alias: String): Provider<MinimalExternalModuleDependency> =
    findLibrary(alias).orElseThrow {
        IllegalStateException("Library alias '$alias' is missing in gradle/libs.versions.toml")
    }

internal fun VersionCatalog.bundle(alias: String): Provider<ExternalModuleDependencyBundle> =
    findBundle(alias).orElseThrow {
        IllegalStateException("Bundle alias '$alias' is missing in gradle/libs.versions.toml")
    }

internal fun VersionCatalog.intVersion(alias: String): Int =
    findVersion(alias).orElseThrow {
        IllegalStateException("Version alias '$alias' is missing in gradle/libs.versions.toml")
    }.requiredVersion.toInt()

/** Adds every library of a catalog bundle to [configuration]. */
internal fun DependencyHandler.addBundle(
    configuration: String,
    bundle: Provider<ExternalModuleDependencyBundle>,
) {
    bundle.get().forEach { add(configuration, it) }
}

/** Default namespace for a module: `:core:network` -> `casa.moller.tandem.core.network`. */
internal fun Project.defaultNamespace(): String =
    "casa.moller.tandem." + path.removePrefix(":").replace(':', '.').replace('-', '_')

/**
 * Registers the `ciTest` / `ciLint` lifecycle tasks that the CI workflow runs in every module.
 * `ciLint` is ktlint ([configureKtlint]), the module graph rules (`checkModuleGraph`, applied
 * here through `tandem.module-graph`) and, in Android modules, Android Lint for [lintTask].
 * Task names are resolved lazily, so variant tasks created later by AGP are fine.
 */
internal fun Project.registerCiTasks(testTask: String, lintTask: String?) {
    pluginManager.apply("tandem.module-graph")
    tasks.register("ciTest") {
        group = "verification"
        description = "Runs the unit tests CI cares about for this module ($testTask)."
        dependsOn(testTask)
    }
    tasks.register("ciLint") {
        group = "verification"
        description = "Runs ktlint, the module graph check" + (lintTask?.let { " and Android Lint ($it)" } ?: "") + " for this module."
        dependsOn("ktlintCheck", "checkModuleGraph")
        if (lintTask != null) dependsOn(lintTask)
    }
}
