// All plugins applied here with apply false: they're only actually applied in
// the :mobile module. Declaring them here lets Gradle resolve/share their
// versions (from the version catalog) across all subprojects.
plugins {
    alias(libs.plugins.com.google.devtools.ksp) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.firebase.crashlytics) apply false
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.version.catalog.update)
}

// AGP itself must stay on the classpath the "classic" way. Its version is the
// `gradle` entry in gradle/libs.versions.toml, which pluginManagement also
// reads directly before catalog aliases are available.
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath(libs.gradle)
    }
}

// --- Keep-versions-fresh tooling -------------------------------------------
//
// 1) `./gradlew versionCatalogUpdate` (from nl.littlerobots.version-catalog-update,
//    applied above) scans every dependency AND plugin declared in
//    gradle/libs.versions.toml, checks for newer releases, and rewrites the
//    file in place with an up-to-date comment showing what's available -
//    including AGP's `gradle` entry, Kotlin, Compose, Firebase, etc. Run it
//    periodically (e.g. monthly) and review the diff before committing.
//    Add `--interactive` to pick updates one at a time instead of applying all.
//
// 2) AGP remains in the catalog because the root buildscript needs its artifact
//    on the classpath. settings.gradle.kts reads that same `gradle` entry for
//    pluginManagement, so changing it once updates both consumers.
versionCatalogUpdate {
    sortByKey = true
}
