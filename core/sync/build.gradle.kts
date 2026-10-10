// Sync engine (plan 3.5, 4.1, 5.3): SyncManager, SyncOutboxWorker, PeriodicSyncWorker,
// ConnectivityMonitor, circuit breaker. Implements core:data's SyncRepository (and the
// offline-first repositories that need the network) on top of core:network and core:database.
// Features never depend on this module (checkModuleGraph); the app wires it with Hilt.
plugins {
    id("tandem.android.library")
    id("tandem.android.hilt")
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

    // WorkManager with Hilt-injected workers (@HiltWorker). The app provides the
    // HiltWorkerFactory through Configuration.Provider when the sync lane adds the workers.
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    testImplementation(projects.core.testing)
    testImplementation(libs.androidx.work.testing)
}
