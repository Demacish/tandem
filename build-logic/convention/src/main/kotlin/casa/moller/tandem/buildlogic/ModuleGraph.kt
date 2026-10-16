package casa.moller.tandem.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/** One declared project dependency: `configuration` of the checked project depends on `path`. */
internal data class DeclaredProjectDependency(val configuration: String, val path: String)

/**
 * The module dependency rules of plan 5.2, checked on the dependencies a module DECLARES
 * (`implementation(projects.core.network)`, `testImplementation(...)`, ...):
 *
 * 1. `:feature:*` never depends on `:core:network`, `:core:database`, `:core:sync`,
 *    `:core:datastore` or `:core:timer`; features talk to `:core:data` only (repositories and the
 *    Phase 1b `TimerController` hide the implementations).
 * 2. `:feature:*` never depends on another `:feature:*`, except `:feature:shell`, the navigation
 *    host that shows the other features' routes (docs/BYGGE.md "Navigering").
 * 3. `:core:*` never depends on `:feature:*`.
 * 4. `:core:testing` (fakes, fixtures) is only allowed in test configurations, never in code
 *    that ships.
 *
 * `:app` wires everything and is only checked for rule 4.
 */
internal object ModuleGraphRules {
    /** Modules that features must not use directly (plan 5.2). */
    val FEATURE_FORBIDDEN: Set<String> = setOf(":core:network", ":core:database", ":core:sync", ":core:datastore", ":core:timer")

    /** The one feature module that may depend on other features: it hosts their routes. */
    const val FEATURE_HOST: String = ":feature:shell"

    const val TESTING: String = ":core:testing"

    /** Test-only configurations: testImplementation, testDebugImplementation, androidTestImplementation, kspTest, ... */
    fun isTestConfiguration(name: String): Boolean = name.contains("test", ignoreCase = true)

    fun violations(projectPath: String, dependencies: List<DeclaredProjectDependency>): List<String> = dependencies
        .distinct()
        .mapNotNull { dependency -> violation(projectPath, dependency) }
        .sorted()

    private fun violation(projectPath: String, dependency: DeclaredProjectDependency): String? {
        val target = dependency.path
        val where = "$projectPath (${dependency.configuration}) -> $target"
        val isFeature = projectPath.startsWith(":feature:")
        return when {
            target == projectPath -> null

            isFeature && target in FEATURE_FORBIDDEN ->
                "$where: feature modules use :core:data, never :core:network/:core:database/:core:sync/:core:datastore/:core:timer"

            isFeature && projectPath != FEATURE_HOST && target.startsWith(":feature:") ->
                "$where: only $FEATURE_HOST may depend on another feature (it hosts their routes)"

            projectPath.startsWith(":core:") && target.startsWith(":feature:") ->
                "$where: core modules never depend on feature modules"

            target == TESTING && !isTestConfiguration(dependency.configuration) ->
                "$where: $TESTING is test-only (use testImplementation)"

            else -> null
        }
    }
}

/**
 * Fails when the module declares a dependency that breaks [ModuleGraphRules]. The violations are
 * collected while the project is configured (configuration-cache safe), the task only reports.
 */
@DisableCachingByDefault(because = "Reports violations collected at configuration time; nothing to cache.")
abstract class CheckModuleGraphTask : DefaultTask() {
    @get:Input
    abstract val projectPath: Property<String>

    @get:Input
    abstract val violations: ListProperty<String>

    @TaskAction
    fun check() {
        val found = violations.get()
        if (found.isNotEmpty()) {
            throw GradleException(
                "Module graph rules (plan 5.2, docs/BYGGE.md) are broken in ${projectPath.get()}:\n" +
                    found.joinToString(separator = "\n") { "  - $it" },
            )
        }
    }
}

/**
 * Registers `checkModuleGraph` for this project. Called by [ModuleGraphConventionPlugin]
 * (`tandem.module-graph`), which the application, library and JVM convention plugins apply.
 */
internal fun Project.registerModuleGraphCheck() {
    val violations = objects.listProperty(String::class.java)
    val collect = {
        val declared = configurations.flatMap { configuration ->
            configuration.dependencies.withType(ProjectDependency::class.java).map { dependency ->
                DeclaredProjectDependency(configuration.name, dependency.path)
            }
        }
        violations.set(ModuleGraphRules.violations(path, declared))
    }
    // Build scripts declare their dependencies after the plugins are applied.
    if (state.executed) collect() else afterEvaluate { collect() }
    val modulePath = path
    tasks.register("checkModuleGraph", CheckModuleGraphTask::class.java) {
        group = "verification"
        description = "Checks the module dependency rules of plan 5.2 for $modulePath."
        projectPath.set(modulePath)
        this.violations.set(violations)
    }
}
