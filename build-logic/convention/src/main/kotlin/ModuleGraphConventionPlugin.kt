import casa.moller.tandem.buildlogic.registerModuleGraphCheck
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * `tandem.module-graph`: registers `checkModuleGraph`, which fails when the module declares a
 * dependency that breaks the rules of plan 5.2 (features use only core:{data,domain,ui,designsystem,
 * model,common}; only feature:shell depends on other features; core never depends on features;
 * core:testing only in tests; core:ai never reaches core:network/sync/database). See `ModuleGraphRules`.
 *
 * Applied by tandem.android.application, tandem.android.library and tandem.jvm.library; their
 * `ciLint` depends on `checkModuleGraph`, so pr.yml runs it in every module.
 */
class ModuleGraphConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.registerModuleGraphCheck()
    }
}
