// Repositories (offline first) and mappers DTO <-> model. The only module features talk to
// for data; it hides core:network and core:datastore from them (plan 5.2/5.3).
plugins {
    id("tandem.android.library")
    id("tandem.android.hilt")
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
}
