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
 * 1. `:feature:*` depends only on `:core:{data,domain,ui,designsystem,model,common}` (an allow
 *    list, so a new implementation module such as Fas S's `:core:ai` is refused without editing
 *    this file); never on `:core:network`, `:core:database`, `:core:sync`, `:core:datastore`,
 *    `:core:timer` or `:core:ai`. Features talk to `:core:data` (repositories, the Phase 1b
 *    `TimerController` and Fas S's `FridgeVision`/`AiSettingsRepository` hide the implementations).
 *    `:core:testing` is allowed in test configurations (rule 4), other features only as rule 2 says.
 * 2. `:feature:*` never depends on another `:feature:*`, except `:feature:shell`, the navigation
 *    host that shows the other features' routes (docs/BYGGE.md "Navigering").
 * 3. `:core:*` never depends on `:feature:*`.
 * 4. `:core:testing` (fakes, fixtures) is only allowed in test configurations, never in code
 *    that ships.
 * 5. `:core:ai` (Fas S, the only reader of the user's AI key) never depends on `:core:network`,
 *    `:core:sync` or `:core:database`: it has its own HTTP clients, so no Tandoor interceptor,
 *    header, token or cookie can reach the AI calls.
 *
 * `:app` wires everything and is only checked for rule 4.
 */
internal object ModuleGraphRules {
    /** The only `:core:*` modules a feature may use (plan 5.2); everything else is refused. */
    val FEATURE_ALLOWED_CORE: Set<String> =
        setOf(":core:data", ":core:domain", ":core:ui", ":core:designsystem", ":core:model", ":core:common")

    /** The AI module (Fas S, B22): reads the user's AI key, has its own HTTP clients. */
    const val AI: String = ":core:ai"

    /** What [AI] never reaches: Tandoor's network stack and everything that wraps it. */
    val AI_FORBIDDEN: Set<String> = setOf(":core:network", ":core:sync", ":core:database")

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

            isFeature && !target.startsWith(":feature:") && target != TESTING && target !in FEATURE_ALLOWED_CORE ->
                "$where: feature modules use only :core:{data,domain,ui,designsystem,model,common}, " +
                    "never :core:network/:core:database/:core:sync/:core:datastore/:core:timer/:core:ai"

            isFeature && projectPath != FEATURE_HOST && target.startsWith(":feature:") ->
                "$where: only $FEATURE_HOST may depend on another feature (it hosts their routes)"

            projectPath.startsWith(":core:") && target.startsWith(":feature:") ->
                "$where: core modules never depend on feature modules"

            target == TESTING && !isTestConfiguration(dependency.configuration) ->
                "$where: $TESTING is test-only (use testImplementation)"

            projectPath == AI && target in AI_FORBIDDEN ->
                "$where: core:ai never depends on core:network/core:sync/core:database " +
                    "(no Tandoor interceptor, header, token or cookie may reach the AI calls)"

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
