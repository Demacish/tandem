pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "tandem"

// All Phase 0 modules are declared up front so that feature lanes never need to edit this file.
include(":app")
include(":core:model")
include(":core:common")
include(":core:domain")
include(":core:network")
include(":core:datastore")
include(":core:designsystem")
include(":core:data")
include(":core:ui")
include(":feature:onboarding")
include(":feature:shell")
include(":contract-tests")
// Release smoke test: drives the R8-minified app on an emulator (smoke.yml, docs/BYGGE.md).
include(":smoke")

// --- Phase 1 (grund lane): every Phase 1a/1b module is declared here up front, so that the
// network, database, sync, demo, UI, cook and timer lanes never need to edit this file. ---
include(":core:database")
include(":core:sync")
include(":core:testing")
include(":core:timer")
include(":feature:recipes")
include(":feature:home")
include(":feature:cook")

// --- ci lane (Fas 1b): Macrobenchmark and the baseline profile generator (benchmark.yml). ---
include(":benchmark")

// --- grund lane (Fas 2, 0.3.0 "handla & planera"): the shopping list and the meal plan. Books
// live in :feature:recipes (plan 6.1). Declared up front so the lanes never edit this file. ---
include(":feature:shopping")
include(":feature:mealplan")

// --- grund lane (Fas S, 0.4.0 "Skafferi & drycker"): Hemma (pantry, Kan laga nu, Packa upp,
// basvaror), scanning (barcode, receipt, fridge photo, the shared review list) and the fridge
// photo's AI clients. Drinks live in :feature:recipes. Declared up front so the lanes never edit
// this file (docs/BYGGE.md "Fas S – kontrakt"). ---
include(":feature:pantry")
include(":feature:scan")
include(":core:ai")
