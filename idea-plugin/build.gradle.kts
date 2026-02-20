plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.22"
    id("org.jetbrains.intellij") version "1.17.4"
}

group = "com.zzf"
version = "0.1.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

kotlin {
    jvmToolchain(17)
}

repositories {
    mavenCentral()
}

// Force Kotlin stdlib version to match what IntelliJ 2024.1 bundles.
// This prevents older versions (e.g. from the root project's Spring BOM) from
// replacing 1.9.x and breaking the Kotlin compiler at build time.
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin") {
            useVersion("1.9.22")
            because("IntelliJ 2024.1 bundles Kotlin 1.9.22; compiler and stdlib must match")
        }
    }
}

dependencies {
    implementation(project(":")) {
        exclude(group = "com.fasterxml.jackson.core")
        exclude(group = "org.springframework.boot")
        exclude(group = "org.springframework.kafka")
        exclude(group = "io.micrometer")
        exclude(group = "org.slf4j")
        exclude(group = "ch.qos.logback")
    }
    implementation("com.fasterxml.jackson.core:jackson-databind:2.15.2")
    implementation("com.fasterxml.jackson.core:jackson-core:2.15.2")
    implementation("com.fasterxml.jackson.core:jackson-annotations:2.15.2")
    implementation("org.commonmark:commonmark:0.21.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.21.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

intellij {
    version.set("2024.1")
    type.set("IC")
    sandboxDir.set(layout.buildDirectory.dir("idea-sandbox").get().asFile.absolutePath)
    plugins.set(listOf("java", "org.jetbrains.plugins.terminal"))
}

tasks.patchPluginXml {
    sinceBuild.set("241")
    untilBuild.set("")   // open-ended: works on 241 and all future builds
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs = listOf("-Xjvm-default=all")
    }
}

tasks.buildSearchableOptions {
    enabled = false
}

tasks.runIde {
    jvmArgs("-Djb.vmOptionsFile=${project.file("src/main/resources/idea.vmoptions").absolutePath}")
}

tasks.test {
    useJUnitPlatform()
}
