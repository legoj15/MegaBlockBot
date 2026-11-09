pluginManagement {
    repositories {
        maven("https://maven.neoforged.net/releases")
        maven("https://maven.kikugie.dev/releases")
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Provisions the Java 21 toolchain the 1.21.x nodes need; only Java 25 is installed by hand.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("dev.kikugie.stonecutter") version "0.9.8"
}

stonecutter {
    create(rootProject) {
        // BuildCraft's NeoForge version set (also McDevBridge's). Fabric nodes, when they come, follow
        // BuildCraft's naming: version("26.3-fabric", "26.3") plus loader=fabric in that node's
        // gradle.properties. The loader is never parsed from the node name.
        versions("26.3", "26.2", "26.1.2", "1.21.11", "1.21.10", "1.21.1")
        vcsVersion = "26.3"
    }
}

rootProject.name = "MegaBlockBot"
