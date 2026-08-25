import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.2.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20"
    id("org.jetbrains.compose") version "1.11.1"
}

repositories {
    google()
    mavenCentral()
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.json:json:20240303")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
}

kotlin {
    jvmToolchain(17)
}

compose.desktop {
    application {
        mainClass = "MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Dmg)
            packageName = "SageGardenDesktop"
            packageVersion = "1.0.0"

            // jlink's automatic module detection scans compiled bytecode for module dependencies,
            // but java.net.http.HttpClient's actual implementation lives behind an internal SPI
            // (jdk.internal.net.http), which that scan doesn't reliably follow from user code like
            // GardenSyncClient — so the module has to be listed explicitly or the bundled runtime
            // is missing it entirely (java.lang.NoClassDefFoundError: java/net/http/HttpClient at
            // runtime, even though it compiles fine). jdk.crypto.ec is needed alongside it for the
            // TLS handshake against the (HTTPS) Cloud Functions endpoint.
            modules("java.net.http", "jdk.crypto.ec")

            windows {
                // Adds an install-location page to the MSI wizard instead of silently installing
                // to a fixed path — without this, jpackage defaults to Program Files with no way
                // for the person running the installer to see or change it.
                dirChooser = true
                perUserInstall = true
                menuGroup = "Sage Garden"
                shortcut = true
            }
        }
    }
}
