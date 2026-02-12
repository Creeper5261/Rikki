plugins {
    id("java")
    id("org.springframework.boot") version "2.6.13"
    id("io.spring.dependency-management") version "1.0.15.RELEASE"
    id("jacoco")
    id("org.owasp.dependencycheck") version "12.2.0"
}

group = "com.zzf"
version = "0.0.1-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.kafka:spring-kafka")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("com.github.javaparser:javaparser-core:3.26.2")
    implementation("dev.langchain4j:langchain4j:1.10.0")
    implementation("dev.langchain4j:langchain4j-open-ai:1.10.0")

    compileOnly("org.projectlombok:lombok:1.18.30")
    annotationProcessor("org.projectlombok:lombok:1.18.30")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.mockito:mockito-inline")
}

tasks.getByName<Test>("test") {
    useJUnitPlatform()
}

tasks.jar {
    enabled = true
    archiveClassifier.set("")
}

tasks.bootJar {
    archiveClassifier.set("boot")
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    violationRules {
        rule {
            limit {
                counter = "INSTRUCTION"
                value = "COVEREDRATIO"
                minimum = "0.20".toBigDecimal()
            }
            limit {
                counter = "BRANCH"
                value = "COVEREDRATIO"
                minimum = "0.15".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}

val nvdApiKey = System.getenv("NVD_API_KEY")
val hasNvdApiKey = nvdApiKey != null && nvdApiKey.isNotBlank()
val nvdDataDir = layout.buildDirectory.dir("dependency-check-data").get().asFile

dependencyCheck {
    formats = listOf("HTML", "JSON")
    failBuildOnCVSS = 11.0f
    autoUpdate = hasNvdApiKey
    failOnError = false
    skip = !hasNvdApiKey && !nvdDataDir.exists()
    withGroovyBuilder {
        "data" {
            setProperty("directory", nvdDataDir.absolutePath)
        }
        "nvd" {
            setProperty("apiKey", nvdApiKey)
        }
    }
}
