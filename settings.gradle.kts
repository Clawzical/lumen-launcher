rootProject.name = "lumen-launcher"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google() // Compose Multiplatform pulls some androidx artifacts from here
    }
}

include(
    ":core",      // instances, downloads, JVM tuning, shared HTTP client — no UI deps
    ":modrinth",  // Modrinth API v2 client + dependency resolver
    ":installer", // version JSONs, libraries, natives, assets, Java runtimes, Fabric/Quilt/NeoForge
    ":launch",    // launch command assembly + game process
    ":auth",      // Microsoft device code → Xbox Live → XSTS → Minecraft; refresh tokens in the OS keychain
    ":app",       // Compose for Desktop UI
)
// Planned modules (same shape as :modrinth):
//   :curseforge  – CurseForge API (official key or proxy)
