import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvmToolchain(17)

    jvm()

    sourceSets {
        getByName("jvmMain").dependencies {
            implementation(project(":shared"))
            implementation(project(":ui"))
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.core)
            // The synchronous, native-bundled SQLite driver the desktop host
            // opens; unlike Android there is no system SQLite to fall back on.
            implementation(libs.androidx.sqlite.bundled.jvm)
        }
    }
}

compose.desktop {
    application {
        mainClass = "org.vestifeed.desktop.MainKt"

        nativeDistributions {
            targetFormats(
                TargetFormat.Dmg,
                TargetFormat.Msi,
                TargetFormat.Deb,
            )
            packageName = "Vesti"
            packageVersion = "1.0.0"
        }
    }
}
