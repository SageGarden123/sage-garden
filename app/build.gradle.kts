import java.util.Properties
import java.io.FileInputStream

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(FileInputStream(localPropertiesFile))
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.services)
}

android {
    namespace = "com.example.sagegarden"
    compileSdk = 37

    defaultConfig {
        // The Play Store identity — deliberately kept separate from `namespace` above (which stays
        // com.example.sagegarden, matching every Kotlin file's actual package declaration) rather
        // than renaming the whole source tree. Google rejects any com.example.* applicationId
        // outright for real publishing (it's a reserved placeholder, not just usually-taken), which
        // is why this looked like a generic "already in use" collision even with random suffixes.
        applicationId = "com.sagegarden.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "1.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "PLANTNET_API_KEY", "\"${localProperties.getProperty("PLANTNET_API_KEY", "")}\"")
        buildConfigField("String", "DROPBOX_APP_KEY", "\"${localProperties.getProperty("DROPBOX_APP_KEY", "")}\"")
        buildConfigField("String", "MAPS_API_KEY", "\"${localProperties.getProperty("MAPS_API_KEY", "")}\"")
        buildConfigField("String", "SAGE_API_BASE_URL", "\"${localProperties.getProperty("SAGE_API_BASE_URL", "")}\"")
        manifestPlaceholders["MAPS_API_KEY"] = localProperties.getProperty("MAPS_API_KEY", "")
    }

    signingConfigs {
        create("release") {
            storeFile = file(localProperties.getProperty("RELEASE_STORE_FILE", "release-keystore-not-configured.jks"))
            storePassword = localProperties.getProperty("RELEASE_STORE_PASSWORD", "")
            keyAlias = localProperties.getProperty("RELEASE_KEY_ALIAS", "")
            keyPassword = localProperties.getProperty("RELEASE_KEY_PASSWORD", "")
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            optimization {
                enable = true
                keepRules {
                    files.add(file("proguard-rules.pro"))
                }
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation(libs.androidx.compose.ui.geometry)
    implementation(kotlin("stdlib"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("com.dropbox.core:dropbox-core-sdk:8.0.1")
    implementation("com.dropbox.core:dropbox-android-sdk:8.0.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")

// Room (local database)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

// Location
    implementation("com.google.android.gms:play-services-location:21.2.0")

// Google Maps
    implementation("com.google.maps.android:maps-compose:4.3.3")
    implementation("com.google.android.gms:play-services-maps:18.2.0")
    implementation(libs.places)

// Image loading (for camera photos)
    implementation("io.coil-kt:coil-compose:2.6.0")

// Home screen widget
    implementation("androidx.glance:glance-appwidget:1.1.1")

// Firebase App Check (Play Integrity) — attests requests to the Sage Cloud Functions backend
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.appcheck.playintegrity)


}