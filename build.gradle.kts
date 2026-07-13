import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.jvm.tasks.Jar
import java.util.zip.ZipFile

plugins {
    java
    id("com.gradleup.shadow") version "9.5.1"
}

group = "com.loohp"
version = "2026.1.2.0"
val pluginVersion = version.toString()
val paper26_1Version = "26.1.2.build.74-stable"
val paper26_2Version = "26.2.build.56-alpha"
val craftEngineVersion = "26.7.2"
val sparrowHeartVersion = "0.72"
val caffeineVersion = "3.2.3"

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
    compileOnly("net.momirealms:craft-engine-core:$craftEngineVersion")
    compileOnly("net.momirealms:craft-engine-bukkit:$craftEngineVersion")
    compileOnly(files("common/lib/LightAPI-fork-3.5.2.jar"))

    implementation("net.momirealms:sparrow-yaml:1.0.7")
    implementation("net.momirealms:sparrow-heart:$sparrowHeartVersion")
    implementation("com.github.ben-manes.caffeine:caffeine:$caffeineVersion")

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("io.papermc.paper:paper-api:$paper26_1Version")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    paper26_2CompileClasspath("io.papermc.paper:paper-api:$paper26_2Version")
    paper26_2CompileClasspath("me.clip:placeholderapi:2.11.7")
    paper26_2CompileClasspath("net.momirealms:craft-engine-core:$craftEngineVersion")
    paper26_2CompileClasspath("net.momirealms:craft-engine-bukkit:$craftEngineVersion")
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

val benchmarkSourceSet = sourceSets.create("benchmark") {
    java.setSrcDirs(listOf("benchmark/src/main/java"))
    resources.setSrcDirs(listOf("benchmark/src/main/resources"))
    compileClasspath += sourceSets.main.get().output + configurations.compileClasspath.get()
    runtimeClasspath += output + compileClasspath
}

