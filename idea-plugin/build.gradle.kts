plugins {
    id("java")
    id("org.jetbrains.intellij") version "1.17.3"
    id("io.spring.dependency-management") version "1.0.15.RELEASE"
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

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:2.6.13")
    }
}

dependencies {
    implementation(project(":")) {
        exclude(group = "com.fasterxml.jackson.core")
        exclude(group = "org.springframework.boot")
        exclude(group = "org.slf4j")
        exclude(group = "ch.qos.logback")
    }
    implementation("com.fasterxml.jackson.core:jackson-databind:2.15.2")
    implementation("com.fasterxml.jackson.core:jackson-core:2.15.2")
    implementation("com.fasterxml.jackson.core:jackson-annotations:2.15.2")
    implementation("org.commonmark:commonmark:0.21.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.21.0")
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
