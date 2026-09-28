import java.util.Properties
import java.io.FileInputStream

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(FileInputStream(localPropertiesFile))
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.sagegarden.car"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.sagegarden.car"
        // 26 (not lower) specifically so the adaptive launcher icon (mipmap-anydpi-v26) doesn't
        // need a separate legacy PNG fallback — fine for a 2023+ car head unit or Daniel's own
        // recent phone, both comfortably newer than this floor.
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "0.5"
        // Same Maps/Places Cloud project as the phone app (see MainActivity's app/build.gradle.kts)
        // — this app's package name + debug-keystore SHA-1 need adding to that API key's Android app
        // restrictions in Cloud Console before the map will actually load (see MainActivity.kt's own
        // top comment for the exact values to add).
        manifestPlaceholders["MAPS_API_KEY"] = localProperties.getProperty("MAPS_API_KEY", "")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("com.google.maps.android:maps-compose:4.3.3")
    implementation("com.google.android.gms:play-services-maps:18.2.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
