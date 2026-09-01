import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    java
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

// ─── Coordinates ─────────────────────────────────────────────────────────────
group   = "com.devlens"
version = "0.1.0-SNAPSHOT"

// ─── Java toolchain ──────────────────────────────────────────────────────────
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release   = 17
}

// ─── Dependency versions (pinned – no ranges) ────────────────────────────────
val mcpVersion            = "2.0.1"
val treeSitterVersion     = "0.26.6"
val tsJavaVersion         = "0.23.5"
val tsJavascriptVersion   = "0.25.0"
val tsTypescriptVersion   = "0.23.2"
val jgitVersion           = "7.7.1.202607240634-r"
val slf4jVersion          = "2.0.18"
val junitVersion          = "5.14.1"

// ─── Repositories ─────────────────────────────────────────────────────────────
repositories {
    mavenCentral()
}

// ─── Dependency management (import BOMs) ─────────────────────────────────────
dependencies {
    // MCP BOM – pins all io.modelcontextprotocol.sdk:* versions
    implementation(platform("io.modelcontextprotocol.sdk:mcp-bom:$mcpVersion"))
    // JUnit BOM – pins all org.junit.* versions
    testImplementation(platform("org.junit:junit-bom:$junitVersion"))

    // ── Production ────────────────────────────────────────────────────────────
    // MCP server + STDIO/SSE/Streamable-HTTP transports, bundled with Jackson 3
    implementation("io.modelcontextprotocol.sdk:mcp")

    // Repo access without requiring a git binary on PATH
    implementation("org.eclipse.jgit:org.eclipse.jgit:$jgitVersion")

    // tree-sitter core + grammars (bonede lineage: precompiled natives, Java 11+)
    // Do NOT use io.github.tree-sitter:jtreesitter — requires JDK 23+.
    implementation("io.github.bonede:tree-sitter:$treeSitterVersion")
    implementation("io.github.bonede:tree-sitter-java:$tsJavaVersion")
    implementation("io.github.bonede:tree-sitter-javascript:$tsJavascriptVersion")
    implementation("io.github.bonede:tree-sitter-typescript:$tsTypescriptVersion")

    // IMPORTANT: stdout is the JSON-RPC channel on STDIO transport.
    // slf4j-simple is configured via simplelogger.properties to write to stderr only.
    implementation("org.slf4j:slf4j-simple:$slf4jVersion")

    // ── Test ──────────────────────────────────────────────────────────────────
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.modelcontextprotocol.sdk:mcp-test")
}

// ─── Unit tests ───────────────────────────────────────────────────────────────
tasks.test {
    useJUnitPlatform()
    // Exclude integration tests from the normal test task (they need the fat jar)
    exclude("**/*IT.class")
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
}

// ─── Fat JAR (shadow) ─────────────────────────────────────────────────────────
// Equivalent of maven-shade-plugin: every dependency (including tree-sitter
// native libraries) must be inside the jar so the MCP client can launch it as
// `java -jar devlens.jar` with no classpath flag.
tasks.named<ShadowJar>("shadowJar") {
    archiveBaseName    = "devlens"
    archiveClassifier  = ""          // no "-all" suffix; matches Maven output name
    archiveVersion     = ""          // produces devlens.jar, not devlens-0.1.0.jar

    manifest {
        attributes(
            "Main-Class"             to "com.devlens.DevLensServer",
            // tree-sitter calls System.load (restricted from JDK 24).
            // Declaring native access here keeps the JVM warning-free without
            // requiring every MCP client to pass --enable-native-access=ALL-UNNAMED.
            "Enable-Native-Access"   to "ALL-UNNAMED",
            "Implementation-Version" to project.version,
            "Implementation-Title"   to project.name
        )
    }

    // Merge META-INF/services/* from all jars so Jackson modules etc. are found
    mergeServiceFiles()

    // Drop signature files from signed jars so the fat jar is not rejected
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class")
}

// Make sure the fat jar is always built when `assemble` is invoked
tasks.assemble {
    dependsOn(tasks.named("shadowJar"))
}

// ─── Integration tests ────────────────────────────────────────────────────────
// These run AFTER the fat jar is built and launch it as a real subprocess —
// the same way an MCP client would. Native library loading and stdout purity
// can only be verified this way.
val integrationTest by tasks.registering(Test::class) {
    description = "Runs integration tests (*IT) against the shaded fat jar."
    group       = LifecycleBasePlugin.VERIFICATION_GROUP

    useJUnitPlatform()
    // Only classes whose name ends in IT
    include("**/*IT.class")
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath        = sourceSets["test"].runtimeClasspath

    // Same environment variables that maven-failsafe provides
    val dataDir = project.findProperty("devlens.it.dataDir")
            ?: "${project.projectDir}/devlens-data"
    environment("DEVLENS_DATA_DIR", dataDir)

    // Tell the IT where to find the fat jar
    systemProperty("devlens.jar", tasks.named<ShadowJar>("shadowJar").get().archiveFile.get())

    // Must run after the fat jar exists
    dependsOn(tasks.named("shadowJar"))

    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
}

// Wire integration tests into the `check` lifecycle
tasks.check {
    dependsOn(integrationTest)
}

// ─── Output jar location message ─────────────────────────────────────────────
tasks.named<ShadowJar>("shadowJar").configure {
    doLast {
        println("\n✓ Fat JAR written to: ${archiveFile.get()}")
        println("  Run with:  java -jar ${archiveFile.get()}")
        println("  Or set DEVLENS_DATA_DIR first:\n" +
                "    DEVLENS_DATA_DIR=./devlens-data java -jar ${archiveFile.get()}\n")
    }
}

