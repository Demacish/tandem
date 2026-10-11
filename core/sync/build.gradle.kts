// Sync engine and offline-first repositories (plan 3.5, 3.6, 4.1, 5.3): SyncEngine, the
// WorkManager workers, SyncGuard (circuit breaker, blocked/unauthorized pauses), the outbox
// sender, the demo seeder and the app's Coil ImageLoader. Implements every Phase 1 repository
// interface of core:data on top of core:network and core:database. Features never depend on this
// module (checkModuleGraph); the app wires it with Hilt (docs/BYGGE.md "Synk").
plugins {
    id("tandem.android.library")
    id("tandem.android.hilt")
}

android {
    defaultConfig {
        // Worker class names are stored in WorkManager's database: keep them stable under R8.
        consumerProguardFiles("consumer-rules.pro")
    }
}

// --- sync lane (Phase 1): the tests seed the demo database from core:data's demo assets, read
// straight from disk (like DemoContentTest), so they do not depend on asset merging. ---
val demoAssetsDir: File = rootProject.file("core/data/src/main/assets/demo")
tasks.withType<Test>().configureEach {
    inputs.dir(demoAssetsDir).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("demoAssets")
    systemProperty("tandem.demoAssetsDir", demoAssetsDir.absolutePath)
}

dependencies {
    api(projects.core.data)
    implementation(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.core.domain)
    implementation(projects.core.network)
    implementation(projects.core.database)
    implementation(projects.core.datastore)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp)
    implementation(libs.androidx.core.ktx)
    // App start / resume triggers the sync (ProcessLifecycleOwner).
    implementation(libs.androidx.lifecycle.process)

    // The app's one Coil ImageLoader (SingletonImageLoader) with the account's image client.
    implementation(libs.coil.singleton)
    implementation(libs.coil.network.okhttp)

    // WorkManager with Hilt-injected workers (@HiltWorker); the app provides HiltWorkerFactory
    // through Configuration.Provider (TandemApplication).
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    testImplementation(projects.core.testing)
    testImplementation(libs.androidx.work.testing)
    testImplementation(platform(libs.okhttp.bom))
    testImplementation(libs.okhttp.mockwebserver3)
    testImplementation(libs.okhttp.tls)
}
