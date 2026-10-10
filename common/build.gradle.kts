import versioning.BuildConfig

plugins {
    `maven-publish`
    grim.`base-conventions`
}

repositories {
    val localOverride = if (BuildConfig.mavenLocalOverride) mavenLocal() else null

    // Grim API & PacketEvents
    val grimPublicReleases = maven("https://maven.grim.ac/public/releases") {
        mavenContent { releasesOnly() }
    }
    val grimPublicSnapshots = maven("https://maven.grim.ac/public/snapshots") {
        mavenContent { snapshotsOnly() }
    }
    val grimLegacySnapshots = maven("https://repo.grim.ac/snapshots")
    exclusiveContent {
        forRepositories(*listOfNotNull(localOverride, grimPublicReleases, grimPublicSnapshots, grimLegacySnapshots).toTypedArray())
        filter {
            includeGroup("ac.grim.grimac")
            includeGroup("com.github.retrooper")
        }
    }

    // ViaVersion
    exclusive("https://repo.viaversion.com", { mavenContent { releasesOnly() } }) {
        includeGroup("com.viaversion")
    }

    // Configuralize
    exclusive("https://nexus.scarsz.me/content/repositories/releases", { mavenContent { releasesOnly() } }) {
        includeGroup("github.scarsz")
    }

    // Cumulus
    exclusive("https://repo.opencollab.dev/maven-releases/", { mavenContent { releasesOnly() } }) {
        includeGroup("org.geysermc.api")
    }

    // Floodgate
    exclusive("https://repo.opencollab.dev/maven-snapshots/", { mavenContent { snapshotsOnly() } }) {
        includeGroup("org.geysermc.floodgate")
        includeGroup("org.geysermc.cumulus")
        includeModule("org.geysermc", "common")
        includeModule("org.geysermc", "geyser-parent")
    }

    mavenCentral()
}


dependencies {
    // compileOnly, not api: each platform bundles PE via its own JiJ/shade path,
    // so api() here would nest packetevents-api a second time (~4.2MB) in the jars.
    compileOnly(libs.packetevents.api)
    api(libs.cloud.core)
    api(libs.cloud.processors.requirements)
    api(libs.configuralize) {
        artifact {
            classifier = "slim"
        }
        exclude(group = "org.yaml", module = "snakeyaml")
    }
    // Bump snakeyaml (transitive dep of configuralize) 1.29 -> 2.2+ for geyser-fabric
    api(libs.snakeyaml)
    api(libs.fastutil)
    api(libs.adventure.text.minimessage)
    api(libs.jetbrains.annotations)
    api(libs.hikaricp)
    runtimeOnly(libs.slf4j.jdk14)
    api(libs.grim.api)
    api(libs.grim.internal)
    compileOnly(libs.grim.internal.shims)
    compileOnly(libs.mongoDriverSync)

    compileOnly(libs.geyser.base.api) {
        isTransitive = false // messes with guava otherwise
    }

    compileOnly(libs.floodgate.api)
    compileOnly(libs.viaversion)
    compileOnly(libs.viabackwards)
    compileOnly(libs.netty)
    compileOnly(libs.luckperms)

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

publishing.publications.create<MavenPublication>("maven") {
    from(components["java"])
}

// Benchmarks for the chunk section cache. Standalone mains under src/bench/java,
// compiled against main plus packetevents, never packaged and never run by `test`
// or `build`. Run one with e.g. `./gradlew :common:runSharingBench`.
sourceSets {
    create("bench") {
        java.srcDir("src/bench/java")
    }
}

configurations {
    named("benchImplementation") {
        extendsFrom(configurations["implementation"])
    }
}

dependencies {
    add("benchImplementation", sourceSets["main"].output)
    add("benchImplementation", libs.packetevents.api)
    add("benchImplementation", libs.grim.api)
}

fun registerChunkBench(name: String, benchMainClass: String) {
    tasks.register<JavaExec>(name) {
        group = "benchmark"
        classpath = sourceSets["bench"].runtimeClasspath
        mainClass.set(benchMainClass)
    }
}

registerChunkBench("runSectionBench", "ac.grim.grimac.benchmark.chunks.SectionBench")
registerChunkBench("runSharingBench", "ac.grim.grimac.benchmark.chunks.SharingBench")
registerChunkBench("runCompareBench", "ac.grim.grimac.benchmark.chunks.CompareBench")

// Runs every chunk benchmark in order: micro -> end-to-end -> before/after.
tasks.register("runChunkBenches") {
    group = "benchmark"
    description = "Runs all chunk section cache benchmarks."
    dependsOn("runCompareBench")
}

tasks.named("runCompareBench") {
    dependsOn("runSharingBench")
}

tasks.named("runSharingBench") {
    dependsOn("runSectionBench")
}
