import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.jvm.tasks.Jar

plugins {
    java
    id("com.gradleup.shadow") version "9.5.1"
}

group = "com.loohp"
version = "2026.1.2.0"
val pluginVersion = version.toString()
val paper26_1Version = "26.1.2.build.74-stable"
val paper26_2Version = "26.2.build.56-alpha"

val paper26_2CompileClasspath = configurations.create("paper26_2CompileClasspath") {
    isCanBeConsumed = false
    isCanBeResolved = true
    extendsFrom(configurations.implementation.get())
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
    maven("https://repo.momirealms.net/releases")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:$paper26_1Version")
    compileOnly("me.clip:placeholderapi:2.11.7")
    compileOnly(files("common/lib/LightAPI-fork-3.5.2.jar"))

    implementation("net.momirealms:sparrow-yaml:1.0.7")

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    paper26_2CompileClasspath("io.papermc.paper:paper-api:$paper26_2Version")
    paper26_2CompileClasspath("me.clip:placeholderapi:2.11.7")
    paper26_2CompileClasspath(files("common/lib/LightAPI-fork-3.5.2.jar"))
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
    withSourcesJar()
}

sourceSets {
    main {
        java.setSrcDirs(
            listOf(
                "abstraction/src/main/java",
                "common/src/main/java",
            ),
        )
        resources.setSrcDirs(listOf("common/src/main/resources"))
    }
    test {
        java.setSrcDirs(listOf("common/src/test/java"))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:deprecation", "-Xlint:unchecked"))
}

tasks.processResources {
    inputs.property("version", pluginVersion)
    filesMatching("plugin.yml") {
        expand("project" to mapOf("version" to pluginVersion))
    }
}

val testRuntimeClasspathFiles = sourceSets.test.get().runtimeClasspath
val testClasspathJar = tasks.register<Jar>("testClasspathJar") {
    description = "Builds an ASCII-path classpath JAR for reliable tests from Unicode Windows workspaces."
    val projectPathHash = rootDir.absolutePath.hashCode().toUInt().toString(16)
    archiveFileName = "interactionvisualizer-$projectPathHash-test-classpath.jar"
    destinationDirectory = file(System.getProperty("java.io.tmpdir"))
        .resolve("interactionvisualizer-gradle")
    inputs.files(testRuntimeClasspathFiles)
    doFirst {
        manifest.attributes["Class-Path"] = testRuntimeClasspathFiles.files
            .joinToString(" ") { it.toURI().toASCIIString() }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    dependsOn(testClasspathJar)
    classpath = files(testClasspathJar.flatMap { it.archiveFile })
}

val compilePaper26_2 = tasks.register<JavaCompile>("compilePaper26_2") {
    description = "Compiles the same Paper-API-only sources against Paper 26.2."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    source = sourceSets.main.get().allJava
    classpath = paper26_2CompileClasspath
    destinationDirectory = layout.buildDirectory.dir("classes/java/paper26_2")
    options.release = 25
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:deprecation", "-Xlint:unchecked"))
}

val verifyPaperOnlyArchitecture = tasks.register("verifyPaperOnlyArchitecture") {
    description = "Rejects reintroduction of NMS, Armor Stands, or legacy compatibility layers."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    val sources = sourceSets.main.get().allJava
    inputs.files(sources)
    doLast {
        val forbidden = listOf(
            "net.minecraft",
            "org.bukkit.craftbukkit",
            "ArmorStand",
            "MCVersion",
            "com.loohp.platformscheduler",
            "com.loohp.yamlconfiguration",
            "net.md_5",
            "org.bukkit.ChatColor",
            "org.awaitility",
            "HOTBAR_MOVE_AND_READD",
            "runTaskTimerAsynchronously",
            "new Thread(",
            "new Timer(",
            "getDescription()",
            "getPluginLoader()",
            ".spigot()",
        )
        val violations = sources.files.flatMap { source ->
            val text = source.readText()
            forbidden.filter(text::contains).map { token -> "${source.relativeTo(rootDir)}: $token" }
        }
        check(violations.isEmpty()) {
            "Paper-only architecture violations:\n${violations.joinToString("\n")}"
        }
    }
}

tasks.check {
    dependsOn(compilePaper26_2)
    dependsOn(verifyPaperOnlyArchitecture)
}

tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier = ""
    mergeServiceFiles()

    relocate("net.momirealms.sparrow.yaml", "com.loohp.interactionvisualizer.libs.sparrow.yaml")

    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.jar {
    enabled = false
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}
