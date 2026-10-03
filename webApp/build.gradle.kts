import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvmToolchain(17)

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            commonWebpackConfig {
                outputFileName = "vesti.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        getByName("wasmJsMain").dependencies {
            implementation(project(":shared"))
            implementation(project(":ui"))
            implementation(libs.kotlinx.browser)
            implementation(libs.androidx.sqlite)
            implementation(libs.androidx.sqlite.web)
            // The SQLite WASM build the web worker drives.
            implementation(npm("@sqlite.org/sqlite-wasm", "3.53.4-build2"))
        }
    }
}
