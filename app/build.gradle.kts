import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose)
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":core"))
    implementation(project(":modrinth"))
    implementation(project(":launch"))
    implementation(project(":auth"))
    implementation(libs.jna)          // Windows 11 rounded corners for the custom-chrome window (DWM)
    implementation(libs.jna.platform)
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    // Large jar, but ProGuard in the release packaging tasks strips every unused icon.
    implementation(compose.materialIconsExtended)
    implementation(libs.kotlinx.coroutines.swing) // provides Dispatchers.Main for Compose Desktop
}

compose.desktop {
    application {
        mainClass = "dev.lumen.launcher.MainKt"

        // The launcher itself should stay tiny; the game gets its own tuned JVM.
        jvmArgs += listOf("-Xms32m", "-Xmx320m", "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1")

        // Azure app (client) ID for Microsoft sign-in. Keep it out of the repo: put
        // `lumen.msaClientId=<guid>` in ~/.gradle/gradle.properties. Not a secret, but it's yours.
        providers.gradleProperty("lumen.msaClientId").orNull?.let { jvmArgs += "-Dlumen.msa.clientId=$it" }

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "Lumen"
            packageVersion = "1.0.0"
            // jdk.management → OperatingSystemMXBean (total RAM detection for auto-allocation)
            modules("java.net.http", "jdk.management", "jdk.crypto.ec")

            // Generated from the logo geometry by `./gradlew :app:generateIcons`.
            windows {
                iconFile.set(project.file("icons/lumen.ico"))
                menuGroup = "Lumen"
            }
            linux { iconFile.set(project.file("icons/lumen.png")) }
        }

        buildTypes.release.proguard {
            configurationFiles.from(project.file("proguard-rules.pro"))
        }
    }
}

tasks.register<JavaExec>("generateIcons") {
    group = "lumen"
    description = "Renders the Lumen logo into icons/lumen.ico and icons/lumen.png"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.lumen.launcher.tools.IconExportKt")
    args(layout.projectDirectory.dir("icons").asFile.absolutePath)
}
