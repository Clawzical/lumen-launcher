plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(21) }

dependencies {
    api(project(":installer"))
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
