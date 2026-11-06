// Fas 3 (widgets lane): home-screen widgets (the shopping list, tonight's meal) on Glance, the
// quick settings tile "Handla" and what the launcher shortcuts open (app/res/xml/shortcuts.xml).
// Everything opens the app through core:ui's AppOpenIntents; data comes from core:data only.
plugins {
    id("tandem.android.feature")
}

android {
    defaultConfig {
        // ActionCallbacks are created by reflection (see the file).
        consumerProguardFiles("consumer-rules.pro")
    }
}

dependencies {
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    // ShortcutManagerCompat (the launcher shortcuts).
    implementation(libs.androidx.core.ktx)

    testImplementation(projects.core.testing)
    // A widget's content under test without a launcher: that it follows its flow in a running session.
    testImplementation(libs.androidx.glance.appwidget.testing)
}
