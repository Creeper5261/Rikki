plugins {
    id("java")
    id("org.jetbrains.intellij") version "1.17.4"
}

group = "com.zzf"
version = "0.1.1"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

fun readUnsignedShort(bytes: ByteArray, offset: Int): Int {
    return ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
}

fun writeUnsignedShort(bytes: ByteArray, offset: Int, value: Int) {
    bytes[offset] = ((value ushr 8) and 0xFF).toByte()
    bytes[offset + 1] = (value and 0xFF).toByte()
}

fun patchInlineCompletionProviderClass(file: File) {
    if (!file.exists()) {
        throw GradleException("Inline completion provider class not found: $file")
    }
    val bytes = file.readBytes()
    var index = 8
    val constantPoolCount = readUnsignedShort(bytes, index)
    index += 2
    var nameOffset = -1
    var entryIndex = 1
    while (entryIndex < constantPoolCount) {
        val tag = bytes[index].toInt() and 0xFF
        index += 1
        when (tag) {
            1 -> {
                val length = readUnsignedShort(bytes, index)
                index += 2
                val value = String(bytes, index, length, Charsets.UTF_8)
                if (value == "getId_S2YkoFA") {
                    nameOffset = index
                }
                index += length
            }
            3, 4 -> index += 4
            5, 6 -> index += 8
            7, 8, 16, 19, 20 -> index += 2
            9, 10, 11, 12, 17, 18 -> index += 4
            15 -> index += 3
            else -> throw GradleException("Unsupported constant pool tag $tag while patching $file")
        }
        entryIndex += if (tag == 5 || tag == 6) 2 else 1
    }
    if (nameOffset < 0) {
        throw GradleException("Method name getId_S2YkoFA not found in $file")
    }
    val accessFlags = readUnsignedShort(bytes, index)
    writeUnsignedShort(bytes, index, accessFlags and 0xFBFF)
    "getId-S2YkoFA".toByteArray(Charsets.UTF_8).copyInto(bytes, nameOffset)
    file.writeBytes(bytes)
}

repositories {
    mavenCentral()
}

fun isLegacyKotlinBuildOutput(file: File): Boolean {
    val normalized = file.absolutePath.replace('\\', '/')
    return normalized.contains("/idea-plugin/build/classes/kotlin")
            || normalized.contains("/idea-plugin/build/classes/kotlin-runtime")
            || normalized.contains("/idea-plugin/build/instrumented/")
}

configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-common")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
}

dependencies {
    // Jackson is provided by the IntelliJ Platform (lib-client.jar, 2.15.x).
    // Use compileOnly so code compiles against it but the JARs are not bundled.
    compileOnly("com.fasterxml.jackson.core:jackson-databind:2.15.2")
    compileOnly("com.fasterxml.jackson.core:jackson-core:2.15.2")
    compileOnly("com.fasterxml.jackson.core:jackson-annotations:2.15.2")
    // commonmark is NOT bundled by IntelliJ — keep as implementation.
    implementation("org.commonmark:commonmark:0.21.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.21.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.15.2")
}

intellij {
    version.set("2024.1")
    type.set("IC")
    sandboxDir.set(layout.buildDirectory.dir("idea-sandbox").get().asFile.absolutePath)
    plugins.set(listOf("org.jetbrains.plugins.terminal"))
}

tasks.patchPluginXml {
    sinceBuild.set("241")
    untilBuild.set("")   // open-ended: works on 241 and all future builds
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

val patchInlineCompletionProviderAbi by tasks.registering {
    dependsOn(tasks.named("compileJava"))
    doLast {
        val providerClass = layout.buildDirectory.file("classes/java/main/com/zzf/rikki/idea/completion/RikkiInlineCompletionProvider.class").get().asFile
        patchInlineCompletionProviderClass(providerClass)
    }
}

tasks.buildSearchableOptions {
    enabled = false
}

tasks.runPluginVerifier {
    ideVersions.set(
        listOf(
            "IC-2024.1", // IntelliJ IDEA Community
            "IU-2024.1", // IntelliJ IDEA Ultimate
            "PY-2024.1", // PyCharm Professional
            "PS-2024.1", // PhpStorm
            "DB-2024.1", // DataGrip
            "WS-2024.1", // WebStorm
            "GO-2024.1", // GoLand
            "CL-2024.1", // CLion
            "RD-2024.1", // Rider
            "RM-2024.1"  // RubyMine
        )
    )
}

tasks.runIde {
    jvmArgs("-Djb.vmOptionsFile=${project.file("src/main/resources/idea.vmoptions").absolutePath}")
}

tasks.named("classes") {
    dependsOn(patchInlineCompletionProviderAbi)
}

tasks.named("instrumentCode") {
    enabled = false
}

tasks.named("instrumentTestCode") {
    enabled = false
}

tasks.named<Jar>("instrumentedJar") {
    enabled = false
}

tasks.named<org.jetbrains.intellij.tasks.PrepareSandboxTask>("prepareSandbox") {
    pluginJar.set(tasks.named<Jar>("jar").flatMap { it.archiveFile })
}

tasks.named<org.jetbrains.intellij.tasks.PrepareSandboxTask>("prepareTestingSandbox") {
    pluginJar.set(tasks.named<Jar>("jar").flatMap { it.archiveFile })
}

tasks.test {
    dependsOn(patchInlineCompletionProviderAbi)
    testClassesDirs = files(layout.buildDirectory.dir("classes/java/test"))
    classpath = files(
        layout.buildDirectory.dir("classes/java/test"),
        layout.buildDirectory.dir("resources/test"),
        layout.buildDirectory.dir("classes/java/main"),
        layout.buildDirectory.dir("resources/main"),
        configurations.testRuntimeClasspath
    ).filter { !isLegacyKotlinBuildOutput(it) }
    useJUnitPlatform()
}
