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

// solver-core is the shared planner, a standalone Gradle build in the sibling SprawlCrafting repo. Consumed as a
// composite build so dev needs no publish step; the shipped jar nests it (jarJar in build.gradle.kts), never shades it.
// Looked up in this order. The third form is what a git worktree under <parent>/MegaBlockBot-wt/<name> needs.
run {
    val override = providers.gradleProperty("MBB_SOLVER_CORE_DIR")
        .orElse(providers.environmentVariable("MBB_SOLVER_CORE_DIR")).orNull
    val candidates = listOf(
        "MBB_SOLVER_CORE_DIR (Gradle property or environment variable)" to override?.let { file(it) },
        "../SprawlCrafting/solver-core" to file("../SprawlCrafting/solver-core"),
        "../../SprawlCrafting/solver-core" to file("../../SprawlCrafting/solver-core"),
    )
    fun isSolverCore(dir: File?) = dir != null && File(dir, "settings.gradle.kts").isFile && File(dir, "gradle.properties").isFile

    // An override that points nowhere is skipped, not fatal, but said out loud: a typo must not silently pick a sibling.
    if (override != null && !isSolverCore(file(override))) {
        logger.warn("MBB_SOLVER_CORE_DIR=$override is not a solver-core directory; trying the sibling checkouts")
    }
    val solverCore = candidates.firstNotNullOfOrNull { (_, dir) -> dir?.takeIf(::isSolverCore) }
        ?: throw GradleException(
            "solver-core (SprawlCrafting/solver-core) not found. Looked for it, in order, at:\n" +
                candidates.joinToString("\n") { (name, dir) -> "  - $name" + (dir?.let { " = ${it.canonicalPath}" } ?: " (not set)") } +
                "\nCheck out SprawlCrafting next to this repo, or point MBB_SOLVER_CORE_DIR at its solver-core directory."
        )
    logger.lifecycle("solver-core: ${solverCore.canonicalPath}")

    includeBuild(solverCore) {
        // Named so build.gradle.kts can find its directory and read solver_version from it.
        name = "solver-core"
        dependencySubstitution {
            substitute(module("com.legoj15:solver")).using(project(":"))
        }
    }
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
