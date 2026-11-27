// Per-node build script; Stonecutter runs it once per version node.
// When the first Fabric node lands, move the neoForge {} block and the mods.toml expansion into
// buildSrc convention plugins (one per loader) the way BuildCraft does, rather than gating two plugin DSLs
// inline (docs/platform-layer.md).
import java.util.Properties

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

// A typo must not silently turn the bot off ("True".toBoolean() is true, "yes".toBoolean() is false).
val botEnabled = (property("bot_enabled") as String).also {
    require(it == "true" || it == "false") { "bot_enabled must be true or false, got '$it' (node ${project.name})" }
}.toBoolean()

// The navigation module is its own mod and jar (LGPL, so the rest of the mod stays MIT). It lives in a second
// source set so Stonecutter processes it like `main`; it is created on every node so a version switch rewrites
// src/nav/ too, but only a bot node compiles, packages and loads it.
val navModId = "${modId}_nav"
val navSourceSet = sourceSets.create("nav")

stonecutter {
    // `//? if neoforge {` is live on a node whose `loader` is neoforge, `//? if fabric {` on a fabric
    // one. Loader arms belong only in com/legoj15/megablockbot/platform/<loader>/ files and the
    // binder holders in Platform (docs/platform-layer.md).
    constants.match(loader, "neoforge", "fabric")
    // `//? if bot {` is live only where the bot is built. It guards the one call from common code into the
    // game package and the arms of platform/neoforge/ files written for the bot node's version only
    // (docs/platform-layer.md). neoforge.mods.toml is gated separately, by a template property below.
    constants.put("bot", botEnabled)
}

