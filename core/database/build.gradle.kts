// Room 3 database (plan 5.7): entities, DAOs, FTS4, migrations and the exported schemas in
// schemas/ (committed; pr.yml fails when a build exports a schema that is not committed).
// Only core:data and core:sync use this module; features never do (checkModuleGraph).
plugins {
    id("tandem.android.library")
    id("tandem.android.hilt")
    id("tandem.android.room")
}

dependencies {
    // DAO signatures use core:model types (RecipeQuery, HomeSectionType, NutritionPresence) and
    // TandemDatabase extends RoomDatabase, so both are part of this module's API.
    api(projects.core.model)
    api(libs.androidx.room3.runtime)
    implementation(projects.core.common)
    implementation(libs.kotlinx.coroutines.android)
    // The raw recipe JSON is stored next to the extracted columns (plan 3.6, 5.7); the
    // ingredient conversions column is JSON too.
    implementation(libs.kotlinx.serialization.json)
}
