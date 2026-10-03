import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)

    android {
        namespace = "org.vestifeed.shared"
        compileSdk = 37
        minSdk = 34
    }

    jvm()

    // The web target. The whole point of the de-JVM'd core: the same parser,
    // database and backend run in the browser.
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        nodejs()
    }

    sourceSets {
        commonMain.dependencies {
            // The multiplatform SQLite API. On Android and the JVM it is the
            // synchronous driver; in the browser it is the Web Worker driver.
            implementation(libs.androidx.sqlite)
            // Multiplatform JSON, the Gson replacement.
            api(libs.kotlinx.serialization.json)
            // Multiplatform HTTP, the OkHttp replacement.
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            // Multiplatform date/time, the java.time replacement.
            implementation(libs.kotlinx.datetime)
            // The java.io replacement: streaming sources and byte strings.
            implementation(libs.okio)
            // The java.util.concurrent replacement: atomics and locks.
            implementation(libs.atomicfu)
            implementation(libs.kotlinx.coroutines.core)
            // HTML and XML parsing for the feed reader, the jsoup replacement.
            implementation(libs.ksoup)
        }

        androidMain.dependencies {
            implementation(libs.ktor.client.cio)
        }

        jvmMain.dependencies {
            implementation(libs.ktor.client.cio)
        }

        getByName("wasmJsMain") {
            dependencies {
                // The browser HTTP engine the shared client resolves at runtime.
                implementation(libs.ktor.client.js)
                // The Web Worker SQLite driver.
                implementation(libs.androidx.sqlite.web)
            }
        }

        jvmTest.dependencies {
            implementation(libs.androidx.sqlite.bundled.jvm)
            implementation(libs.junit)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
            implementation(libs.okhttp.mockwebserver)
        }
    }
}
