// Stonecutter controller (root project). The per-node build is build.gradle.kts.
// ModDevGradle is declared here with `apply false` so every node can apply it without a version.
plugins {
    id("dev.kikugie.stonecutter")
    id("net.neoforged.moddev") version "2.0.147" apply false
}

// The active node: what the IDE and the unqualified tasks see. Stonecutter's
// "Set active project to ..." task rewrites this line.
stonecutter active "26.3" /* [SC] DO NOT EDIT */

// `gradlew buildAll` builds every node's jar. Enumerated from the tree so a removed or broken node
// cannot silently shrink coverage.
tasks.register("buildAll") {
    group = "build"
    description = "Build the jar for every Stonecutter node."
    dependsOn(subprojects.map { "${it.path}:build" })
}
