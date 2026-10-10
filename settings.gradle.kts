@file:Suppress("UnstableApiUsage")

pluginManagement {
    // The version catalog is the single source of truth. This block is evaluated
    // before Gradle exposes catalog aliases, so read the one required value here.
    val agpVersion = rootDir.resolve("gradle/libs.versions.toml")
        .readLines()
        .firstOrNull { it.trimStart().startsWith("gradle = ") }
        ?.substringAfter('"')
        ?.substringBefore('"')
        ?: error("Could not find the AGP 'gradle' version in gradle/libs.versions.toml")

    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
    plugins {
        // Both plugin declarations and root buildscript's `classpath(libs.gradle)`
        // use the version catalog's single `gradle` version entry.
        id("com.android.application").version(agpVersion)
        id("com.android.library").version(agpVersion)
        id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "Smarter Garage"
include(":mobile")
