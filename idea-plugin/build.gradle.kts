plugins {
    id("java")
    id("org.jetbrains.intellij") version "1.17.3"
}

group = "com.zzf"
version = "0.1.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:2.6.13"))
    implementation(project(":"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.15.2")
}

intellij {
    version.set("2023.2")
    type.set("IC")
    sandboxDir.set(layout.buildDirectory.dir("idea-sandbox").get().asFile.absolutePath)
    plugins.set(listOf("java"))
}

tasks.patchPluginXml {
    sinceBuild.set("232")
    untilBuild.set("241.*")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.buildSearchableOptions {
    enabled = false
}

tasks.runIde {
    jvmArgs("-Djb.vmOptionsFile=${project.file("src/main/resources/idea.vmoptions").absolutePath}")
}
