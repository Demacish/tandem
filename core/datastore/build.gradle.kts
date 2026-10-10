// Settings (Preferences DataStore) and the encrypted account/token store (DataStore + Tink AEAD
// with the keyset wrapped by an Android Keystore key, plan 5.5). On-disk files are listed in
// README.md; app/src/main/res/xml/data_extraction_rules.xml excludes all of them from backup
// and device transfer.
plugins {
    id("tandem.android.library")
    id("tandem.android.hilt")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    // The public API (SecureAccountStore, SettingsStore, StorageHealthMonitor) uses model types.
    api(projects.core.model)
    implementation(projects.core.common)

    implementation(libs.androidx.datastore)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.tink.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
}
