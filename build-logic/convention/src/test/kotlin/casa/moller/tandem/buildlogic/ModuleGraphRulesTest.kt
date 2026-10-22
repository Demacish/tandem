package casa.moller.tandem.buildlogic

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * The module dependency rules of plan 5.2 ([ModuleGraphRules], `checkModuleGraph`). Runs in pr.yml
 * as `./gradlew :build-logic:convention:test`, so a rule cannot be weakened by accident.
 */
class ModuleGraphRulesTest {

    private fun violations(project: String, vararg deps: Pair<String, String>): List<String> =
        ModuleGraphRules.violations(project, deps.map { (configuration, path) -> DeclaredProjectDependency(configuration, path) })

    @Test
    fun theAllowedFeatureDependenciesPass() {
        val allowed = listOf(":core:data", ":core:domain", ":core:ui", ":core:designsystem", ":core:model", ":core:common")
        allowed.forEach { target ->
            assertEquals(emptyList<String>(), violations(":feature:cook", "implementation" to target), target)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = [":core:network", ":core:database", ":core:sync", ":core:datastore", ":core:timer"])
    fun aFeatureNeverUsesAnImplementationModule(target: String) {
        val found = violations(":feature:recipes", "implementation" to target)
        assertEquals(1, found.size, "$target must be forbidden for features: $found")
        assertTrue(found.single().startsWith(":feature:recipes (implementation) -> $target:"), found.single())
    }

    @ParameterizedTest
    @ValueSource(strings = ["implementation", "api", "debugImplementation", "testImplementation", "compileOnly"])
    fun theCookModeReachesTimersOnlyThroughCoreData(configuration: String) {
        // Fas 1b: the cook mode talks to TimerController (core:data); core:timer is the
        // implementation that :app binds with Hilt, in every configuration, tests included.
        val found = violations(":feature:cook", configuration to ":core:timer")
        assertEquals(1, found.size, found.toString())
        assertTrue(found.single().contains("never :core:network/:core:database/:core:sync/:core:datastore/:core:timer"), found.single())
    }

    @Test
    fun theForbiddenListIsComplete() {
        assertEquals(
            setOf(":core:network", ":core:database", ":core:sync", ":core:datastore", ":core:timer"),
            ModuleGraphRules.FEATURE_FORBIDDEN,
        )
    }

    @Test
    fun onlyTheShellMayDependOnOtherFeatures() {
        assertEquals(
            emptyList<String>(),
            violations(
                ":feature:shell",
                "implementation" to ":feature:recipes",
                "implementation" to ":feature:home",
                "implementation" to ":feature:cook",
            ),
        )
        val found = violations(":feature:recipes", "implementation" to ":feature:cook")
        assertEquals(1, found.size, found.toString())
        assertTrue(found.single().contains("only :feature:shell may depend on another feature"), found.single())
    }

    @Test
    fun theShellIsStillAFeature() {
        val found = violations(":feature:shell", "implementation" to ":core:sync")
        assertEquals(1, found.size, "the navigation host must not reach core:sync either: $found")
    }

    @Test
    fun coreNeverDependsOnAFeature() {
        val found = violations(":core:ui", "implementation" to ":feature:recipes")
        assertEquals(listOf(":core:ui (implementation) -> :feature:recipes: core modules never depend on feature modules"), found)
    }

    @Test
    fun coreTestingIsTestOnly() {
        assertEquals(
            emptyList<String>(),
            violations(
                ":feature:home",
                "testImplementation" to ":core:testing",
                "testDebugImplementation" to ":core:testing",
                "androidTestImplementation" to ":core:testing",
            ),
        )
        assertEquals(1, violations(":app", "implementation" to ":core:testing").size)
        assertEquals(1, violations(":core:sync", "debugImplementation" to ":core:testing").size)
    }

    @Test
    fun theAppMayWireEverything() {
        val found = violations(
            ":app",
            "implementation" to ":core:timer",
            "implementation" to ":core:sync",
            "implementation" to ":feature:cook",
            "testImplementation" to ":core:testing",
        )
        assertEquals(emptyList<String>(), found)
    }

    @Test
    fun aModuleMayReferToItselfAndDuplicatesAreReportedOnce() {
        assertEquals(emptyList<String>(), violations(":feature:cook", "testImplementation" to ":feature:cook"))
        val found = violations(":feature:cook", "implementation" to ":core:timer", "implementation" to ":core:timer")
        assertEquals(1, found.size, found.toString())
    }

    @Test
    fun violationsAreSorted() {
        val found = violations(":feature:cook", "implementation" to ":core:timer", "api" to ":core:network")
        assertEquals(found.sorted(), found)
        assertEquals(2, found.size)
    }
}