// Every source set (main, nav, test) is UTF-8, whatever the build machine's default charset is.
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.compileJava {
    dependsOn("stonecutterGenerate")
    // The game package touches Minecraft internals the other nodes' versions may not have; it is built where the bot is.
    if (!botEnabled) exclude("com/legoj15/megablockbot/game/**")
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

// Its own game directory keeps the GameTest run out of run/.
val gameTestDirectory = layout.projectDirectory.dir("run-gameTestServer")

neoForge {
    version = neoVersion

    if (botEnabled) {
        // The AT files sit at the tree root (src/), not in versions/<node>/, so resolve from rootProject. Both mods'
        // files apply in dev; a shipped jar carries its own copy.
        accessTransformers.from(
            rootProject.file("src/main/resources/META-INF/accesstransformer.cfg"),
            rootProject.file("src/nav/resources/META-INF/accesstransformer.cfg"),
        )
        // ModDevGradle only wires Minecraft onto `main`; the nav entrypoint needs it too.
        addModdingDependenciesTo(navSourceSet)
    } else {
        // ModDevGradle applies main's META-INF/accesstransformer.cfg by default. That file is bot-only (written for the
        // bot node's Minecraft), so another node must not pick it up: an explicitly empty set turns the default off.
        accessTransformers.files.setFrom()
    }

    runs {
        register("client") {
            client()
        }
        register("server") {
            server()
            programArgument("--nogui")
        }
        if (botEnabled) {
            // Headless GameTest server (scripts/gametest.ps1). MDG has no gameTestServer() helper; the run type is
            // the string. Its own game directory keeps it out of run/. The namespace filter limits it to our tests.
            register("gameTestServer") {
                type = "gameTestServer"
                gameDirectory = gameTestDirectory
                systemProperty("neoforge.enabledGameTestNamespaces", modId)
                // scripts/gametest.ps1 -Only: run only the named test classes (a fix loop's short run).
                System.getProperty("mbb.gametest.only")?.let { systemProperty("mbb.gametest.only", it) }
            }
        }
    }

    mods {
        register(modId) {
            sourceSet(sourceSets["main"])
        }
        if (botEnabled) {
            register(navModId) {
                sourceSet(navSourceSet)
            }
        }
    }
}

// ─── Navigation module jar ───────────────────────────────────────────────────────────────────
// Built from the nav source set, nested in the main jar by jarJar below. Lands outside build/libs so that folder
// holds only the node's shippable jar. Automatic-Module-Name is required: ModDevGradle refuses to nest a plain
// file without one.
val navJar = if (botEnabled) {
    tasks.register<Jar>("navJar") {
        group = "build"
        description = "Jar of the navigation module (its own mod, LGPL), nested into the main jar."
        archiveBaseName = "megablockbot-nav"
        archiveVersion = modVersion
        destinationDirectory = layout.buildDirectory.dir("nav-libs")
        from(navSourceSet.output)
        manifest {
            attributes(
                "Automatic-Module-Name" to "com.legoj15.megablockbot.nav",
                "Implementation-Title" to navModId,
                "Implementation-Version" to modVersion,
            )
        }
    }
} else null

// ─── solver-core ─────────────────────────────────────────────────────────────────────────────
// The planner is the sibling repo's standalone build, included in settings.gradle.kts (lookup order there).
// Its version is read from the located checkout instead of pinned here, so the two cannot drift. jarJar nests the
// library, never shades it: FML loads the nested copy as a JPMS module, and another mod nesting the same library
// (SprawlCrafting) must resolve to ONE copy, not two split packages. The range lets FML pick the newest 2.x.
val solverVersion = Properties().apply {
    gradle.includedBuild("solver-core").projectDir.resolve("gradle.properties").inputStream().use(::load)
}.getProperty("solver_version") ?: error("solver_version is missing from solver-core/gradle.properties")
val solverRange = "[$solverVersion,${solverVersion.substringBefore('.').toInt() + 1}.0.0)"

dependencies {
    implementation("com.legoj15:solver:$solverVersion")
    // On 1.21.1 FML does not load a plain non-mod jar from the JVM classpath: a dev run dies with
    // NoClassDefFoundError on the first solver class (seen here with the line removed), so the library also goes on
    // MDG's additionalRuntimeClasspath. SprawlCrafting gates that on "1.x"; that is wrong for this tree's nodes. MDG
    // refuses the configuration on 1.21.10 and 1.21.11 ("no additional classpath anymore", as on 26.x), and a dev
    // server on both, with a probe that built a RecipeGraphSolver at server start, loaded the library from
    // `implementation` alone. So it is gated on MDG's own flag, true below 1.21.9 (here: 1.21.1 only).
    if (neoForge.versionCapabilities.legacyClasspath()) {
        "additionalRuntimeClasspath"("com.legoj15:solver:$solverVersion")
    }
    jarJar("com.legoj15:solver") {
        version {
            strictly(solverRange)
            prefer(solverVersion)
        }
    }

    if (botEnabled) {
        // Main compiles against the nav classes. Not `implementation`: in a dev run MDG already loads the nav mod's own
        // output, and a second copy on the classpath would be a duplicate. In a shipped jar it is nested, below.
        compileOnly(navSourceSet.output)
        // A plain file, so ModDevGradle derives its jar-in-jar identity from Automatic-Module-Name and a hash.
        jarJar(files(navJar))
    }

    // Plain JUnit for the Minecraft-free core package. Minecraft is not on this classpath, which is the point.
    testImplementation(platform("org.junit:junit-bom:5.10.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // core/lang/model parses JSON with Gson. Minecraft bundles it on every node (main compiles against it there:
    // 2.10.1 on 1.21.1, 2.11.0 on 1.21.10, 2.13.2 on 1.21.11, read from each version manifest), but the test classpath
    // has no Minecraft, so it is added here at the oldest of those, 1.21.1's, to keep the code on API every node has.
    testImplementation("com.google.code.gson:gson:2.10.1")
}

tasks.test {
    useJUnitPlatform()
    // `-Dmbb.<name>=...` on the gradlew command line reaches the test JVM (the live model probe is switched on
    // this way, see LiveModelProbe); nothing else is forwarded.
    System.getProperties().stringPropertyNames().filter { it.startsWith("mbb.") }.forEach { name ->
        systemProperty(name, System.getProperty(name))
    }
    // The probe's table is its output, so show test output only on a run that asked for it.
    if (System.getProperty("mbb.liveProbe") != null) testLogging.showStandardStreams = true
}

// ModDevGradle must wait for Stonecutter to emit this node's sources before it resolves them (it reads main's
// resources, access transformer included), or Gradle rejects the build for an undeclared dependency.
tasks.named("createMinecraftArtifacts") {
    dependsOn("stonecutterGenerate")
}

if (botEnabled) {
    tasks.named("compileNavJava") {
        dependsOn("stonecutterGenerateNav")
    }
}

val templateProperties = mapOf(
    "mod_id" to modId,
    "mod_version" to modVersion,
    "neo_dep_range" to neoDepRange,
    "mc_dep_range" to mcDepRange,
    "java_version" to javaVersion.toString(),
    "bot" to botEnabled,
)

tasks.processResources {
    inputs.properties(templateProperties)
    filesMatching(listOf("META-INF/neoforge.mods.toml", "*.mixins.json")) {
        expand(templateProperties)
    }
    // Bot-only resources: the mixin config and the access transformer (declared in mods.toml only on a bot node),
    // and the GameTest arena. Left out of every other node's jar.
    if (!botEnabled) {
        exclude("${modId}.mixins.json", "META-INF/accesstransformer.cfg", "data/$modId/structure/**")
    }
}

tasks.named<ProcessResources>("processNavResources") {
    inputs.properties(templateProperties)
    filesMatching(listOf("META-INF/neoforge.mods.toml", "*.mixins.json")) {
        expand(templateProperties)
    }
}
