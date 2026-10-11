// Repositories (offline first) and mappers DTO <-> model. The only module features talk to
// for data; it hides core:network and core:datastore from them (plan 5.2/5.3).
plugins {
    id("tandem.android.library")
    id("tandem.android.hilt")
}

// --- demo lane (Phase 1): DemoContentTest reads the demo assets (src/main/assets/demo, plan 6.2
// "Demoläge") and the recorded Tandoor 2.6.13 fixtures straight from disk on the plain JVM;
// declared as inputs so that changed content or fixtures re-run the tests. ---
val demoAssetsDir: File = file("src/main/assets/demo")
val demoFixtureDir: File = rootProject.file("api-schema/fixtures/2.6.13/fas1")
tasks.withType<Test>().configureEach {
    inputs.dir(demoAssetsDir).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("demoAssets")
    inputs.dir(demoFixtureDir).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("demoFixtures")
    systemProperty("tandem.demoAssetsDir", demoAssetsDir.absolutePath)
    systemProperty("tandem.demoFixtureDir", demoFixtureDir.absolutePath)
}

dependencies {
    // ServerAccount, TandemError, Outcome and AppSettings appear in this module's public API.
    api(projects.core.model)
    implementation(projects.core.network)
    implementation(projects.core.datastore)
    implementation(projects.core.common)

    implementation(libs.kotlinx.coroutines.android)
    // --- Phase 1 (grund lane): the repository interfaces expose Flow/StateFlow. ---
    api(libs.kotlinx.coroutines.core)

    // --- demo lane (Phase 1): DemoContent reads Tandoor-shaped JSON assets into core:network's
    // DTOs (kotlinx-datetime comes with core:model); the tests check the timers with core:domain's
    // detector. ---
    implementation(libs.kotlinx.serialization.json)
    testImplementation(projects.core.domain)
}
