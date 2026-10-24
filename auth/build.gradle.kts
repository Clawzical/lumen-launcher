plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(21) }

dependencies {
    api(project(":core"))
    implementation(libs.jna)          // Windows Credential Manager (Advapi32 Cred* functions)
    implementation(libs.jna.platform) // FILETIME struct

    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test { useJUnitPlatform() }
