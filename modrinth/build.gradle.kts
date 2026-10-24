plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(21) }

dependencies {
    api(project(":core"))
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
