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
