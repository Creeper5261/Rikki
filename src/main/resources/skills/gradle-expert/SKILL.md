---
name: gradle-expert
description: Expert guidance for Gradle Kotlin DSL (build.gradle.kts) management, dependency resolution, and multi-module configuration.
---

# Gradle Expert Skill

Use this skill when the user asks for help with Gradle builds, dependencies, or configuration, especially for Kotlin DSL (`build.gradle.kts`).

## Capabilities

1.  **Dependency Management**: Adding, updating, or removing dependencies with correct syntax.
2.  **Version Catalog**: Migrating to or using `libs.versions.toml`.
3.  **Multi-Module Setup**: Configuring `settings.gradle.kts` and subproject dependencies.
4.  **Task Customization**: Creating custom Gradle tasks in Kotlin DSL.
5.  **Troubleshooting**: Analyzing build errors and suggesting fixes.

## Best Practices (Kotlin DSL)

-   **Plugins Block**:
    ```kotlin
    plugins {
        id("java")
        id("org.springframework.boot") version "3.2.0"
        kotlin("jvm") version "1.9.20"
    }
    ```

-   **Dependencies**:
    ```kotlin
    dependencies {
        implementation("org.springframework.boot:spring-boot-starter-web")
        testImplementation("org.junit.jupiter:junit-jupiter")
        // Use project dependency for multi-module
        implementation(project(":core"))
    }
    ```

-   **Repositories**:
    ```kotlin
    repositories {
        mavenCentral()
    }
    ```

## Task Rules

1.  **Prefer Kotlin DSL**: Unless explicitly asked for Groovy, always provide code snippets in Kotlin DSL (`.kts`).
2.  **Check Context**: Before suggesting changes, try to `READ_FILE` the existing `build.gradle.kts` or `settings.gradle.kts`.
3.  **Version Compatibility**: Ensure suggested plugin versions are compatible with the project's Java/Kotlin version.
4.  **Verification**: After modifying build scripts, suggest running `./gradlew clean build` to verify.
