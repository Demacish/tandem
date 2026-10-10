// Shared test doubles for Phase 1 (plan 5.2 "core:testing"): fakes of every core:data repository
// interface with deterministic sample recipes, a MainDispatcherRule, and a loader for the
// recorded Tandoor fixtures in api-schema/fixtures. TEST-ONLY: use it with testImplementation;
// checkModuleGraph (ciLint) fails if a main configuration depends on it.
plugins {
    id("tandem.android.library")
}

android {
    sourceSets.getByName("main") {
        // api-schema/fixtures/<version>/<file> becomes the classpath resource <version>/<file>
        // (read by TandoorFixtures), so there is no further copy of the recordings.
        resources.directories.add(rootProject.file("api-schema/fixtures").path)
    }
}

dependencies {
    // The fakes implement the core:data interfaces and return core:model types.
    api(projects.core.data)
    api(projects.core.model)
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.coroutines.test)
    // MainDispatcherRule is a JUnit 4 rule (Android modules run their unit tests on JUnit 4).
    api(libs.junit4)
    implementation(libs.kotlinx.serialization.json)
}
