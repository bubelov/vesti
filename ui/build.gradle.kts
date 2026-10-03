@file:OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvmToolchain(17)

    android {
        namespace = "org.vestifeed.ui"
        compileSdk = 37
        minSdk = 34

        // Required for Compose Resources (the Material Symbols icon font) to be
        // assembled and packaged into the Android host's assets.
        androidResources {
            enable = true
        }
    }

    jvm()

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared"))
            // Exposed because the Android app hosts these composables, which
            // needs the Compose types on its classpath.
            api(compose.runtime)
            api(compose.ui)
            implementation(compose.foundation)
            implementation(compose.material3)
            // Bundles the Material Symbols icon font and resolves it on every
            // platform (Android, JVM and wasm).
            implementation(compose.components.resources)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor)
        }

        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
        }

        androidMain.dependencies {
            implementation(libs.androidx.browser)
        }

        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.androidx.sqlite.bundled.jvm)
            implementation(compose.uiTest)
        }
    }
}

compose.resources {
    // The generated accessors (Res.font.…) are only used inside :ui.
    publicResClass = false
    packageOfResClass = "org.vestifeed.ui.resources"
}
