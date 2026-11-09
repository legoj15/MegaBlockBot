// Per-node build script; Stonecutter runs it once per version node.
// When the first Fabric node lands, move the neoForge {} block and the mods.toml expansion into
// buildSrc convention plugins (one per loader) the way BuildCraft does, rather than gating two plugin DSLs
// inline (docs/platform-layer.md).
plugins {
    id("net.neoforged.moddev")
}

val modId = property("mod_id") as String
val modVersion = property("mod_version") as String
val minecraftVersion = property("minecraft_version") as String
val neoVersion = property("neo_version") as String
val javaVersion = (property("java_version") as String).toInt()
val loader = property("loader") as String
val neoDepRange = property("neo_dep_range") as String
val mcDepRange = property("mc_dep_range") as String

stonecutter {
    // `//? if neoforge {` is live on a node whose `loader` is neoforge, `//? if fabric {` on a fabric
    // one. Loader arms belong only in com/legoj15/megablockbot/platform/<loader>/ files and the
    // binder holders in Platform (docs/platform-layer.md).
    constants.match(loader, "neoforge", "fabric")
}

tasks.compileJava {
    dependsOn("stonecutterGenerate")
    options.encoding = "UTF-8"
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(javaVersion)
    }
}

base {
    archivesName = modId
}
version = "$modVersion+mc$minecraftVersion"

repositories {
    mavenCentral()
    maven("https://maven.neoforged.net/releases")
}

neoForge {
    version = neoVersion

    runs {
        register("client") {
            client()
        }
        register("server") {
            server()
            programArgument("--nogui")
        }
    }

    mods {
        register(modId) {
            sourceSet(sourceSets["main"])
        }
    }
}

tasks.processResources {
    val replaceProperties = mapOf(
        "mod_id" to modId,
        "mod_version" to modVersion,
        "neo_dep_range" to neoDepRange,
        "mc_dep_range" to mcDepRange,
    )
    inputs.properties(replaceProperties)
    filesMatching("META-INF/neoforge.mods.toml") {
        expand(replaceProperties)
    }
}
