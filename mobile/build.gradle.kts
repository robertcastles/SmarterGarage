import java.util.Properties

plugins {
    id("com.android.application")
    alias(libs.plugins.com.google.devtools.ksp)
    alias(libs.plugins.firebase.crashlytics)
    alias(libs.plugins.google.services)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.lusion.sgauto"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.lusion.sgauto"
        minSdk = 29
        // Intentionally pinned to 36 (compileSdk is 37). Bumping targetSdk opts into
        // new OS behavior changes and needs deliberate testing, so this stays until
        // we're ready. Suppress the advisory "not targeting latest SDK" lint.
        //noinspection OldTargetApi,GradleDependency,ExpiredTargetSdkVersion
        targetSdk = 36
        versionCode = 576
        versionName = "2025.${versionCode}"
//        testInstrumentationRunner ("androidx.test.runner.AndroidJUnitRunner")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin.jvmToolchain(21)

    buildFeatures {
        compose = true
    }

    val keystorePropertiesFile = rootProject.file("keystore.properties")
    val keystoreProperties = Properties()
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
    }

    val releaseStoreFile = keystoreProperties.getProperty("storeFile")
    val releaseStorePassword = keystoreProperties.getProperty("storePassword")
    val releaseKeyAlias = keystoreProperties.getProperty("keyAlias")
    val releaseKeyPassword = keystoreProperties.getProperty("keyPassword")
    val hasReleaseSigning = !releaseStoreFile.isNullOrBlank() &&
        !releaseStorePassword.isNullOrBlank() &&
        !releaseKeyAlias.isNullOrBlank() &&
        !releaseKeyPassword.isNullOrBlank()

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

//    composeCompiler {
//        reportsDestination = layout.buildDirectory.dir("compose_compiler")
//        stabilityConfigurationFile = rootProject.layout.projectDirectory.file("stability_config.conf")
//    }
//    composeOptions {
//        kotlinCompilerExtensionVersion = "1.5.10"
//        kotlinCompilerExtensionVersion (rootProject.composeVersion)
//    }

        buildTypes {
            release {
                if (hasReleaseSigning) {
                    signingConfig = signingConfigs.getByName("release")
                }
                isMinifyEnabled = true
                isShrinkResources = true
                proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro"
                )
                isDebuggable = false
                // NOTE: we have no native/NDK code of our own, and the two prebuilt
                // .so files that end up in the bundle (androidx.graphics.path,
                // androidx.datastore) are published pre-stripped by Google with no
                // debug symbols to extract. Neither `ndk.debugSymbolLevel` nor
                // Crashlytics' `nativeSymbolUploadEnabled` can produce anything
                // useful here, so both were removed — the Play Console "missing
                // native debug symbols" warning is a known, effectively unfixable
                // false positive for apps pulling these libraries in transitively.
            }
            debug {
                isMinifyEnabled = false
                isDebuggable = true
            }
        }

}

dependencies {

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.crashlytics)
    implementation(libs.firebase.analytics)

    implementation(libs.retrofit)
    implementation(libs.converter.gson)
    implementation(libs.okhttp)

    implementation(libs.activity.compose)
    implementation(libs.appcompat)
    implementation(libs.car.app)
    implementation(libs.core.ktx)
    implementation(libs.foundation)
    implementation(libs.gson)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.lifecycle.process)
    implementation(libs.material)
    implementation(libs.material3)
    implementation(libs.play.services.location)
    implementation(libs.preference.ktx)
    implementation(libs.ui.tooling)
    implementation(libs.ui.tooling.preview)
    implementation(libs.ui.tooling.preview.android)
    implementation(libs.work.runtime.ktx)
}