val benchmarkJar = tasks.register<Jar>("benchmarkJar") {
    description = "Builds the standalone Paper A/B benchmark plugin (never shipped in production)."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    dependsOn(benchmarkSourceSet.classesTaskName)
    archiveClassifier = "benchmark"
    from(benchmarkSourceSet.output)
    from(sourceSets.main.get().output) {
        include("com/loohp/interactionvisualizer/entities/DroppedItemSpatialIndex*.class")
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

val legacyTextCacheDisableProperty = "interactionvisualizer.disableLegacyTextComponentCache"

tasks.named<Test>("test") {
    systemProperty(legacyTextCacheDisableProperty, "false")
    exclude("**/LegacyTextComponentCacheDisabledTest.class")
}

val testLegacyTextComponentCacheDisabled = tasks.register<Test>("testLegacyTextComponentCacheDisabled") {
    description = "Verifies the isolated JVM rollback path for legacy text component caching."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = sourceSets.test.get().output.classesDirs
    include("**/LegacyTextComponentCacheDisabledTest.class")
    systemProperty(legacyTextCacheDisableProperty, "true")
    dependsOn(tasks.testClasses)
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
    description = "Confines NMS reflection to the client pickup bridge and rejects legacy layers."
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
        val pickupBridge = file(
            "common/src/main/java/com/loohp/interactionvisualizer/integration/packet/ClientPickupAnimationBridge.java",
        ).canonicalFile
        val bridgeTokens = setOf("net.minecraft", "org.bukkit.craftbukkit")
        val violations = sources.files.flatMap { source ->
            val text = source.readText()
            forbidden.filter(text::contains).mapNotNull { token ->
                val allowed = source.canonicalFile == pickupBridge && token in bridgeTokens
                if (allowed) null else "${source.relativeTo(rootDir)}: $token"
            }
        }
        check(violations.isEmpty()) {
            "Paper-only architecture violations:\n${violations.joinToString("\n")}"
        }
    }
}

val verifyCustomContentIsolation = tasks.register("verifyCustomContentIsolation") {
    description = "Keeps optional provider APIs behind their reflection-loaded bridge implementations."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    val sources = sourceSets.main.get().allJava
    inputs.files(sources)
    doLast {
        val allowedCraftEngineSource = file(
            "common/src/main/java/com/loohp/interactionvisualizer/integration/craftengine/CraftEngineCustomContentBridge.java",
        ).canonicalFile
        val managerSource = file(
            "common/src/main/java/com/loohp/interactionvisualizer/integration/CustomContentManager.java",
        ).canonicalFile
        val stableApiClass = "net.momirealms.craftengine.bukkit.api.CraftEngineItems"
        val craftEngineToken = Regex("net\\.momirealms\\.craftengine(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)+")
        val violations = sources.files.flatMap { source ->
            craftEngineToken.findAll(source.readText()).mapNotNull { match ->
                val allowed = when (source.canonicalFile) {
                    allowedCraftEngineSource, managerSource -> match.value == stableApiClass
                    else -> false
                }
                if (allowed) null else "${source.relativeTo(rootDir)}: ${match.value}"
            }.toList()
        }.toMutableList()

        val allowedArtifacts = setOf("craft-engine-core", "craft-engine-bukkit")
        configurations.flatMap { it.dependencies }
            .filter { it.group == "net.momirealms" && it.name.startsWith("craft-engine-") }
            .filter { it.name !in allowedArtifacts }
            .forEach { dependency ->
                violations.add("Unexpected CraftEngine artifact: ${dependency.group}:${dependency.name}")
            }
        check(violations.isEmpty()) {
            "CraftEngine API escaped its optional bridge implementation:\n${violations.joinToString("\n")}"
        }
    }
}

tasks.check {
    dependsOn(compilePaper26_2)
    dependsOn(verifyPaperOnlyArchitecture)
    dependsOn(verifyCustomContentIsolation)
    dependsOn(testLegacyTextComponentCacheDisabled)
}

tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier = ""
    mergeServiceFiles()

    relocate("net.momirealms.sparrow.yaml", "com.loohp.interactionvisualizer.libs.sparrow.yaml")
    relocate("net.momirealms.sparrow.heart", "com.loohp.interactionvisualizer.libs.sparrow.heart")
    relocate("com.github.benmanes.caffeine", "com.loohp.interactionvisualizer.libs.caffeine")
    relocate("org.jspecify", "com.loohp.interactionvisualizer.libs.jspecify")
    relocate("com.google.errorprone.annotations", "com.loohp.interactionvisualizer.libs.errorprone.annotations")

    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true

    doLast {
        ZipFile(archiveFile.get().asFile).use { jar ->
            val bundledCraftEngine = jar.entries().asSequence()
                .map { it.name }
                .filter { it.startsWith("net/momirealms/craftengine/") }
                .toList()
            check(bundledCraftEngine.isEmpty()) {
                "CraftEngine must remain compileOnly, but provider classes were bundled:\n" +
                    bundledCraftEngine.joinToString("\n")
            }
            check(jar.getEntry("com/loohp/interactionvisualizer/libs/sparrow/heart/SparrowHeart.class") != null) {
                "The shaded Sparrow Heart runtime is missing"
            }
            listOf("r26_1", "r26_2").forEach { adapter ->
                check(jar.getEntry(
                    "com/loohp/interactionvisualizer/libs/sparrow/heart/impl/$adapter/Heart.class",
                ) != null) {
                    "The shaded Sparrow Heart $adapter adapter is missing"
                }
            }
            val unrelocatedHeart = jar.entries().asSequence()
                .map { it.name }
                .filter { it.startsWith("net/momirealms/sparrow/heart/") }
                .toList()
            check(unrelocatedHeart.isEmpty()) {
                "Unrelocated Sparrow Heart classes were bundled:\n" + unrelocatedHeart.joinToString("\n")
            }
            check(jar.getEntry("META-INF/licenses/sparrow-heart.txt") != null) {
                "The Sparrow Heart MIT license notice is missing"
            }
            check(jar.getEntry("META-INF/licenses/caffeine.txt") != null) {
                "The Caffeine Apache-2.0 license notice is missing"
            }
            check(jar.getEntry("META-INF/licenses/jspecify.txt") != null) {
                "The JSpecify Apache-2.0 license notice is missing"
            }
            check(jar.getEntry("META-INF/licenses/error-prone-annotations.txt") != null) {
                "The Error Prone Annotations Apache-2.0 license notice is missing"
            }
            check(jar.getEntry(
                "com/loohp/interactionvisualizer/libs/caffeine/cache/Caffeine.class",
            ) != null) {
                "The shaded Caffeine runtime is missing"
            }
            val unrelocatedCaffeine = jar.entries().asSequence()
                .map { it.name }
                .filter { it.startsWith("com/github/benmanes/caffeine/") }
                .toList()
            check(unrelocatedCaffeine.isEmpty()) {
                "Unrelocated Caffeine classes were bundled:\n" +
                    unrelocatedCaffeine.joinToString("\n")
            }
            val unrelocatedAnnotationDependencies = jar.entries().asSequence()
                .map { it.name }
                .filter {
                    it.startsWith("org/jspecify/") ||
                        it.startsWith("com/google/errorprone/annotations/")
                }
                .toList()
            check(unrelocatedAnnotationDependencies.isEmpty()) {
                "Unrelocated Caffeine annotation dependencies were bundled:\n" +
                    unrelocatedAnnotationDependencies.joinToString("\n")
            }
            check(jar.getEntry(
                "com/loohp/interactionvisualizer/libs/jspecify/annotations/NullMarked.class",
            ) != null) {
                "The relocated JSpecify annotations are missing"
            }
            check(jar.getEntry(
                "com/loohp/interactionvisualizer/libs/errorprone/annotations/CanIgnoreReturnValue.class",
            ) != null) {
                "The relocated Error Prone annotations are missing"
            }
        }
    }
}

tasks.jar {
    enabled = false
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}
