plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(21) }

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    api(libs.ktor.client.core)
    api(libs.ktor.client.content.negotiation)
    api(libs.ktor.serialization.json)
    implementation(libs.ktor.client.cio)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test { useJUnitPlatform() }
